package app.onyx

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import app.onyx.buds.BudsService
import app.onyx.audio.SoundEffects
import app.onyx.audio.VolumeController
import app.onyx.core.Prefs
import app.onyx.core.YouTubeDownloadEntry
import app.onyx.imports.PlaylistImporter
import app.onyx.lyrics.LyricsRepository
import app.onyx.media.MediaRepository
import app.onyx.media.NowPlaying
import app.onyx.media.OutputRouter
import app.onyx.media.HeadphonesRepository
import app.onyx.music.LocalLibrary
import app.onyx.music.LocalPlaybackController
import app.onyx.music.LocalPlaylists
import app.onyx.music.LocalTrack
import app.onyx.spotify.SpotifyClient
import app.onyx.spotify.QueueData
import app.onyx.spotify.DiscordBridge
import app.onyx.ui.tr
import app.onyx.update.UpdateManager
import app.onyx.update.UpdateState
import app.onyx.youtube.DownloadService
import app.onyx.youtube.YtSection
import app.onyx.youtube.YtVideo
import app.onyx.youtube.YouTubeDownloads
import app.onyx.youtube.YouTubeRadio
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppController private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val prefs = Prefs(appContext)
    val media = MediaRepository.get(appContext)
    val router = OutputRouter(appContext)
    val headphones = HeadphonesRepository(appContext)
    val spotify = SpotifyClient(appContext, prefs)
    val sound = SoundEffects(appContext, prefs)
    val volume = VolumeController(appContext)
    val localLibrary = LocalLibrary(appContext, prefs)
    val localPlayer = LocalPlaybackController(appContext, localLibrary)
    val localPlaylists = LocalPlaylists(appContext)
    val lyrics = LyricsRepository(appContext, prefs) { spotify.token() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Long-lived scope for work initiated from the UI that must survive leaving
     * the screen (e.g. TagWriter rewriting a file that is currently playing).
     */
    val appScope: CoroutineScope = scope

    /** YouTube streaming with automatic recommendations. */
    val youtubeRadio = YouTubeRadio(appScope, appContext, localPlayer)
    private var watcher: Job? = null

    /** Local playback wins over notification sessions so artwork and lyrics never go stale. */
    val nowPlaying = combine(media.state, localPlayer.state) { mediaState, localState ->
        val track = localState.current
        // Buffering reports isPlaying=false. Using playWhenReady as well keeps
        // the player from flashing the previous app's track (or an empty card)
        // for a second every time a new song is started. While the local track
        // is current but paused, keep it on screen unless another app actually
        // plays something; otherwise the card bounces between sources.
        val localActive = track != null && (
            localState.isPlaying || localState.playWhenReady || !mediaState.now.isPlaying
        )
        if (localActive) {
            track!!.toNowPlaying(playing = localState.isPlaying || localState.playWhenReady)
        } else {
            mediaState.now
        }
    }.stateIn(scope, SharingStarted.Eagerly, media.state.value.now)

    /**
     * Live playback position for the Discord bridge. Kept apart from [nowPlaying]
     * so the UI does not recompose three times a second.
     */
    val playbackPosition = combine(media.state, localPlayer.state) { mediaState, localState ->
        val localActive = localState.current != null && (
            localState.isPlaying || localState.playWhenReady || !mediaState.now.isPlaying
        )
        if (localActive) localState.positionMs else mediaState.positionMs
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    val queue = MutableStateFlow<QueueData?>(null)
    private var queueFetchedAt = 0L
    val discordBridge = DiscordBridge(prefs, nowPlaying, playbackPosition)
    val syncClient = app.onyx.sync.SyncClient(appContext, prefs, localPlaylists, localLibrary)
    val updates = UpdateManager(appContext)

    // Sleep timer: counts down and pauses playback when it hits zero.
    private val _sleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs.asStateFlow()
    private var sleepJob: Job? = null

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        _sleepRemainingMs.value = minutes * 60_000L
        sleepJob = scope.launch {
            while (_sleepRemainingMs.value > 0L && isActive) {
                delay(1000)
                _sleepRemainingMs.value = (_sleepRemainingMs.value - 1000).coerceAtLeast(0L)
            }
            if (_sleepRemainingMs.value <= 0L) pauseAllForSleep()
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = 0L
    }

    private fun pauseAllForSleep() {
        localPlayer.pause()
        media.pause()
    }

    /**
     * Downloads the chosen chapters, then rescans the library and files them
     * into a playlist named after the video.
     */
    fun startYouTubeImport(
        video: YtVideo,
        sections: List<YtSection>,
        folderUri: String,
        album: String,
    ) {
        if (sections.isEmpty()) return
        if (folderUri.isBlank()) {
            toast(tr("Сначала выбери папку для сохранения", "Pick a folder to save into first"))
            return
        }
        // The queue already has a worker: it will pick this batch up itself, and
        // starting the service again would stop it once the second start returns.
        val serviceRunning = YouTubeDownloads.isProcessing()
        YouTubeDownloads.enqueue(video, sections, folderUri, album) { saved ->
            localLibrary.rescan()
            if (saved.isNotEmpty()) {
                localPlaylists.create(album.take(80), trackIds = saved.map { it.id }) {
                    toast(
                        tr(
                            "Готово: ${saved.size} треков в «${album.take(40)}»",
                            "Done: ${saved.size} tracks in «${album.take(40)}»",
                        ),
                    )
                }
                scope.launch {
                    prefs.addYouTubeDownloads(
                        saved.map {
                            YouTubeDownloadEntry(it.id, it.uri, it.title, it.artist, it.name, it.savedAt)
                        },
                    )
                }
            } else {
                toast(tr("Не удалось сохранить треки", "Could not save the tracks"))
            }
            scope.launch {
                delay(600)
                localLibrary.rescan()
            }
        }
        startQueueIfNeeded(serviceRunning)
    }

    /** Saves the whole mix as one file, no chapter splitting. */
    fun startYouTubeWhole(video: YtVideo, folderUri: String) {
        if (folderUri.isBlank()) {
            toast(tr("Сначала выбери папку для сохранения", "Pick a folder to save into first"))
            return
        }
        val serviceRunning = YouTubeDownloads.isProcessing()
        YouTubeDownloads.enqueueWhole(video, folderUri) { saved ->
            localLibrary.rescan()
            if (saved.isNotEmpty()) {
                toast(tr("Скачано: ${video.title.take(48)}", "Saved: ${video.title.take(48)}"))
                scope.launch {
                    prefs.addYouTubeDownloads(
                        saved.map {
                            YouTubeDownloadEntry(it.id, it.uri, it.title, it.artist, it.name, it.savedAt)
                        },
                    )
                }
            } else {
                toast(tr("Не удалось сохранить файл", "Could not save the file"))
            }
            scope.launch {
                delay(600)
                localLibrary.rescan()
            }
        }
        startQueueIfNeeded(serviceRunning)
    }

    /** Downloads a whole playlist and replaces each `yt:` entry with the saved file. */
    fun startPlaylistDownload(
        videos: List<YtVideo>,
        folderUri: String,
        playlistId: String,
        playlistName: String,
    ) {
        if (videos.isEmpty()) return
        if (folderUri.isBlank()) {
            toast(tr("Сначала выбери папку для сохранения", "Pick a folder to save into first"))
            return
        }
        val serviceRunning = YouTubeDownloads.isProcessing()
        YouTubeDownloads.enqueueWholeBatch(
            videos = videos,
            folderUri = folderUri,
            album = playlistName,
            onSaved = { saved ->
                if (saved.videoId.isNotBlank()) {
                    localPlaylists.replaceStreamWithLocal(
                        playlistId,
                        saved.videoId,
                        saved.id,
                    )
                }
            },
            onAllFinished = { saved ->
                localLibrary.rescan()
                if (saved.isNotEmpty()) {
                    toast(
                        tr(
                            "Скачано: ${saved.size}",
                            "Saved: ${saved.size}",
                        ),
                    )
                    scope.launch {
                        prefs.addYouTubeDownloads(
                            saved.map {
                                YouTubeDownloadEntry(
                                    it.id,
                                    it.uri,
                                    it.title,
                                    it.artist,
                                    it.name,
                                    it.savedAt,
                                )
                            },
                        )
                    }
                } else {
                    toast(tr("Не удалось скачать треки", "Could not download the tracks"))
                }
            },
        )
        startQueueIfNeeded(serviceRunning)
    }

    /** Imports a playlist from a public link or pasted M3U/CSV text. */
    fun importPlaylist(url: String) {
        toast(tr("Импортирую плейлист…", "Importing playlist…"))
        scope.launch(Dispatchers.IO) {
            runCatching {
                val imported = PlaylistImporter.load(url, spotify)
                val matched = PlaylistImporter.match(imported, localLibrary)
                localPlaylists.create(
                    name = imported.name,
                    description = imported.description,
                    trackIds = matched.localIds,
                ) { id ->
                    if (matched.streams.isNotEmpty()) {
                        localPlaylists.addStreamTracks(id, matched.streams)
                    }
                    val total = matched.localIds.size + matched.streams.size
                    toast(
                        tr(
                            "Импортировано: $total, не найдено: ${matched.unresolved}",
                            "Imported: $total, not found: ${matched.unresolved}",
                        ),
                    )
                }
            }.onFailure {
                toast(it.message ?: tr("Не удалось импортировать", "Import failed"))
            }
        }
    }

    private fun startQueueIfNeeded(serviceRunning: Boolean) {
        if (serviceRunning) return
        val started = DownloadService.start(appContext)
        if (!started) {
            // Foreground service refused (rare, e.g. background restriction):
            // finish the queue inside the app process instead of losing it.
            scope.launch(Dispatchers.IO) {
                YouTubeDownloads.processQueue(appContext)
            }
        } else {
            // Watchdog: if the service died before starting a worker, do the
            // work in-process so the queue can never hang in "Downloading".
            scope.launch {
                delay(2_500)
                if (YouTubeDownloads.state.value.busy && !YouTubeDownloads.isProcessing()) {
                    withContext(Dispatchers.IO) {
                        YouTubeDownloads.processQueue(appContext)
                    }
                }
            }
        }
    }

    @Volatile
    var lastTab: String = "Player"

    /** In-memory mirror so the first-run flow never flashes twice in one process. */
    @Volatile
    var onboardingDone: Boolean? = null

    fun refreshQueue(force: Boolean = false) {
        if (!force && SystemClock.elapsedRealtime() - queueFetchedAt < 8_000L) return
        scope.launch {
            val data = runCatching { spotify.queue() }.getOrNull() ?: return@launch
            queue.value = data
            queueFetchedAt = SystemClock.elapsedRealtime()
        }
    }

    fun clearQueue() {
        queue.value = null
        queueFetchedAt = 0L
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun start() {
        media.start()
        headphones.refresh()
        sound.init()
        localPlayer.connect()
        scope.launch {
            // The player session can be created before or after DataStore is
            // read; LocalPlaybackController buffers the mode until it connects.
            localPlayer.setRepeatMode(prefs.snapshot().repeatMode)
        }
        startBudsIfAllowed()
        scope.launch {
            val snapshot = prefs.snapshot()
            if (snapshot.autoUpdateCheck && !BuildConfig.TRANSITIONAL) {
                when (val result = updates.check()) {
                    is UpdateState.Available -> toast(
                        tr(
                            "Доступно обновление ${result.info.versionName}",
                            "Update ${result.info.versionName} is available",
                        ),
                    )
                    else -> Unit
                }
                prefs.setLastUpdateCheck(System.currentTimeMillis())
            }
        }
        if (watcher != null) return
        watcher = scope.launch {
            nowPlaying
                .distinctUntilChangedBy {
                    listOf(
                        it.packageName,
                        it.title,
                        it.artist,
                        it.durationMs,
                        it.mediaId,
                    )
                }
                .collect { lyrics.sync(it) }
        }
        scope.launch {
            localPlayer.state
                .map { it.current?.id to it.isPlaying }
                .distinctUntilChangedBy { it }
                .collect { media.refresh() }
        }
    }

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    /** Applies a repeat mode now and remembers it for the next launch. */
    fun setRepeatMode(mode: Int) {
        localPlayer.setRepeatMode(mode)
        scope.launch { prefs.setRepeatMode(mode) }
    }

    /**
     * Never reconnect in the background just because the app is open. Start the
     * SPP service only when the Buds are actually connected as an audio device;
     * otherwise the user taps "Подключить" explicitly.
     */
    fun startBudsIfAllowed() {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted && budsAudioConnected()) {
            BudsService.ensure(appContext)
        }
    }

    private fun budsAudioConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val am = appContext.getSystemService(AudioManager::class.java) ?: return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                val isBt = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                isBt && d.productName?.toString()?.contains("buds", ignoreCase = true) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        watcher?.cancel()
        watcher = null
        cancelSleepTimer()
        lyrics.close()
        sound.close()
        volume.close()
        discordBridge.close()
        syncClient.close()
        localPlayer.close()
        localPlaylists.close()
        localLibrary.close()
        media.close()
    }

    companion object {
        @Volatile
        private var inst: AppController? = null

        fun get(context: Context): AppController =
            inst ?: synchronized(this) {
                inst ?: AppController(context).also { inst = it }
            }
    }
}

private fun LocalTrack.toNowPlaying(playing: Boolean = true): NowPlaying = NowPlaying(
    packageName = "app.onyx.local",
    title = title,
    artist = artist,
    album = album,
    artUri = artPath?.let {
        if (it.startsWith("http://", true) || it.startsWith("https://", true)) {
            it
        } else {
            android.net.Uri.fromFile(java.io.File(it)).toString()
        }
    },
    mediaId = id,
    durationMs = durationMs,
    isPlaying = playing,
    canSeek = true,
    canSkip = true,
)
