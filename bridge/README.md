# Discord PC bridge

Android does not expose a Discord RPC socket, so the phone sends the current
track to a small companion on your PC. The PC talks to the Discord desktop app
through the official IPC API (`pypresence`).

## Requirements

- Discord desktop app running on the PC.
- Python 3.10+.
- Phone and PC on the same Wi-Fi network.

## Run

```powershell
pip install pypresence
python bridge/onyx_bridge.py
```

The bridge listens on port `8787`.

In ONYX: **Настройки → Интеграции → Discord**:

- enable **ПК-мост ONYX**;
- enter the PC's LAN IP (e.g. `192.168.1.42`);
- port `8787`;
- play a track.

## What appears in Discord

- app name: **ONYX**;
- track title and artist;
- `onyx_large` artwork and `playing`/`pause` badge;
- button **ONYX на GitHub**.

Discord buttons can only open URLs. A direct “open this exact track” button is a
Spotify partnership feature; for third-party apps the button must point to a
website. That is why share/presence sends the GitHub link, not a track deep link.
If you want a track link, host a small page (e.g. `onyx.example/now`) that reads
the same bridge state and redirects to Spotify.

## Firewall

Allow inbound TCP 8787 on the PC (Windows Firewall prompt on first run).

## Security

The bridge accepts only POST `/presence` with JSON and keeps no history. Run it
only on a trusted home network; do not expose the port to the internet.
