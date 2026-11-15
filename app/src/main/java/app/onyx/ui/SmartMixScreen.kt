package app.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LocalTrack
import app.onyx.music.Mood
import app.onyx.music.Similarity
import app.onyx.music.TrackFeatures
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Two things built on the offline analysis: tracks similar to what is playing
 * and a mood-based session that fits a chosen length. Both only use analysed
 * files; the screen says so instead of guessing.
 */
@Composable
fun SmartMixScreen(
    controller: AppController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val cache by controller.analysis.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var moodName by rememberSaveable { mutableStateOf(Mood.Deep.name) }
    var minutes by rememberSaveable { mutableIntStateOf(45) }
    val mood = runCatching { Mood.valueOf(moodName) }.getOrDefault(Mood.Deep)
    val seed = playback.current

    fun featuresOf(track: LocalTrack): TrackFeatures? =
        cache[track.id]?.takeIf { it.modified == track.modified }?.features

    val similar = remember(seed?.id, cache, library.tracks) {
        if (seed == null) emptyList() else Similarity.find(
            seed = seed,
            tracks = library.tracks,
            featuresOf = { featuresOf(it) },
        )
    }
    val session = remember(mood, minutes, cache, library.tracks, seed?.id) {
        Similarity.buildSession(
            tracks = library.tracks,
            featuresOf = { featuresOf(it) },
            mood = mood,
            minutes = minutes,
            seed = seed,
        )
    }
    val analyzed = remember(cache) { cache.values.size }

    fun playList(items: List<Similarity.Scored>, start: LocalTrack? = null) {
        if (items.isEmpty()) return
        val queue = items.map { it.track }
        val track = start ?: queue.first()
        controller.localPlayer.play(track, queue)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    text = tr("Умный плейлист", "Smart mix"),
                    color = OnyxWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = tr(
                        "$analyzed треков проанализировано",
                        "$analyzed tracks analyzed",
                    ),
                    color = OnyxFaint,
                    fontSize = 10.sp,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Похожие", "Similar"),
                selected = mode == 0,
                onClick = { mode = 0 },
            )
            GlassPill(
                text = tr("Сессия", "Session"),
                selected = mode == 1,
                onClick = { mode = 1 },
            )
        }

        if (analyzed == 0) {
            EmptyAnalysis()
            return@Column
        }

        if (mode == 0) {
            if (seed == null || featuresOf(seed) == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr(
                            "Включи локальный трек, который уже проанализирован,\nчтобы увидеть похожие.",
                            "Play an analysed local track\nto see similar ones.",
                        ),
                        color = OnyxDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                return@Column
            }
            if (similar.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr("Похожих пока нет", "No similar tracks yet"),
                        color = OnyxDim,
                        fontSize = 12.sp,
                    )
                }
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(tr("Похоже на «${seed.title}»", "Similar to «${seed.title}»"), Modifier.weight(1f))
                GlassPill(
                    text = tr("Играть", "Play"),
                    selected = true,
                    onClick = { playList(similar) },
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(similar, key = { it.track.id }) { item ->
                    ScoredRow(
                        item = item,
                        playing = playback.current?.id == item.track.id && playback.isPlaying,
                        onClick = { playList(similar, item.track) },
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MoodPill(tr("Спокойное", "Calm"), mood == Mood.Calm) { moodName = Mood.Calm.name }
                MoodPill(tr("Глубокое", "Deep"), mood == Mood.Deep) { moodName = Mood.Deep.name }
                MoodPill(tr("Энергичное", "Energetic"), mood == Mood.Energetic) { moodName = Mood.Energetic.name }
                MoodPill(tr("Тёмное", "Dark"), mood == Mood.Dark) { moodName = Mood.Dark.name }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(30, 45, 60, 90).forEach { value ->
                    GlassPill(
                        text = "$value ${tr("мин", "min")}",
                        selected = minutes == value,
                        onClick = { minutes = value },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(
                    tr(
                        "${session.size} треков · ${formatMinutes(session)}",
                        "${session.size} tracks · ${formatMinutes(session)}",
                    ),
                    Modifier.weight(1f),
                )
                GlassPill(
                    text = tr("Запустить", "Start"),
                    selected = true,
                    enabled = session.isNotEmpty(),
                    onClick = { playList(session) },
                )
            }
            if (session.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr(
                            "Для сессии нужно хотя бы несколько проанализированных треков.",
                            "A session needs a few analysed tracks.",
                        ),
                        color = OnyxDim,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(session, key = { it.track.id }) { item ->
                        ScoredRow(
                            item = item,
                            playing = playback.current?.id == item.track.id && playback.isPlaying,
                            onClick = { playList(session, item.track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodPill(label: String, selected: Boolean, onClick: () -> Unit) {
    GlassPill(text = label, selected = selected, onClick = onClick)
}

@Composable
private fun ScoredRow(
    item: Similarity.Scored,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val features = item.features
    val meta = listOfNotNull(
        features.bpm?.let { "${it.roundToInt()} BPM" },
        features.camelot,
        features.lufs?.let { String.format(Locale.ROOT, "%.1f LUFS", it) },
    ).joinToString(" · ")
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.track.title,
                    color = if (playing) OnyxWhite else OnyxWhite.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(item.track.artist, meta).filter { it.isNotBlank() }.joinToString(" · "),
                    color = OnyxFaint,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OnyxLine.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${(item.score * 100).roundToInt()}",
                    color = OnyxDim,
                    fontSize = 8.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyAnalysis() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            tr(
                "Сначала проанализируй треки: Настройки → Библиотека → Анализ треков.",
                "Analyse some tracks first: Settings → Library → Track analysis.",
            ),
            color = OnyxDim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

private fun formatMinutes(items: List<Similarity.Scored>): String {
    val seconds = items.sumOf { it.track.durationMs } / 1000
    val minutes = (seconds / 60).coerceAtLeast(1)
    return "$minutes ${tr("мин", "min")}"
}
