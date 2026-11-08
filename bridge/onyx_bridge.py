"""
ONYX Discord PC bridge.

Receives now-playing data from the Android app over LAN and sets Discord
Rich Presence through the official Discord IPC (pypresence).

Install:
    pip install pypresence

Run:
    python bridge/onyx_bridge.py

Then in ONYX: Settings -> Integrations -> Discord -> enable PC bridge and enter
this computer's LAN IP (for example 192.168.1.42) and port 8787.
"""

from __future__ import annotations

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from pypresence import Presence
except ImportError:
    raise SystemExit("Install pypresence first: pip install pypresence")

APP_ID = "1568847197397717073"
GITHUB = "https://github.com/ysl021/onyx"
PORT = 8787
IDLE_CLEAR_SECONDS = 90

presence = Presence(APP_ID)
connected = False
last_update = 0.0
last_state = None


def ensure_connected() -> bool:
    global connected
    if connected:
        return True
    try:
        presence.connect()
        connected = True
        print("Connected to Discord IPC")
        return True
    except Exception as exc:  # Discord is not running
        print(f"Discord IPC unavailable: {exc}")
        connected = False
        return False


def update(data: dict) -> None:
    global last_update, last_state
    last_update = time.time()
    last_state = data
    if not ensure_connected():
        return

    title = (data.get("title") or "").strip()
    artist = (data.get("artist") or "").strip()
    playing = bool(data.get("playing"))
    duration = int(data.get("durationMs") or 0)

    link = (data.get("url") or GITHUB).strip()
    label = "Открыть трек" if link.startswith("https://open.spotify.com/") else "ONYX на GitHub"

    args = dict(
        details=title[:128] or "ONYX",
        state=(artist[:128] if artist else "music control center"),
        large_image="onyx_large",
        large_text="ONYX",
        small_image="playing" if playing else "pause",
        small_text="ONYX",
        buttons=[{"label": label, "url": link}],
    )
    if playing and duration > 0:
        args["start"] = int(time.time())
    try:
        presence.update(**args)
        print(f"Presence: {title} — {artist} ({'playing' if playing else 'paused'})")
    except Exception as exc:
        print(f"Update failed: {exc}")
        global connected
        connected = False


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
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
        update(data)
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ONYX bridge is running")

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"ONYX bridge listening on 0.0.0.0:{PORT}")
    print("Phone and PC must be on the same Wi-Fi network.")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)

    def idle_loop():
        while True:
            time.sleep(5)
            if connected and last_update and time.time() - last_update > IDLE_CLEAR_SECONDS:
                try:
                    presence.clear()
                except Exception:
                    pass

    import threading

    threading.Thread(target=idle_loop, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            presence.close()
        except Exception:
            pass
