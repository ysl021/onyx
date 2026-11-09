# Discord Rich Presence assets

Application ID: `1568847197397717073`

Upload these files in **Discord Developer Portal → your app → Rich Presence → Art Assets**:

| File | Asset key | Use |
|---|---|---|
| `onyx_large.png` | `onyx_large` | large image next to the track |
| `onyx_small.png` | `onyx_small` | small overlay icon |
| `playing.png` | `playing` | small icon while playing |
| `play.png` | `play` | play state |
| `pause.png` | `pause` | pause state |

All assets are 512×512 (Discord's minimum for Art Assets) with transparency where
needed. The small icons keep ~20% padding so Discord's circular crop does not
clip the glyph.

## Presence payload (desktop ONYX 1.0)

```text
details       = <track title>
state         = <artist>
large_image   = onyx_large
large_text    = ONYX
small_image   = playing / pause
small_text    = ONYX
buttons       = [ { label: "ONYX на GitHub", url: "https://github.com/ysl021/onyx" } ]
```

Discord buttons only accept URLs. A direct "open this exact track" button is a
Spotify partnership feature, not available for third-party apps; use the GitHub
button or a personal landing page.

## Android

Discord does not expose a local RPC socket on Android. ONYX on Android can:

- share the current track through the Android share sheet;
- rely on Discord's own "Listening to Spotify" when Spotify is linked in Discord.

Full custom presence on Android requires access to the Discord Social SDK. Without
it, the legal route is the desktop companion (ONYX 1.0) which receives the phone's
now-playing state and sets RPC from the PC.
