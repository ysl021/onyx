package app.onyx.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.onyx.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The exclusive launcher icon set drawn by the collaborating artist.
 *
 * Icons 11..21 are not in-app UI glyphs: they are full launcher icons, so the
 * previews here are the only place where they can move. Android does not allow
 * animated launcher icons, so each mark gets a short motion that runs in the
 * icon picker and in the splash intro — the home-screen shortcut stays static.
 *
 * Index → art (files come from promo/artist-icons):
 *  11 headphones   12 play       13 vortex     14 waveform   15 sigil
 *  16 antlers      17 orbit      18 crown      19 monolith   20 diamond
 *  21 comet
 */
const val EXCLUSIVE_FIRST = 11
const val EXCLUSIVE_LAST = 21

data class MarkCaption(val ru: String, val en: String)

private val CAPTIONS = mapOf(
    11 to MarkCaption("наушники", "headphones"),
    12 to MarkCaption("плей", "play"),
    13 to MarkCaption("воронка", "vortex"),
    14 to MarkCaption("волна", "waveform"),
    15 to MarkCaption("сигил", "sigil"),
    16 to MarkCaption("ветви", "antlers"),
    17 to MarkCaption("орбита", "orbit"),
    18 to MarkCaption("корона", "crown"),
    19 to MarkCaption("монолит", "monolith"),
    20 to MarkCaption("ромб", "diamond"),
    21 to MarkCaption("комета", "comet"),
)

fun markCaption(index: Int): String =
    CAPTIONS[index]?.let { tr(it.ru, it.en) } ?: tr("эксклюзив", "exclusive")

@DrawableRes
fun markRes(index: Int): Int = when (index) {
    11 -> R.drawable.ic_onyx_11
    12 -> R.drawable.ic_onyx_12
    13 -> R.drawable.ic_onyx_13
    14 -> R.drawable.ic_onyx_14
    15 -> R.drawable.ic_onyx_15
    16 -> R.drawable.ic_onyx_16
    17 -> R.drawable.ic_onyx_17
    18 -> R.drawable.ic_onyx_18
    19 -> R.drawable.ic_onyx_19
    20 -> R.drawable.ic_onyx_20
    else -> R.drawable.ic_onyx_21
}

/** Everything that moves the mark itself; decorations live in [drawMarkDecor]. */
data class MarkMotion(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
)

private const val TAU = (2.0 * PI).toFloat()

/**
 * One motion per mark. [p] is 0..1: in the picker it loops forever, in the
 * intro it runs once, so every curve starts and ends at the resting pose.
 */
fun markMotion(index: Int, p: Float): MarkMotion {
    val wave = sin(TAU * p)
    val wave2 = sin(2f * TAU * p)
    return when (index) {
        // headphones: slow breathing pulse
        11 -> MarkMotion(scaleX = 1f + 0.05f * wave, scaleY = 1f + 0.05f * wave)
        // play: gentle side sweep with a soft fade
        12 -> MarkMotion(dx = 8f * wave, alpha = 0.82f + 0.18f * cos(TAU * p))
        // vortex: full turn, grows in
        13 -> MarkMotion(
            rotation = 360f * p,
            scaleX = 0.92f + 0.08f * p,
            scaleY = 0.92f + 0.08f * p,
        )
        // waveform: amplitude breathing (taller, then wider)
        14 -> MarkMotion(scaleX = 1f - 0.04f * wave2, scaleY = 1f + 0.07f * wave2)
        // sigil: slow rocking and a light bloom
        15 -> MarkMotion(
            rotation = 6f * wave,
            scaleX = 0.97f + 0.03f * wave2,
            scaleY = 0.97f + 0.03f * wave2,
        )
        // antlers: rise into place
        16 -> MarkMotion(dy = -10f * wave, alpha = 0.78f + 0.22f * (0.5f + 0.5f * wave))
        // orbit: swing around the centre
        17 -> MarkMotion(rotation = 12f * wave, scaleX = 0.98f + 0.02f * wave2)
        // crown: hard beat, then rest
        18 -> {
            val beat = max(0f, sin(TAU * p)).let { it * it * it }
            MarkMotion(scaleX = 1f + 0.08f * beat, scaleY = 1f + 0.08f * beat)
        }
        // monolith: grounded, only the height moves
        19 -> MarkMotion(scaleY = 0.95f + 0.05f * wave, alpha = 0.8f + 0.2f * (0.5f + 0.5f * wave))
        // diamond: double-time sparkle
        20 -> {
            val beat = max(0f, wave2).let { it * it }
            MarkMotion(scaleX = 1f + 0.1f * beat, scaleY = 1f + 0.1f * beat)
        }
        // comet: drifts and turns
        else -> MarkMotion(
            rotation = 20f * wave,
            dx = 6f * cos(TAU * p),
            dy = -4f * wave,
        )
    }
}

