package app.onyx.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.audio.VisualizerController
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VisualizerScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val local by controller.localPlayer.state.collectAsStateWithLifecycle()
    val localActive = local.current != null && local.isPlaying
    val visualizer = remember { VisualizerController(context.applicationContext) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    val wave by visualizer.wave.collectAsStateWithLifecycle()
    val fft by visualizer.fft.collectAsStateWithLifecycle()
    val error by visualizer.error.collectAsStateWithLifecycle()

    LaunchedEffect(granted) {
        if (granted) visualizer.start() else visualizer.stop()
    }
    DisposableEffect(Unit) {
        onDispose { visualizer.stop() }
    }

    Box(modifier.fillMaxSize()) {
        // Soft radial glow under the visualizer.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            OnyxWhite.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tr("ВИЗУАЛИЗАТОР", "VISUALIZER"),
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 2.6.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = nowPlaying.title.ifBlank { tr("Тишина", "Silence") },
                color = OnyxWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = nowPlaying.artist,
                color = OnyxDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            if (!granted) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr("Для визуализатора нужен доступ к микрофону — так Android отдаёт аудиопоток.", "Visualizer needs microphone access — that is how Android exposes the audio stream."),
                        color = OnyxDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    GlassPill(
                        text = tr("Разрешить доступ", "Allow access"),
                        selected = true,
                        onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }
            } else {
                Spectrum(
                    wave = wave,
                    fft = fft,
                    art = nowPlaying.art ?: nowPlaying.artUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error.orEmpty(), color = OnyxFaint, fontSize = 10.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.weight(0.8f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircleButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = tr("Предыдущий", "Previous"),
                    size = 48.dp,
                    onClick = {
                        if (localActive) controller.localPlayer.previous() else controller.media.previous()
                    },
                )
                CircleButton(
                    icon = if (nowPlaying.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = tr("Играть/пауза", "Play/pause"),
                    size = 64.dp,
                    filled = true,
                    iconSize = 26.dp,
                    onClick = {
                        if (localActive) controller.localPlayer.toggle() else controller.media.playPause()
                    },
                )
                CircleButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = tr("Следующий", "Next"),
                    size = 48.dp,
                    onClick = {
                        if (localActive) controller.localPlayer.next() else controller.media.next()
                    },
                )
            }
        }
    }
}

@Composable
private fun Spectrum(
    wave: FloatArray,
    fft: FloatArray,
    art: Any?,
    modifier: Modifier = Modifier,
) {
    val color = OnyxWhite
    val bars = 108
    val target = remember { FloatArray(bars) }
    val display = remember { FloatArray(bars) }
    var frame by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(true) }

    LaunchedEffect(fft, wave) {
        val hasFft = fft.isNotEmpty() && fft.any { it > 0.02f }
        val hasWave = wave.isNotEmpty() && wave.any { abs(it) > 0.012f }
        if (!hasFft && !hasWave) {
            idle = true
            return@LaunchedEffect
        }
        idle = false
        for (i in 0 until bars) {
            val sample = when {
                hasFft -> fft[(i * fft.size / bars).coerceIn(0, fft.size - 1)]
                else -> wave[(i * wave.size / bars).coerceIn(0, wave.size - 1)]
            }
            target[i] = abs(sample).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f
                else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                val t = now / 1_000_000_000f
                for (i in 0 until bars) {
                    val goal = if (idle) {
                        (0.05f + 0.05f * sin(t * 1.4f + i * 0.33f) +
                            0.02f * sin(t * 2.1f - i * 0.2f)).coerceIn(0f, 0.16f)
                    } else {
                        target[i]
                    }
                    val speed = if (goal > display[i]) 22f else 6f
                    display[i] += (goal - display[i]) * (1f - kotlin.math.exp(-speed * dt))
                }
                frame++
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncArt(
            model = art,
            modifier = Modifier
                .size(136.dp)
                .clip(CircleShape),
        )
        Canvas(Modifier.fillMaxSize()) {
            val _draw = frame
            val cx = size.width / 2f
            val cy = size.height / 2f
            val inner = size.minDimension * 0.235f
            val maxLen = size.minDimension * 0.225f
            val twoPi = (Math.PI * 2).toFloat()
            for (i in 0 until bars) {
                val angle = -Math.PI.toFloat() / 2f + i * twoPi / bars
                val dx = cos(angle)
                val dy = sin(angle)
                val amp = (display[i] * maxLen).coerceAtLeast(1.5f)
                val start = Offset(cx + dx * inner, cy + dy * inner)
                val end = Offset(cx + dx * (inner + amp), cy + dy * (inner + amp))
                drawLine(
                    color = color.copy(alpha = 0.08f),
                    start = start,
                    end = Offset(
                        cx + dx * (inner + amp * 1.28f),
                        cy + dy * (inner + amp * 1.28f),
                    ),
                    strokeWidth = 5.5f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color.copy(alpha = 0.86f),
                    start = start,
                    end = end,
                    strokeWidth = 2.1f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = color.copy(alpha = 0.16f),
                radius = inner,
                style = Stroke(width = 1.1f),
            )
            drawCircle(
                color = color.copy(alpha = 0.06f),
                radius = inner + maxLen + 2f,
                style = Stroke(width = 0.8f),
            )
        }
    }
}
