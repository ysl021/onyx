package app.onyx.music

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.onyx.MainActivity
import app.onyx.youtube.YouTubeStreamCache
import java.io.IOException

class LocalPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // YouTube streams are plain HTTPS URLs but googlevideo checks the UA
        // and referer. DefaultDataSource keeps content:// and file:// URIs on
        // the local file/content sources; passing the HTTP factory directly
        // made every local track fail to load.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.youtube.com/"))
        val localSource = DefaultDataSource.Factory(this, http)
        // `yt://<videoId>` entries are resolved to a direct googlevideo URL
        // when ExoPlayer opens them. This keeps the whole queue instant: the
        // expensive extraction happens only for the track being played.
        val dataSource = YouTubeDataSourceFactory(localSource)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        player = exo
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(pi)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

/**
 * Resolves `yt://<id>` on open and retries once with a fresh URL when
 * googlevideo answers 403. Cached stream URLs are bound to the network they
 * were extracted on, so switching Wi-Fi/mobile data (or YouTube invalidating
 * an old client) used to kill playback until the cache expired.
 */
private class YouTubeDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = YouTubeDataSource(upstream)
}

private class YouTubeDataSource(
    private val factory: DataSource.Factory,
) : DataSource {

    private companion object {
        /** videoId -> last time both extractor URLs were rejected by this network. */
        val recentFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    private fun fresh(): DataSource =
        factory.createDataSource().also { source ->
            listeners.forEach(source::addTransferListener)
            delegate = source
        }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "yt") return fresh().open(dataSpec)
        val id = dataSpec.uri.host ?: dataSpec.uri.lastPathSegment.orEmpty()
        val first = YouTubeStreamCache.resolveBlocking(id)
            ?: throw IOException("Не удалось получить поток YouTube")
        return try {
            fresh().open(dataSpec.withUri(Uri.parse(first)))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403 && e.responseCode != 410) throw e
            runCatching { delegate?.close() }
            YouTubeStreamCache.invalidate(id)
            // Both extractors can return URLs that this network rejects (ISP
            // block, missing poToken). Re-extracting on every ExoPlayer retry
            // hammers the extractors and keeps the UI spinning, so allow one
            // fallback attempt per video per minute and surface the error.
            val lastFailure = recentFailures[id] ?: 0L
            if (System.currentTimeMillis() - lastFailure < 60_000L) throw e
            println("[yt] stream ${e.responseCode} for $id, re-resolving via NewPipe")
            val second = YouTubeStreamCache.resolveBlocking(id, preferNewPipe = true) ?: throw e
            try {
                fresh().open(dataSpec.withUri(Uri.parse(second)))
            } catch (secondError: HttpDataSource.InvalidResponseCodeException) {
                if (secondError.responseCode == 403 || secondError.responseCode == 410) {
                    recentFailures[id] = System.currentTimeMillis()
                }
                throw secondError
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders.orEmpty()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
