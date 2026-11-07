package app.onyx.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MediaRepository private constructor(private val appContext: Context) {

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var started = false
    private val handler = Handler(Looper.getMainLooper())

    private val component get() = ComponentName(appContext, OnyxNotificationListener::class.java)

    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val pos = currentPosition()
            if (pos != null) {
                _state.update { it.copy(positionMs = pos) }
            }
            handler.postDelayed(this, 300)
        }
    }

    fun start() {
        if (started) return
        started = true
        refresh()
        handler.post(ticker)
    }

    fun refresh() {
        val manager = appContext.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            val sessions = manager.getActiveSessions(component)
            _state.update { it.copy(hasAccess = true) }
            bind(sessions)
        } catch (_: SecurityException) {
            _state.update { it.copy(hasAccess = false) }
        } catch (_: Exception) {
            _state.update { it.copy(hasAccess = false) }
        }
    }

    fun onListenerConnected() {
        refresh()
        try {
            val manager = appContext.getSystemService(MediaSessionManager::class.java) ?: return
            manager.removeOnActiveSessionsChangedListener(activeListener)
            manager.addOnActiveSessionsChangedListener(activeListener, component)
        } catch (_: Exception) {
        }
    }

    fun onListenerDisconnected() {
        _state.update { it.copy(hasAccess = false) }
    }

    private fun bind(list: List<MediaController>?) {
        val sessions = list.orEmpty()
        if (sessions.isNotEmpty()) _state.update { it.copy(hasAccess = true) }
        val own = sessions.firstOrNull {
            it.packageName == appContext.packageName &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val otherPlaying = sessions.firstOrNull {
            it.packageName != appContext.packageName &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val candidate = own
            ?: otherPlaying
            ?: sessions.firstOrNull { it.packageName == SPOTIFY }
            ?: sessions.firstOrNull()
        if (candidate?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = candidate
        try {
            candidate?.registerCallback(controllerCallback, handler)
        } catch (_: Exception) {
        }
        publish()
    }

    private fun publish() {
        val c = controller ?: run {
            _state.update { it.copy(now = NowPlaying()) }
            return
        }
        val md = c.metadata
        val pb = c.playbackState
        val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val actions = pb?.actions ?: 0
        val now = NowPlaying(
            packageName = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.description?.title?.toString().orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.description?.subtitle?.toString().orEmpty(),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            artUri = artUri,
            art = art,
            mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            isPlaying = pb?.state == PlaybackState.STATE_PLAYING,
            canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            canSkip = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L ||
                (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
        )
        val pos = positionOf(pb) ?: _state.value.positionMs
        _state.update { it.copy(now = now, positionMs = pos) }
    }

    private fun positionOf(pb: PlaybackState?): Long? {
        if (pb == null) return null
        var p = pb.position
        if (pb.state == PlaybackState.STATE_PLAYING) {
            val delta = SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
            if (delta in 0..60_000) p += delta
        }
        val dur = _state.value.now.durationMs
        if (dur > 0) p = p.coerceIn(0, dur)
        return p.coerceAtLeast(0)
    }

    private fun currentPosition(): Long? = positionOf(controller?.playbackState)

    fun playPause() {
        val c = controller ?: return
        try {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        } catch (_: Exception) {
        }
        handler.postDelayed({ publish() }, 180)
    }

    fun next() {
        try {
            controller?.transportControls?.skipToNext()
        } catch (_: Exception) {
        }
        handler.postDelayed({ publish() }, 220)
    }

    fun previous() {
        try {
            controller?.transportControls?.skipToPrevious()
        } catch (_: Exception) {
        }
        handler.postDelayed({ publish() }, 220)
    }

    fun seekTo(ms: Long) {
        try {
            controller?.transportControls?.seekTo(ms)
        } catch (_: Exception) {
        }
        _state.update { it.copy(positionMs = ms) }
    }

    fun close() {
        handler.removeCallbacks(ticker)
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    companion object {
        private const val SPOTIFY = "com.spotify.music"

        @Volatile
        private var inst: MediaRepository? = null

        fun get(context: Context): MediaRepository =
            inst ?: synchronized(this) {
                inst ?: MediaRepository(context.applicationContext).also { inst = it }
            }
    }
}
