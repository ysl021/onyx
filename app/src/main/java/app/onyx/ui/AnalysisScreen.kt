package app.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.TrackAnalyzer
import app.onyx.music.TrackFeatures
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Offline analysis of the local library: BPM, key/Camelot and loudness. Results
 * are cached per file and invalidated when the file changes; nothing is sent
 * anywhere and streams are skipped.
 */
@Composable
fun AnalysisScreen(
    controller: AppController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val cache by controller.analysis.state.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    var processed by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    val tracks = remember(library.tracks) {
        library.tracks
            .filter { it.uri.startsWith("content://") && it.durationMs > 30_000 }
            .sortedWith(compareBy({ cache[it.id]?.modified != it.modified }, { it.title.lowercase() }))
    }
    val analyzedCount = tracks.count { track ->
        cache[track.id]?.takeIf { it.modified == track.modified } != null
    }

    fun featuresOf(trackId: String, modified: Long): TrackFeatures? =
        cache[trackId]?.takeIf { it.modified == modified }?.features

    fun startAll() {
        if (job?.isActive == true) return
        processed = 0
        failed = 0
        running = true
        job = scope.launch {
            val pending = tracks.filter { track -> featuresOf(track.id, track.modified) == null }
            pending.forEach { track ->
                current = track.title
                val result = runCatching { TrackAnalyzer.analyze(context, track.uri) }
                result.onSuccess { controller.analysis.put(track, it) }
                    .onFailure { failed++ }
                processed++
            }
            current = ""
            running = false
        }
    }

    fun analyzeOne(track: app.onyx.music.LocalTrack) {
        if (running) return
        running = true
        current = track.title
        scope.launch {
            runCatching { TrackAnalyzer.analyze(context, track.uri) }
                .onSuccess { controller.analysis.put(track, it) }
                .onFailure { failed++ }
            current = ""
            running = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("Назад", "Back"),
                    size = 42.dp,
                    iconSize = 20.dp,
                    onClick = onBack,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tr("Анализ треков", "Track analysis"),
                        color = OnyxWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr(
                            "$analyzedCount из ${tracks.size} проанализировано",
                            "$analyzedCount of ${tracks.size} analyzed",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    text = tr(
                        "BPM, тональность (Camelot) и громкость LUFS считаются на телефоне, без сети. " +
                            "Результат кешируется и пересчитывается, только если файл изменился.",
                        "BPM, key (Camelot) and LUFS loudness are computed on the phone, offline. " +
                            "Results are cached and recomputed only when the file changes.",
                    ),
                    color = OnyxDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = if (running) tr("Остановить", "Stop") else tr("Анализировать", "Analyze"),
                        selected = running,
                        onClick = {
                            if (running) {
                                job?.cancel()
                                running = false
                                current = ""
                            } else {
                                startAll()
                            }
                        },
                    )
                    if (cache.isNotEmpty()) {
                        GlassPill(
                            text = tr("Сбросить", "Reset"),
                            selected = false,
                            onClick = { controller.analysis.clear() },
                        )
                    }
                }
                if (running) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = tr(
                            "$processed готово${if (failed > 0) ", ошибок $failed" else ""} · $current",
                            "$processed done${if (failed > 0) ", $failed failed" else ""} · $current",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        items(tracks, key = { it.id }) { track ->
            val features = featuresOf(track.id, track.modified)
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = OnyxWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist.ifBlank { tr("без артиста", "no artist") },
                            color = OnyxFaint,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (features != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = features.bpm?.let { "${it.roundToInt()} BPM" } ?: "— BPM",
                                color = OnyxWhite,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = listOfNotNull(
                                    features.camelot,
                                    features.key,
                                    features.lufs?.let { String.format(Locale.ROOT, "%.1f LUFS", it) },
                                ).joinToString(" · "),
                                color = OnyxFaint,
                                fontSize = 8.sp,
                                maxLines = 1,
                            )
                        }
                    } else {
                        GlassPill(
                            text = if (track.id == current && running) "…" else tr("Анализ", "Analyze"),
                            selected = false,
                            enabled = !running,
                            onClick = { analyzeOne(track) },
                        )
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        tr("Нет локальных треков длиннее 30 секунд", "No local tracks longer than 30 seconds"),
                        color = OnyxDim,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
