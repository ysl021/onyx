"""
ONYX Discord PC bridge.

Receives now-playing data from the Android app over LAN and sets Discord
Rich Presence through the official Discord IPC (pypresence).

Install:
    pip install pypresence

Run:
    python bridge/onyx_bridge.py

Then in ONYX: Settings -> Discord -> enable the PC bridge and enter this
computer's LAN IP (for example 192.168.1.42) and port 8787.

Design notes:
  * The phone sends the playback position, so Discord gets both `start` and
    `end` timestamps and draws the progress bar.
  * Updates are coalesced: bursts (pause -> new track -> play) collapse into
    the final state, and Discord's 5-updates-per-20-seconds limit is respected.
    The newest state always wins, so the title and duration never get stuck on
    the previous track.
  * Covers are looked up in public catalogues (Deezer, iTunes, Spotify oEmbed)
    because Discord fetches external images server-side and cannot reach the
    phone's LAN address.
"""

from __future__ import annotations

import argparse
import json
import re
import threading
import time
import urllib.parse
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from pypresence import Presence
    from pypresence.types import ActivityType
except ImportError:
    raise SystemExit("Install pypresence first: pip install pypresence")

APP_ID = "1568847197397717073"
GITHUB = "https://github.com/ysl021/onyx"
DEFAULT_PORT = 8787

IDLE_CLEAR_SECONDS = 90
RECONNECT_SECONDS = 10
COALESCE_SECONDS = 0.25
RATE_WINDOW = 20.0
RATE_LIMIT = 5

presence = Presence(APP_ID)
ipc_lock = threading.Lock()

connected = False
latest: dict | None = None
latest_lock = threading.Lock()
wake = threading.Event()

applied_track: dict | None = None
applied_at = 0.0
tokens: deque[float] = deque()

art_cache: dict[str, str | None] = {}
cover_cache: dict[str, str | None] = {}


def log(message: str) -> None:
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


# ---------------------------------------------------------------- connection


def ensure_connected() -> bool:
    global connected
    with ipc_lock:
        if connected:
            return True
        try:
            presence.connect()
            connected = True
            log("connected to Discord IPC")
        except Exception as exc:  # Discord is not running yet
            connected = False
            log(f"Discord IPC unavailable: {exc}")
        return connected


def drop_connection(reason: str) -> None:
    global connected
    with ipc_lock:
        connected = False
    log(f"Discord IPC lost: {reason}")


# -------------------------------------------------------------------- covers


def _normalize(text: str) -> str:
    text = text.lower()
    text = re.sub(r"\([^)]*\)|\[[^\]]*\]", " ", text)
    text = re.sub(r"[^\w\s]", " ", text)
    return " ".join(text.split())


def _similar(a: str, b: str) -> bool:
    left = set(_normalize(a).split())
    right = set(_normalize(b).split())
    if not left or not right:
        return False
    return len(left & right) / min(len(left), len(right)) >= 0.6


def _json(url: str) -> dict | None:
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "ONYX/1.0"})
        with urllib.request.urlopen(request, timeout=3) as response:
            return json.load(response)
    except Exception:
        return None


def spotify_cover(url: str) -> str | None:
    if url in art_cache:
        return art_cache[url]
    cover = None
    try:
        endpoint = "https://open.spotify.com/oembed?url=" + urllib.parse.quote(url, safe="")
        request = urllib.request.Request(endpoint, headers={"User-Agent": "ONYX/1.0"})
        with urllib.request.urlopen(request, timeout=3) as response:
            cover = json.load(response).get("thumbnail_url")
    except Exception:
        cover = None
    art_cache[url] = cover
    return cover


def deezer_cover(title: str, artist: str, album: str) -> str | None:
    query = " ".join(part for part in (artist, title, album) if part)
    data = _json("https://api.deezer.com/search?limit=5&q=" + urllib.parse.quote(query))
    for item in (data or {}).get("data", []):
        hit_title = item.get("title") or ""
        hit_artist = (item.get("artist") or {}).get("name") or ""
        if _similar(title, hit_title) and (not artist or _similar(artist, hit_artist)):
            album_info = item.get("album") or {}
            cover = album_info.get("cover_xl") or album_info.get("cover_big")
            if cover:
                return cover
    return None


def itunes_cover(title: str, artist: str) -> str | None:
    query = " ".join(part for part in (artist, title) if part)
    data = _json(
        "https://itunes.apple.com/search?entity=song&limit=5&term="
        + urllib.parse.quote(query)
    )
    for item in (data or {}).get("results", []):
        hit_title = item.get("trackName") or ""
        hit_artist = item.get("artistName") or ""
        if _similar(title, hit_title) and (not artist or _similar(artist, hit_artist)):
            art = item.get("artworkUrl100") or ""
            if art:
                return art.replace("100x100bb", "600x600bb")
    return None


