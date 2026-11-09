package app.onyx.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.onyx.ui.tr
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class LocalPlaybackState(
    val connected: Boolean = false,
    val current: LocalTrack? = null,
    val queue: List<LocalTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
)

class LocalPlaybackController(
    private val context: Context,
    private val library: LocalLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null
    private var connecting = false
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            controller?.let { publish(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            controller?.let { publish(it) }
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { publish(it) }
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(
            context,
            ComponentName(context, LocalPlaybackService::class.java),
        )
        val future = try {
            MediaController.Builder(context, token).buildAsync()
        } catch (t: Throwable) {
            connecting = false
            _state.value = _state.value.copy(
                connected = false,
                error = tr("Локальный плеер недоступен", "Local player is unavailable") + ": ${t.message ?: t.javaClass.simpleName}",
            )
            return
        }
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                connecting = false
                publish(c)
                pending?.invoke()
                pending = null
            } catch (t: Throwable) {
                connecting = false
                _state.value = _state.value.copy(
                    connected = false,
                    error = tr("Не удалось подключить локальный плеер", "Could not connect the local player") + ": ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }, MoreExecutors.directExecutor())
    }

    fun play(track: LocalTrack, queue: List<LocalTrack>) {
        val action = action@{
            val c = controller ?: return@action
            val items = queue.map { it.toMediaItem() }
            val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            c.setMediaItems(items, index, 0L)
            c.prepare()
            c.play()
        }
        if (controller == null) {
            pending = action
            connect()
        } else {
            action()
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Pause without toggling — used by the sleep timer. */
    fun pause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from < 0 || to < 0) return
        if (from >= c.mediaItemCount || to >= c.mediaItemCount) return
        runCatching {
            c.moveMediaItem(from, to)
            publish(c)
        }
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        publish(c)
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
    }

    private fun publish(c: MediaController) {
        val item = c.currentMediaItem
        val track = item?.let { media ->
            library.state.value.tracks.firstOrNull { it.id == media.mediaId }
                ?: LocalTrack(
                    id = media.mediaId,
                    uri = media.localConfiguration?.uri?.toString().orEmpty(),
                    title = media.mediaMetadata.title?.toString().orEmpty(),
                    artist = media.mediaMetadata.artist?.toString().orEmpty(),
                    album = media.mediaMetadata.albumTitle?.toString().orEmpty(),
                )
        }
        _state.value = LocalPlaybackState(
            connected = true,
            current = track,
            queue = (0 until c.mediaItemCount).mapNotNull { index ->
                val media = c.getMediaItemAt(index)
                library.state.value.tracks.firstOrNull { it.id == media.mediaId }
                    ?: LocalTrack(
                        id = media.mediaId,
                        uri = media.localConfiguration?.uri?.toString().orEmpty(),
                        title = media.mediaMetadata.title?.toString().orEmpty(),
                        artist = media.mediaMetadata.artist?.toString().orEmpty(),
                        album = media.mediaMetadata.albumTitle?.toString().orEmpty(),
                    )
            },
            currentIndex = if (c.mediaItemCount == 0) -1 else c.currentMediaItemIndex,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            error = null,
        )
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0L),
                        durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    )
                }
                delay(350)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun close() {
        stopTicker()
        try {
            controller?.removeListener(listener)
            controller?.release()
        } catch (_: Throwable) {
        }
        controller = null
        scope.cancel()
    }
}

private fun LocalTrack.toMediaItem(): MediaItem {
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .apply {
            if (!artPath.isNullOrBlank()) {
                val f = File(artPath)
                if (f.exists()) setArtworkUri(Uri.fromFile(f))
            }
        }
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse(uri))
        .setMediaMetadata(meta)
        .build()
}
