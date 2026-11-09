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

The Android app sends the playback position too, so Discord gets both
`start` and `end` timestamps and draws the real progress bar.
"""

from __future__ import annotations

import argparse
import json
import threading
import time
import urllib.parse
import urllib.request
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
MIN_UPDATE_SECONDS = 4.0

presence = Presence(APP_ID)
ipc_lock = threading.Lock()
state_lock = threading.Lock()

connected = False
last_update = 0.0
last_signature: tuple | None = None
last_track: dict | None = None
art_cache: dict[str, str | None] = {}


def log(message: str) -> None:
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


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


def spotify_cover(url: str) -> str | None:
    """Spotify's public oEmbed endpoint gives a ready https cover URL."""
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


def clear_presence() -> None:
    global connected, last_signature, last_update, last_track
    with ipc_lock:
        if connected:
            try:
                presence.clear()
            except Exception as exc:
                connected = False
                log(f"clear failed: {exc}")
    with state_lock:
        last_signature = None
        last_update = 0.0
        last_track = None


def apply_presence(data: dict) -> None:
    global last_signature, last_update, last_track

    if data.get("clear"):
        log("clear")
        clear_presence()
        return

    title = (data.get("title") or "").strip()
    artist = (data.get("artist") or "").strip()
    album = (data.get("album") or "").strip()
    playing = bool(data.get("playing"))
    duration = max(0, int(data.get("durationMs") or 0))
    position = max(0, int(data.get("positionMs") or 0))
    if duration:
        position = min(position, duration)
    link = (data.get("url") or GITHUB).strip()

    if not title:
        clear_presence()
        return

    signature = (title, artist, album, playing, duration)
    now = time.time()
    with state_lock:
        if signature == last_signature and now - last_update < MIN_UPDATE_SECONDS:
            # Same track: Discord keeps its own timer, no need to talk to it again.
            return
        last_signature = signature
        last_update = now
        last_track = {
            "title": title,
            "artist": artist,
            "album": album,
            "playing": playing,
            "positionMs": position,
            "durationMs": duration,
            "url": link,
        }

    if not ensure_connected():
        return

    state_line = " • ".join(part for part in (artist, album) if part)[:128]
    cover = spotify_cover(link) if "open.spotify.com" in link else None
    is_spotify = "open.spotify.com" in link

    args = dict(
        activity_type=ActivityType.LISTENING,
        details=title[:128],
        state=state_line or "ONYX",
        large_image=cover or "onyx_large",
        large_text=(album or "ONYX")[:128],
        small_image="playing" if playing else "pause",
        small_text="ONYX",
        buttons=[{
            "label": "Открыть в Spotify" if is_spotify else "ONYX — GitHub",
            "url": link,
        }],
    )

    # Both timestamps are what make Discord draw the progress bar.
    if playing:
        start = int(now - position / 1000)
        args["start"] = start
        if duration > 0:
            args["end"] = int(start + duration / 1000)

    with ipc_lock:
        if not connected:
            return
        try:
            presence.update(**args)
            log(
                f"{title} — {artist or '?'} "
                f"({'playing' if playing else 'paused'}, "
                f"{position // 1000}s/{duration // 1000}s)"
            )
        except Exception as exc:
            drop_connection(str(exc))


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
        threading.Thread(target=apply_presence, args=(data,), daemon=True).start()
        self.send_response(204)
        self.end_headers()

    def do_GET(self) -> None:
        if self.path.startswith("/health"):
            with state_lock:
                payload = {
                    "connected": connected,
                    "track": last_track,
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


def reconnect_loop() -> None:
    while True:
        if not connected:
            if ensure_connected():
                with state_lock:
                    track = last_track
                    last_signature = None
                if track:
                    apply_presence(track)
        time.sleep(RECONNECT_SECONDS)


def idle_loop() -> None:
    while True:
        time.sleep(5)
        with state_lock:
            stale = last_update and time.time() - last_update > IDLE_CLEAR_SECONDS
        if stale:
            log("phone went quiet, clearing presence")
            clear_presence()


def main() -> None:
    parser = argparse.ArgumentParser(description="ONYX Discord Rich Presence bridge")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()

    threading.Thread(target=reconnect_loop, daemon=True).start()
    threading.Thread(target=idle_loop, daemon=True).start()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
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