def deezer_artist_cover(title: str, artist: str, album: str) -> str | None:
    """
    Fallback for tracks the Deezer search index does not return: find the artist,
    then scan their album track lists for a title match. Bounded by a deadline so
    it can never block the presence worker for long.
    """
    if not artist:
        return None
    found = _json(
        "https://api.deezer.com/search/artist?limit=5&q=" + urllib.parse.quote(artist)
    )
    deadline = time.time() + 8
    for hit in (found or {}).get("data", []):
        if not _similar(artist, hit.get("name") or ""):
            continue
        albums = _json(f"https://api.deezer.com/artist/{hit['id']}/albums?limit=100")
        items = (albums or {}).get("data", [])
        # Albums named after the track are the most likely home for it.
        items.sort(key=lambda al: 0 if _similar(title, al.get("title") or "") else 1)
        for entry in items[:40]:
            if time.time() > deadline:
                return None
            detail = _json(f"https://api.deezer.com/album/{entry['id']}")
            for track in ((detail or {}).get("tracks") or {}).get("data", []):
                if _similar(title, track.get("title") or ""):
                    return (
                        (detail or {}).get("cover_xl")
                        or (detail or {}).get("cover_big")
                        or entry.get("cover_xl")
                        or entry.get("cover_big")
                    )
    return None


def _variants(title: str, artist: str) -> list[tuple[str, str]]:
    """YouTube imports often swap title/artist or append '- Topic'."""
    raw = [(title, artist), (artist, title)]
    cleaned: list[tuple[str, str]] = []
    for t, a in raw:
        t2 = re.sub(r"\s*-\s*topic$", "", t, flags=re.I).strip()
        a2 = re.sub(r"\s*-\s*topic$", "", a, flags=re.I).strip()
        cleaned.extend([(t, a), (t2, a2), (t2, a), (t, a2)])
    seen: set[tuple[str, str]] = set()
    out: list[tuple[str, str]] = []
    for pair in cleaned:
        if pair not in seen:
            seen.add(pair)
            out.append(pair)
    return out


lookup_lock = threading.Lock()
lookups: set[str] = set()


def cover_key(title: str, artist: str, album: str, link: str) -> str:
    spotify = "spotify:" + link if "open.spotify.com" in link else ""
    return spotify + f"{_normalize(artist)}|{_normalize(title)}|{_normalize(album)}"


def lookup_cover(title: str, artist: str, album: str, link: str) -> str | None:
    if "open.spotify.com" in link:
        cover = spotify_cover(link)
        if cover:
            return cover
    for attempt_title, attempt_artist in _variants(title, artist):
        cover = deezer_cover(attempt_title, attempt_artist, album) \
            or itunes_cover(attempt_title, attempt_artist)
        if cover:
            return cover
    # Search indexes often miss small releases; scan the artist's albums instead.
    for attempt_title, attempt_artist in _variants(title, artist):
        cover = deezer_artist_cover(attempt_title, attempt_artist, album)
        if cover:
            return cover
    return None


def request_cover(data: dict) -> None:
    """Resolve artwork off the worker thread so a lookup never delays an update."""
    title = (data.get("title") or "").strip()
    artist = (data.get("artist") or "").strip()
    album = (data.get("album") or "").strip()
    link = (data.get("url") or "").strip()
    key = cover_key(title, artist, album, link)
    with lookup_lock:
        if key in cover_cache or key in lookups:
            return
        lookups.add(key)

    def run() -> None:
        cover = lookup_cover(title, artist, album, link)
        with lookup_lock:
            cover_cache[key] = cover
            lookups.discard(key)
        log(f"cover {'found: ' + cover if cover else 'not found for ' + artist + ' - ' + title}")
        if cover:
            # Re-apply the same track now that the artwork is known.
            enqueue(dict(data))

    threading.Thread(target=run, daemon=True).start()


# ------------------------------------------------------------------ presence


def enqueue(data: dict) -> None:
    global latest
    with latest_lock:
        latest = data
    wake.set()


def _take_latest() -> dict | None:
    global latest
    with latest_lock:
        data = latest
        latest = None
    return data


def _rate_wait() -> float:
    """Seconds to wait before the next update fits Discord's rate limit."""
    now = time.time()
    while tokens and now - tokens[0] > RATE_WINDOW:
        tokens.popleft()
    if len(tokens) < RATE_LIMIT:
        return 0.0
    return RATE_WINDOW - (now - tokens[0]) + 0.05


