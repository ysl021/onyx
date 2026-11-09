package app.onyx.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onyx.R
import app.onyx.core.IconSwitcher
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Splash that mirrors the launcher icon the user picked: a falling star for the
 * star icon, a spinning record for the vinyl, a blinking eye for the eye and so
 * on. All ten variants share the same ONYX wordmark and timing, only the icon
 * stage changes.
 */
@Composable
fun IntroScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val iconIndex = remember { runCatching { IconSwitcher.current(context) }.getOrDefault(1) }

    val enter = remember { Animatable(0f) }
    val spacing = remember { Animatable(30f) }
    val exit = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(2000, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        spacing.animateTo(7f, tween(1500, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        glow.animateTo(1f, tween(1200))
        delay(2050)
        exit.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        onFinish()
    }

    val progress = enter.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnyxBlack)
            .graphicsLayer { alpha = 1f - exit.value }
            .clickable { onFinish() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f * glow.value * (1f - exit.value)),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    val s = (0.9f + 0.1f * progress) * (1f + 0.24f * exit.value)
                    scaleX = s
                    scaleY = s
                    alpha = 1f - exit.value * 0.6f
                },
        ) {
            IconStage(index = iconIndex, progress = progress)
            Spacer(Modifier.height(20.dp))
            Text(
                text = "ONYX",
                color = OnyxWhite,
                fontSize = 50.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = spacing.value.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = iconCaption(iconIndex),
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 2.4.sp,
            )
        }
    }
}