/** Small decorations around the mark; kept monochrome on purpose. */
fun DrawScope.drawMarkDecor(index: Int, p: Float, w: Float, h: Float) {
    val cx = w / 2f
    val cy = h / 2f
    val white = Color.White
    val wave = sin(TAU * p)

    // A faint orbit ring is shared by the whole exclusive set so the previews
    // read as one family.
    drawCircle(
        color = white.copy(alpha = 0.04f),
        radius = w * 0.40f,
        center = Offset(cx, cy),
        style = Stroke(width = 1.2f),
    )

    when (index) {
        11 -> { // three sound arcs on each side
            for (i in 0..2) {
                val local = ((p * 1.2f + i * 0.22f) % 1f)
                val r = w * (0.24f + 0.16f * local)
                val a = 0.16f * (1f - local)
                drawArc(
                    color = white.copy(alpha = a),
                    startAngle = -52f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = white.copy(alpha = a),
                    startAngle = 128f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                )
            }
        }
        12 -> { // sparks around the arrow
            for (i in 0 until 6) {
                val angle = i * TAU / 6f + p * 0.6f
                val r = w * (0.30f + 0.05f * sin(p * 3f + i))
                val twinkle = (0.35f + 0.65f * (0.5f + 0.5f * sin(p * 5f + i))).coerceIn(0f, 1f)
                drawCircle(
                    color = white.copy(alpha = 0.06f + 0.20f * twinkle),
                    radius = 1.1f + 1.4f * twinkle,
                    center = Offset(cx + r * cos(angle), cy + r * sin(angle)),
                )
            }
        }
        13 -> { // orbiting dust
            for (i in 0..1) {
                val angle = TAU * p + i * PI.toFloat()
                val r = w * (0.34f + i * 0.06f)
                drawCircle(
                    color = white.copy(alpha = 0.22f),
                    radius = if (i == 0) 2.4f else 1.5f,
                    center = Offset(cx + r * cos(angle), cy + r * sin(angle)),
                )
            }
        }
        14 -> { // vertical scan through the bars
            val y = cy - h * 0.34f + h * 0.68f * p
            drawLine(
                color = white.copy(alpha = 0.10f),
                start = Offset(cx - w * 0.30f, y),
                end = Offset(cx + w * 0.30f, y),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
        }
        15 -> { // diamond glint
            val s = w * 0.10f * (1f - abs(2f * p - 1f))
            if (s > 0.5f) {
                drawLine(
                    color = white.copy(alpha = 0.22f),
                    start = Offset(cx - s, cy - s),
                    end = Offset(cx + s, cy + s),
                    strokeWidth = 1.4f,
                )
                drawLine(
                    color = white.copy(alpha = 0.22f),
                    start = Offset(cx + s, cy - s),
                    end = Offset(cx - s, cy + s),
                    strokeWidth = 1.4f,
                )
            }
        }
        16 -> { // seeds rising
            for (i in 0 until 5) {
                val local = ((p + i * 0.17f) % 1f)
                val x = cx + sin(i * 2.4f) * w * 0.22f
                val y = cy + h * 0.28f - local * h * 0.5f
                drawCircle(
                    color = white.copy(alpha = 0.18f * (1f - local)),
                    radius = 1.2f,
                    center = Offset(x, y),
                )
            }
        }
        17 -> { // two dashed arcs
            for (i in 0..1) {
                drawArc(
                    color = white.copy(alpha = 0.12f),
                    startAngle = (i * 180f) + 40f * wave,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(cx - w * 0.36f, cy - w * 0.36f),
                    size = androidx.compose.ui.geometry.Size(w * 0.72f, w * 0.72f),
                    style = Stroke(width = 1.6f),
                )
            }
        }
        18 -> { // glitch bars
            val off = (if (p < 0.5f) 1f else -1f) * w * 0.03f
            for (i in 0..1) {
                drawLine(
                    color = white.copy(alpha = 0.10f),
                    start = Offset(cx - w * 0.22f + off * i, cy - 10f + i * 20f),
                    end = Offset(cx + w * 0.22f + off * i, cy - 10f + i * 20f),
                    strokeWidth = 1.4f,
                )
            }
        }
        19 -> { // vertical shimmer
            val x = cx - w * 0.26f + w * 0.52f * p
            drawLine(
                color = white.copy(alpha = 0.10f),
                start = Offset(x, cy - h * 0.30f),
                end = Offset(x, cy + h * 0.30f),
                strokeWidth = 1.6f,
            )
        }
        20 -> { // shine sweep
            val x = cx - w * 0.34f + w * 0.68f * p
            drawLine(
                color = white.copy(alpha = 0.14f * sin(PI.toFloat() * p)),
                start = Offset(x, cy - h * 0.28f),
                end = Offset(x + 10f, cy + h * 0.28f),
                strokeWidth = 7f,
                cap = StrokeCap.Round,
            )
        }
        21 -> { // comet tail
            for (i in 1..4) {
                val local = (p + i * 0.08f) % 1f
                val angle = TAU * (p - i * 0.05f)
                val r = w * 0.34f
                drawCircle(
                    color = white.copy(alpha = 0.16f / i),
                    radius = (3f - i * 0.5f).coerceAtLeast(0.6f),
                    center = Offset(cx + r * cos(angle), cy + r * sin(angle)),
                )
            }
        }
    }
}

/**
 * An exclusive mark with its own motion. [progress] drives the animation:
 * pass a looping 0..1 in the picker, or the intro's 0..1 to play it once.
 */
@Composable
fun ExclusiveMark(
    index: Int,
    progress: Float,
    modifier: Modifier = Modifier,
    decorations: Boolean = true,
) {
    val motion = markMotion(index, progress)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (decorations) {
            Canvas(Modifier.fillMaxSize()) {
                drawMarkDecor(index, progress, size.width, size.height)
            }
        }
        Image(
            painter = painterResource(markRes(index)),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(0.78f)
                .graphicsLayer {
                    scaleX = motion.scaleX
                    scaleY = motion.scaleY
                    rotationZ = motion.rotation
                    translationX = motion.dx.dp.toPx()
                    translationY = motion.dy.dp.toPx()
                    alpha = motion.alpha
                },
        )
    }
}