def build_args(data: dict) -> dict | None:
    title = (data.get("title") or "").strip()
    if not title:
        return None
    artist = (data.get("artist") or "").strip()
    album = (data.get("album") or "").strip()
    # "YouTube" is a source label, not an album. Artist pages also often send
    # the artist name as the album, which duplicated the state line.
    if album and (
        album.casefold() in ("youtube", "youtube music")
        or _normalize(album) == _normalize(title)
        or (artist and _normalize(album) == _normalize(artist))
    ):
        album = ""
    playing = bool(data.get("playing"))
    duration = max(0, int(data.get("durationMs") or 0))
    position = max(0, int(data.get("positionMs") or 0))
    if duration:
        position = min(position, duration)
    link = (data.get("url") or GITHUB).strip()

    state_parts: list[str] = []
    for part in (artist, album):
        if part and _normalize(part) not in {_normalize(p) for p in state_parts}:
            state_parts.append(part)
    state_line = " • ".join(state_parts)[:128]
    key = cover_key(title, artist, album, link)
    with lookup_lock:
        cover = cover_cache.get(key)
    if cover is None:
        request_cover(data)
    is_spotify = "open.spotify.com" in link

    args = dict(
        activity_type=ActivityType.LISTENING,
        details=title[:128],
        state=state_line or "ONYX",
        large_image=cover or "onyx_large",
        large_text=(album or artist or "ONYX")[:128],
        small_image="playing" if playing else "pause",
        small_text="ONYX",
        buttons=[{
            "label": "Открыть в Spotify" if is_spotify else "ONYX — GitHub",
            "url": link,
        }],
    )

    # The original working bridge used Unix SECONDS here; keep that format so
    # Discord draws its Spotify-style progress bar.
    if playing:
        start = int(time.time() - position / 1000)
        args["start"] = start
        if duration > 0:
            args["end"] = int(start + duration / 1000)

    return {
        "args": args,
        "track": {
            "title": title,
            "artist": artist,
            "album": album,
            "playing": playing,
            "positionMs": position,
            "durationMs": duration,
            "url": link,
            "cover": cover,
        },
    }


def apply_now(payload: dict) -> bool:
    global applied_track, applied_at
    if not ensure_connected():
        return False
    try:
        with ipc_lock:
            presence.update(**payload["args"])
    except Exception as exc:
        message = str(exc).lower()
        if "rate" in message or "limit" in message:
            log(f"rate limited, will retry: {exc}")
            time.sleep(2)
            return False
        drop_connection(str(exc))
        return False
    applied_track = payload["track"]
    applied_at = time.time()
    track = applied_track
    log(
        f"{track['title']} — {track['artist'] or '?'} "
        f"({'playing' if track['playing'] else 'paused'}, "
        f"{track['positionMs'] // 1000}s/{track['durationMs'] // 1000}s)"
    )
    return True


def presence_worker() -> None:
    global applied_track, applied_at
    while True:
        wake.wait(timeout=1.0)
        wake.clear()
        while True:
            data = _take_latest()
            if data is None:
                break

            # Let a burst of state changes settle, then use the newest one.
            time.sleep(COALESCE_SECONDS)
            newer = _take_latest()
            if newer is not None:
                data = newer

            if data.get("clear"):
                with ipc_lock:
                    if connected:
                        try:
                            presence.clear()
                        except Exception:
                            pass
                applied_track = None
                applied_at = 0.0
                log("clear")
                continue

            payload = build_args(data)
            if payload is None:
                with ipc_lock:
                    if connected:
                        try:
                            presence.clear()
                        except Exception:
                            pass
                applied_track = None
                applied_at = 0.0
                continue

            wait = _rate_wait()
            if wait > 0:
                # Keep the newest state, wait for a free slot, then apply it.
                time.sleep(min(wait, 5.0))
                newer = _take_latest()
                if newer is not None:
                    data = newer
                with latest_lock:
                    global latest
                    if latest is None:
                        latest = data
                continue

            tokens.append(time.time())
            if not apply_now(payload):
                # Retry the same state later unless something newer arrived.
                with latest_lock:
                    if latest is None:
                        latest = data
                time.sleep(2)
                wake.set()
                break


def reconnect_loop() -> None:
    while True:
        if not connected:
            if ensure_connected() and applied_track is not None:
                enqueue(dict(applied_track))
        time.sleep(RECONNECT_SECONDS)


def idle_loop() -> None:
    while True:
        time.sleep(5)
        if applied_track is not None and applied_at and time.time() - applied_at > IDLE_CLEAR_SECONDS:
            log("phone went quiet, clearing presence")
            enqueue({"clear": True})


# -------------------------------------------------------------------- server


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        if self.path != "/presence":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception:
            self.send_error(400)
            return
        enqueue(data)
        self.send_response(204)
        self.end_headers()

    def do_GET(self) -> None:
        if self.path.startswith("/health"):
            payload = {
                "connected": connected,
                "track": applied_track,
            }
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        body = b"ONYX bridge is running"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args) -> None:
        pass


class Server(ThreadingHTTPServer):
    # On Windows SO_REUSEADDR lets a second instance silently bind the same
    # port, which used to leave an old bridge answering requests.
    allow_reuse_address = False
    daemon_threads = True


def main() -> None:
    parser = argparse.ArgumentParser(description="ONYX Discord Rich Presence bridge")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()

    threading.Thread(target=presence_worker, daemon=True).start()
    threading.Thread(target=reconnect_loop, daemon=True).start()
    threading.Thread(target=idle_loop, daemon=True).start()

    try:
        server = Server((args.host, args.port), Handler)
    except OSError as exc:
        raise SystemExit(
            f"Port {args.port} is already in use — another ONYX bridge is running. ({exc})"
        )
    log(f"ONYX bridge listening on {args.host}:{args.port}")
    log("Phone and PC must be on the same Wi-Fi network.")
    log("Discord desktop must be running.")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("stopping")
    finally:
        server.server_close()
        with ipc_lock:
            try:
                presence.close()
            except Exception:
                pass


if __name__ == "__main__":
    main()