/** 220dp stage: decorations behind, the actual launcher icon in front. */
@Composable
private fun IconStage(index: Int, progress: Float) {
    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawParticles(progress, size.width, size.height)
            drawHalo(progress, size.width, size.height)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawDecor(index, progress, size.width, size.height)
        }
        Image(
            painter = painterResource(appIconRes(index)),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    val p = progress
                    when (index) {
                        1 -> { // equalizer: bounce + grow
                            scaleX = 0.7f + 0.3f * p
                            scaleY = 0.7f + 0.3f * p
                            translationY = -8f * sin(p * PI.toFloat() * 3f)
                        }
                        2 -> { // rings: unwind from small
                            val s = 0.45f + 0.55f * p
                            scaleX = s
                            scaleY = s
                            rotationZ = -90f + 90f * p
                        }
                        3 -> { // vinyl: spin in
                            scaleX = 0.6f + 0.4f * p
                            scaleY = 0.6f + 0.4f * p
                            rotationZ = 300f * p
                        }
                        4 -> { // waveform: slide in
                            translationX = -90f * (1f - p)
                            alpha = p
                        }
                        5 -> { // bolt: strike from above + flash
                            translationY = -150f * (1f - p) * (1f - p)
                            scaleX = 1.15f - 0.15f * p
                            scaleY = 1.15f - 0.15f * p
                        }
                        6 -> { // star: fall + settle
                            translationY = -190f * (1f - p) * (1f - p)
                            rotationZ = -35f * (1f - p)
                            scaleX = 0.85f + 0.15f * p
                            scaleY = 0.85f + 0.15f * p
                        }
                        7 -> { // moon: rise
                            translationY = 70f * (1f - p)
                            alpha = 0.35f + 0.65f * p
                        }
                        8 -> { // diamond: turn into place
                            rotationZ = 45f * (1f - p)
                            scaleX = 0.55f + 0.45f * p
                            scaleY = 0.55f + 0.45f * p
                        }
                        9 -> { // play: pop
                            val s = if (p < 0.75f) 0.6f + 0.53f * p else 1.0f - 0.03f * (p - 0.75f) * 4f
                            scaleX = s
                            scaleY = s
                        }
                        else -> { // eye: blink
                            val blink = when {
                                p < 0.45f -> 1f
                                p < 0.55f -> 0.12f + 0.88f * ((p - 0.45f) / 0.10f)
                                else -> 1f
                            }
                            scaleX = 0.7f + 0.3f * p
                            scaleY = (0.7f + 0.3f * p) * blink
                        }
                    }
                    alpha *= (0.25f + 0.75f * p)
                },
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParticles(
    p: Float,
    w: Float,
    h: Float,
) {
    for (i in 0 until 16) {
        val seed = ((i * 53) % 97) / 97f
        val phase = (p * 1.3f + i * 0.11f) % 1f
        val x = w * (0.08f + seed * 0.84f)
        val y = h * (1.02f - phase * 1.1f)
        val alpha = (0.04f + 0.16f * (1f - phase)) * p
        val radius = 1.2f + (i % 3) * 1.1f
        drawCircle(
            color = Color.White.copy(alpha = alpha.coerceIn(0f, 0.22f)),
            radius = radius,
            center = Offset(x, y),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalo(
    p: Float,
    w: Float,
    h: Float,
) {
    val cx = w / 2f
    val cy = h / 2f
    val radius = 82f + 10f * sin(p * PI.toFloat())
    drawCircle(
        color = Color.White.copy(alpha = 0.06f * p),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f),
    )
    val angle = p * 2f * PI.toFloat()
    drawCircle(
        color = Color.White.copy(alpha = 0.35f * p),
        radius = 2.5f,
        center = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDecor(
    index: Int,
    p: Float,
    w: Float,
    h: Float,
) {
    val cx = w / 2f
    val cy = h / 2f
    val white = Color.White
    when (index) {
        1 -> { // five bars rising with the icon
            val base = cy + 52f
            val barW = 9f
            for (i in 0 until 5) {
                val phase = (p * 1.6f - i * 0.08f).coerceIn(0f, 1f)
                val barH = 10f + 46f * phase * (0.5f + 0.5f * sin((i + 1) * 1.3f).toFloat())
                val x = cx - 2 * 22f + i * 22f
                drawLine(
                    color = white.copy(alpha = 0.10f + 0.25f * phase),
                    start = Offset(x, base),
                    end = Offset(x, base - barH),
                    strokeWidth = barW,
                    cap = StrokeCap.Round,
                )
            }
        }
        2 -> { // two rings expanding
            for (i in 0..1) {
                val local = (p * 1.4f - i * 0.25f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                drawCircle(
                    color = white.copy(alpha = 0.16f * (1f - local)),
                    radius = 46f + 66f * local,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.5f),
                )
            }
        }
        3 -> { // orbit dot around the record
            val angle = p * 2f * PI.toFloat()
            val radius = 78f
            drawCircle(
                color = white.copy(alpha = 0.25f),
                radius = 3.5f,
                center = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
            )
            drawCircle(
                color = white.copy(alpha = 0.06f),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f),
            )
        }
        4 -> { // travelling sine
            val path = Path()
            val width = 168f
            val left = cx - width / 2f
            val amp = 18f * p
            for (step in 0..48) {
                val t = step / 48f
                val x = left + width * t
                val y = cy + amp * sin(t * 4f * PI.toFloat() + p * 6f)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = white.copy(alpha = 0.28f * p),
                style = Stroke(width = 3f, cap = StrokeCap.Round),
            )
        }
        5 -> { // electric flash
            val flash = (1f - (p - 0.45f).absoluteValue * 5f).coerceIn(0f, 1f)
            if (flash > 0f) {
                drawCircle(
                    color = white.copy(alpha = 0.22f * flash),
                    radius = 40f + 50f * flash,
                    center = Offset(cx, cy),
                )
            }
            val bolt = Path().apply {
                moveTo(cx - 26f, cy - 70f)
                lineTo(cx + 4f, cy - 10f)
                lineTo(cx - 12f, cy - 4f)
                lineTo(cx + 24f, cy + 72f)
                lineTo(cx + 2f, cy + 8f)
                lineTo(cx + 18f, cy + 2f)
                close()
            }
            drawPath(bolt, white.copy(alpha = 0.10f * p))
        }
        6 -> { // star trail + twinkles
            val tail = (1f - p).coerceIn(0f, 1f)
            drawLine(
                color = white.copy(alpha = 0.22f * tail),
                start = Offset(cx + 34f, cy - 120f - 30f * tail),
                end = Offset(cx + 10f, cy - 34f),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
            for (i in 0 until 7) {
                val angle = i * 0.9f
                val radius = 78f + 14f * sin(i * 2.1f)
                val twinkle = (sin(p * 5f + i) * 0.5f + 0.5f) * p
                drawCircle(
                    color = white.copy(alpha = 0.10f + 0.35f * twinkle),
                    radius = 1.4f + 1.6f * twinkle,
                    center = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
                )
            }
        }
        7 -> { // stars appearing around the rising moon
            for (i in 0 until 10) {
                val appear = ((p - i * 0.05f) * 2.2f).coerceIn(0f, 1f)
                val angle = i * 2.4f
                val radius = 62f + 26f * sin(i * 1.7f)
                drawCircle(
                    color = white.copy(alpha = 0.12f + 0.5f * appear),
                    radius = 1.2f + 1.2f * appear,
                    center = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
                )
            }
        }
        8 -> { // shine sweep across the diamond
            val x = cx - 110f + 220f * p
            drawLine(
                color = white.copy(alpha = 0.18f * sin(p * PI.toFloat())),
                start = Offset(x, cy - 90f),
                end = Offset(x + 40f, cy + 90f),
                strokeWidth = 12f,
                cap = StrokeCap.Round,
            )
        }
        9 -> { // ring pulse behind play
            drawCircle(
                color = white.copy(alpha = 0.20f * (1f - p)),
                radius = 54f + 60f * p,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f),
            )
        }
        else -> { // soft eye glow
            drawCircle(
                color = white.copy(alpha = 0.10f * p),
                radius = 72f,
                center = Offset(cx, cy),
            )
        }
    }
}

private val Float.absoluteValue: Float get() = if (this < 0f) -this else this

private fun iconCaption(index: Int): String = when (index) {
    1 -> tr("equalizer", "equalizer")
    2 -> tr("rings", "rings")
    3 -> tr("vinyl", "vinyl")
    4 -> tr("waveform", "waveform")
    5 -> tr("bolt", "bolt")
    6 -> tr("star", "star")
    7 -> tr("moon", "moon")
    8 -> tr("diamond", "diamond")
    9 -> tr("play", "play")
    else -> tr("eye", "eye")
}

internal fun appIconRes(index: Int): Int = when (index) {
    1 -> R.drawable.ic_onyx_01
    2 -> R.drawable.ic_onyx_02
    3 -> R.drawable.ic_onyx_03
    4 -> R.drawable.ic_onyx_04
    5 -> R.drawable.ic_onyx_05
    6 -> R.drawable.ic_onyx_06
    7 -> R.drawable.ic_onyx_07
    8 -> R.drawable.ic_onyx_08
    9 -> R.drawable.ic_onyx_09
    else -> R.drawable.ic_onyx_10
}
