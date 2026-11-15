package app.onyx.music

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AnalysisEntry(
    val id: String,
    val modified: Long,
    val features: TrackFeatures,
)

/**
 * Cached analysis results, keyed by track id and invalidated when the file's
 * last-modified stamp changes. One small JSON file next to the library cache.
 */
class AnalysisStore(context: Context) {

    private val file = File(context.filesDir, "track_analysis.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _state = MutableStateFlow<Map<String, AnalysisEntry>>(emptyMap())
    val state: StateFlow<Map<String, AnalysisEntry>> = _state.asStateFlow()

    init {
        scope.launch {
            val loaded = runCatching {
                if (file.exists()) {
                    json.decodeFromString<Map<String, AnalysisEntry>>(file.readText())
                } else {
                    emptyMap()
                }
            }.getOrDefault(emptyMap())
            _state.value = loaded
        }
    }

    fun features(track: LocalTrack): TrackFeatures? =
        _state.value[track.id]?.takeIf { it.modified == track.modified }?.features

    fun has(track: LocalTrack): Boolean = features(track) != null

    fun put(track: LocalTrack, features: TrackFeatures) {
        _state.value = _state.value + (track.id to AnalysisEntry(track.id, track.modified, features))
        persist()
    }

    fun clear() {
        _state.value = emptyMap()
        persist()
    }

    private fun persist() {
        val snapshot = _state.value
        scope.launch {
            runCatching {
                file.writeText(json.encodeToString(snapshot))
            }
        }
    }
}
