package app.onyx.core

/**
 * Discord application used by the desktop Rich Presence bridge (ONYX 1.0).
 * The Android build can only share now playing and rely on Discord's own
 * Spotify presence; local Discord RPC is not available on Android.
 */
object DiscordConfig {
    const val APPLICATION_ID = "1568847197397717073"
    const val LARGE_IMAGE = "onyx_large"
    const val SMALL_IMAGE = "onyx_small"
    const val PLAY_IMAGE = "play"
    const val PAUSE_IMAGE = "pause"
    const val PLAYING_IMAGE = "playing"
    const val GITHUB_URL = "https://github.com/ysl021/onyx"
}
