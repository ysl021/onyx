package app.onyx.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LibraryHealth
import app.onyx.music.LocalTrack
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.launch

/**
 * Library health report. Broken titles are fixed through the same override
 * table the tag editor uses, so nothing on disk changes; duplicates and missing
 * covers are listed for a decision instead of being deleted automatically.
 */
@Composable
fun LibraryHealthScreen(
    controller: AppController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val report = remember(library.tracks) { LibraryHealth.analyze(library.tracks) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                Column {
                    Text(
                        text = tr("Состояние медиатеки", "Library health"),
                        color = OnyxWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr(
                            "${report.total} треков · ${report.artists} артистов · ${report.albums} альбомов",
                            "${report.total} tracks · ${report.artists} artists · ${report.albums} albums",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel(tr("Найдено", "Found"))
                Spacer(Modifier.height(8.dp))
                HealthLine(
                    label = tr("Битые названия", "Broken titles"),
                    value = report.broken.size,
                )
                HealthLine(
                    label = tr("Дубликаты", "Duplicates"),
                    value = report.duplicates.sumOf { it.others.size },
                )
                HealthLine(
                    label = tr("Без обложки", "No cover"),
                    value = report.noCover.size,
                )
                HealthLine(
                    label = tr("Без артиста", "No artist"),
                    value = report.noArtist.size,
                )
                HealthLine(
                    label = tr("Без альбома", "No album"),
                    value = report.noAlbum.size,
                )
            }
        }

        if (report.broken.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(
                        tr("Битые названия", "Broken titles"),
                        Modifier.weight(1f),
                    )
                    GlassPill(
                        text = tr("Исправить все", "Fix all"),
                        selected = true,
                        onClick = {
                            report.broken.forEach { item ->
                                controller.localLibrary.updateTrack(
                                    id = item.track.id,
                                    title = item.fixedTitle ?: item.track.title,
                                    artist = item.fixedArtist ?: item.track.artist,
                                    album = item.track.album,
                                )
                            }
                            controller.toast(tr("Названия исправлены", "Titles fixed"))
                        },
                    )
                }
            }
            items(report.broken.take(40), key = { it.track.id }) { item ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        text = item.track.title,
                        color = OnyxWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val preview = listOfNotNull(item.fixedTitle, item.fixedArtist)
                        .joinToString(" · ")
                    if (preview.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "→ $preview",
                            color = OnyxDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = tr("Исправить", "Fix"),
                            selected = false,
                            onClick = {
                                controller.localLibrary.updateTrack(
                                    id = item.track.id,
                                    title = item.fixedTitle ?: item.track.title,
                                    artist = item.fixedArtist ?: item.track.artist,
                                    album = item.track.album,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (report.duplicates.isNotEmpty()) {
            item {
                SectionLabel(tr("Дубликаты", "Duplicates"))
            }
            items(report.duplicates.take(30), key = { it.best.id }) { group ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        text = group.best.title,
                        color = OnyxWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tr(
                            "Лучшая копия: ${group.best.artist.ifBlank { "—" }} · ещё ${group.others.size}",
                            "Best copy: ${group.best.artist.ifBlank { "—" }} · ${group.others.size} more",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                    group.others.take(3).forEach { other ->
                        Text(
                            text = "· " + other.uri.substringAfterLast('/'),
                            color = OnyxFaint,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (report.noCover.isNotEmpty()) {
            item {
                SectionLabel(tr("Без обложки", "No cover"))
            }
            items(report.noCover.take(25), key = { it.id }) { track ->
                TrackLine(track, tr("нет обложки", "no cover"))
            }
        }

        if (report.broken.isEmpty() && report.duplicates.isEmpty() && report.noCover.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            tr("Медиатека в порядке", "The library is clean"),
                            color = OnyxDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthLine(label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = OnyxDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            if (value == 0) "—" else value.toString(),
            color = if (value == 0) OnyxFaint else OnyxWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TrackLine(track: LocalTrack, note: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            text = track.title,
            color = OnyxWhite,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(track.artist, note).filter { it.isNotBlank() }.joinToString(" · "),
            color = OnyxFaint,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
