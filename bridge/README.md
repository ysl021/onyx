# Discord PC bridge

Android does not expose a Discord RPC socket, so the phone sends the current
track to a small companion on your PC. The PC talks to the Discord desktop app
through the official IPC API (`pypresence`).

## Requirements

- Discord desktop app running on the PC.
- Python 3.10+.
- Phone and PC on the same Wi-Fi network.

## Run

Double-click `bridge/run.bat` — it installs `pypresence` on the first run and
keeps the window open. Or manually:

```powershell
pip install pypresence
python bridge/onyx_bridge.py
```

The bridge listens on `0.0.0.0:8787`, reconnects on its own if Discord is
restarted, and clears the presence 90 seconds after the phone goes quiet.

`GET /health` returns the current state as JSON:

```json
{"connected": true, "track": {"title": "...", "artist": "...", "positionMs": 42000}}
```

## Connect the phone

In ONYX: **Settings → Discord**:

- enable **ONYX PC bridge**;
- enter the PC's LAN IP (`ipconfig` → IPv4, e.g. `192.168.1.42`);
- port `8787`;
- press **Test connection** — it should say the bridge is online.

## What appears in Discord

- app name **ONYX**, header **Listening to ONYX**;
- track title, `artist • album`;
- cover art for Spotify tracks (via oEmbed), otherwise the `onyx_large` asset;
- **progress bar** with elapsed/remaining time (built from `start` + `end`);
- `playing`/`pause` badge;
- one button: **Открыть в Spotify** or **ONYX — GitHub**.

The progress bar needs both timestamps. The app sends `positionMs` together
with `durationMs`; the bridge turns them into `start = now − position` and
`end = start + duration`. On pause the timestamps are dropped so the bar does
not keep moving.

## Testing without the phone

```powershell
python bridge/send_test.py
python bridge/send_test.py --paused
python bridge/send_test.py --clear
```

## Firewall

Allow inbound TCP 8787 on the PC (Windows Firewall prompt on first run) for
private networks only.

## Security

The bridge accepts only `POST /presence` and `GET /health`, stores no history,
and never talks to the internet except Spotify's public oEmbed endpoint. Run it
only on a trusted home network; do not expose the port to the internet.

## Notes

Discord buttons only accept URLs, so a track button points at the Spotify track
page or the GitHub repo. Discord renders one activity at a time: if Spotify is
connected in Discord settings, its presence may replace ONYX's.
