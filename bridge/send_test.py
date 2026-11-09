"""Sends a fake now-playing payload to a running ONYX bridge.

Handy for checking Discord Rich Presence without the phone.

    python bridge/send_test.py
    python bridge/send_test.py --title "Track" --artist "Artist" --position 30 --duration 200
    python bridge/send_test.py --paused
    python bridge/send_test.py --clear
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.request


def post(host: str, port: int, payload: dict) -> int:
    request = urllib.request.Request(
        f"http://{host}:{port}/presence",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status


def main() -> None:
    parser = argparse.ArgumentParser(description="Fake now-playing sender for the ONYX bridge")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--title", default="Shiny Ruby")
    parser.add_argument("--artist", default="honeyrax")
    parser.add_argument("--album", default="ONYX test")
    parser.add_argument("--duration", type=float, default=215, help="track length in seconds")
    parser.add_argument("--position", type=float, default=42, help="current position in seconds")
    parser.add_argument("--paused", action="store_true")
    parser.add_argument("--clear", action="store_true")
    parser.add_argument("--seconds", type=float, default=0, help="keep sending for N seconds")
    args = parser.parse_args()

    if args.clear:
        print("HTTP", post(args.host, args.port, {"clear": True}))
        return

    started = time.time()
    while True:
        elapsed = time.time() - started
        position = args.position + (0 if args.paused else elapsed)
        payload = {
            "title": args.title,
            "artist": args.artist,
            "album": args.album,
            "playing": not args.paused,
            "durationMs": int(args.duration * 1000),
            "positionMs": int(position * 1000),
            "source": "app.onyx.test",
            "url": "https://github.com/ysl021/onyx",
        }
        print("HTTP", post(args.host, args.port, payload))
        if not args.seconds or elapsed >= args.seconds:
            break
        time.sleep(10)


if __name__ == "__main__":
    main()
