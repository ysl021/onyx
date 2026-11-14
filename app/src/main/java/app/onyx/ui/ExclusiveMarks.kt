package app.onyx.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import app.onyx.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The exclusive launcher icon set drawn by the collaborating artist.
 *
 * The marks are raster launcher icons, so this is the only place where they can
 * move: Android does not animate home-screen shortcuts. Every preview is a
 * small composed scene rather than a plain scale/rotate — glow, ghost copy,
 * rings, sparks, shockwaves, light sweeps, masked reveals and, for the crown,
 * a glitch pass. The same animation plays in the icon picker and in the splash
 * intro.
 *
 * Index → art (originals in promo/artist-icons):
 *  11 headphones  12 play      13 vortex    14 waveform  15 sigil
 *  16 antlers     17 orbit     18 crown     19 monolith  20 diamond
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

private const val TAU = (2.0 * PI).toFloat()
private const val MARK_SHARE = 0.74f

@Composable
fun ExclusiveMark(
    index: Int,
    progress: Float,
    modifier: Modifier = Modifier,
    decorations: Boolean = true,
) {
    val painter = painterResource(markRes(index))
    Canvas(modifier) {
        val p = progress.coerceIn(0f, 1f)
        val beat = beatAt(p)
        val glow = 0.10f + 0.16f * beat
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = glow),
                    Color.White.copy(alpha = glow * 0.35f),
                    Color.Transparent,
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension * (0.42f + 0.06f * beat),
            ),
            radius = size.minDimension * 0.52f,
            center = Offset(size.width / 2f, size.height / 2f),
        )

        // A faint counter-moving copy gives depth without a second colour.
        drawMark(index, painter, p, ghost = true, beat = beat)

        when (index) {
            12 -> drawSweepReveal(index, painter, p, beat)
            14 -> drawScanReveal(index, painter, p, beat)
            18 -> drawGlitchReveal(index, painter, p, beat)
            19 -> drawBuildReveal(index, painter, p, beat)
            else -> drawMark(index, painter, p, ghost = false, beat = beat)
        }

        if (decorations) drawEffects(index, p, beat)
    }
}

/* --------------------------------- motion --------------------------------- */

private data class Pose(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val alpha: Float = 1f,
)

/** Sharp attack, long release — the "thump" used by the beat-driven marks. */
private fun beatAt(p: Float): Float {
    val t = (p * 2f) % 1f
    return (1f - t).pow(5)
}

private fun easeOut(p: Float): Float = 1f - (1f - p).pow(3)
private fun easeInOut(p: Float): Float =
    if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3) / 2f

private fun poseFor(index: Int, p: Float, beat: Float, ghost: Boolean): Pose {
    val wave = sin(TAU * p)
    val wave2 = sin(2f * TAU * p)
    val tilt = if (ghost) -1f else 1f
    return when (index) {
        11 -> Pose(
            scaleX = 1f + 0.045f * wave,
            scaleY = 1f + 0.045f * wave,
            rotation = 1.6f * wave * tilt,
            alpha = if (ghost) 0.14f else 1f,
        )
        12 -> Pose(
            dx = 5f * wave - if (ghost) 7f else 0f,
            rotation = 1.2f * wave,
            alpha = if (ghost) 0.12f else 1f,
        )
        13 -> Pose(
            rotation = if (ghost) -14f + 360f * p else 360f * p,
            scaleX = 0.93f + 0.07f * easeOut(p),
            scaleY = 0.93f + 0.07f * easeOut(p),
            alpha = if (ghost) 0.10f else 1f,
        )
        14 -> Pose(
            scaleX = 1f - 0.035f * wave2,
            scaleY = 1f + 0.065f * wave2 + 0.035f * beat,
            alpha = if (ghost) 0.12f else 1f,
        )
        15 -> Pose(
            rotation = 7f * wave,
            scaleX = 0.97f + 0.04f * beat,
            scaleY = 0.97f + 0.04f * beat,
            alpha = if (ghost) 0.14f else 1f,
        )
        16 -> {
            val rise = easeOut(min(1f, p * 1.7f))
            Pose(
                dy = (1f - rise) * 26f + 2.2f * wave,
                rotation = 2.4f * wave,
                alpha = if (ghost) 0.10f else 0.35f + 0.65f * rise,
            )
        }
        17 -> Pose(
            rotation = 18f * wave + if (ghost) -10f else 0f,
            scaleX = 0.98f + 0.02f * wave2,
            scaleY = 0.98f + 0.02f * wave2,
            alpha = if (ghost) 0.12f else 1f,
        )
        18 -> Pose(
            scaleX = 1f + 0.075f * beat,
            scaleY = 1f + 0.075f * beat,
            dx = if (ghost) 5f else 0f,
            alpha = if (ghost) 0.10f else 1f,
        )
        19 -> Pose(
            scaleY = 0.94f + 0.06f * easeOut(p),
            alpha = if (ghost) 0.08f else 1f,
        )
        20 -> Pose(
            scaleX = 1f + 0.10f * beat,
            scaleY = 1f + 0.10f * beat,
            rotation = if (ghost) 8f else 0f,
            alpha = if (ghost) 0.12f else 1f,
        )
        else -> Pose(
            rotation = if (ghost) -24f else 24f * wave,
            dx = 4f * cos(TAU * p),
            dy = -3f * wave,
            alpha = if (ghost) 0.08f else 1f,
        )
    }
}

/* ------------------------------- mark drawing ------------------------------ */

private fun DrawScope.drawMark(
    index: Int,
    painter: Painter,
    p: Float,
    ghost: Boolean,
    beat: Float,
    clipTop: Float = 0f,
    clipBottom: Float = 1f,
    extraDx: Float = 0f,
    extraAlpha: Float = 1f,
) {
    val pose = poseFor(index, p, beat, ghost)
    val side = size.minDimension * MARK_SHARE * pose.scaleX
    val height = size.minDimension * MARK_SHARE * pose.scaleY
    val cx = size.width / 2f + pose.dx + extraDx
    val cy = size.height / 2f + pose.dy
    if (clipBottom <= clipTop) return
    clipRect(0f, size.height * clipTop, size.width, size.height * clipBottom) {
        translate(left = cx - side / 2f, top = cy - height / 2f) {
            rotate(pose.rotation, pivot = Offset(side / 2f, height / 2f)) {
                with(painter) {
                    draw(
                        size = Size(side, height),
                        alpha = (pose.alpha * extraAlpha).coerceIn(0f, 1f),
                    )
                }
            }
        }
    }
}

/** Play: a light band slides across the mark while the rest stays dim. */
private fun DrawScope.drawSweepReveal(index: Int, painter: Painter, p: Float, beat: Float) {
    drawMark(index, painter, p, ghost = false, beat = beat, extraAlpha = 0.55f)
    val band = 0.22f
    val top = (-0.15f + p * 1.3f).coerceIn(-band, 1f)
    drawMark(
        index, painter, p, ghost = false, beat = beat,
        clipTop = max(0f, top),
        clipBottom = min(1f, top + band),
        extraAlpha = 1f,
    )
    val y = size.height * (top + band / 2f)
    drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = Offset(size.width * 0.14f, y),
        end = Offset(size.width * 0.86f, y),
        strokeWidth = 1.2f,
        cap = StrokeCap.Round,
    )
}

/** Waveform: sonar-style scan plus a full mark underneath. */
private fun DrawScope.drawScanReveal(index: Int, painter: Painter, p: Float, beat: Float) {
    drawMark(index, painter, p, ghost = false, beat = beat, extraAlpha = 0.62f)
    val top = (p * 1.15f - 0.08f).coerceIn(0f, 1f)
    drawMark(
        index, painter, p, ghost = false, beat = beat,
        clipTop = max(0f, top - 0.10f),
        clipBottom = min(1f, top + 0.10f),
        extraAlpha = 1f,
    )
    for (i in 0..2) {
        val local = ((p * 1.1f + i * 0.26f) % 1f)
        drawCircle(
            color = Color.White.copy(alpha = 0.10f * (1f - local)),
            radius = size.minDimension * (0.24f + 0.22f * local),
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = 1.4f),
        )
    }
}

/** Crown: three horizontal slices jump sideways on the beat. */
private fun DrawScope.drawGlitchReveal(index: Int, painter: Painter, p: Float, beat: Float) {
    val window = (0.55f..0.78f)
    val glitching = p in window
    val strength = if (glitching) sin((p - window.start) / (window.endInclusive - window.start) * PI.toFloat()) else 0f
    val shift = sin(p * 90f) * size.width * 0.035f * strength
    val bounds = listOf(0f to 0.36f, 0.36f to 0.68f, 0.68f to 1f)
    bounds.forEachIndexed { i, (top, bottom) ->
        val dx = when (i) {
            0 -> shift
            1 -> -shift * 0.7f
            else -> shift * 0.4f
        }
        drawMark(
            index, painter, p, ghost = false, beat = beat,
            clipTop = top,
            clipBottom = bottom,
            extraDx = dx,
        )
    }
    if (glitching) {
        for (i in 0..2) {
            val y = size.height * (0.2f + 0.3f * i)
            drawLine(
                color = Color.White.copy(alpha = 0.10f * strength),
                start = Offset(size.width * 0.1f + shift * i, y),
                end = Offset(size.width * 0.9f + shift * i, y),
                strokeWidth = 1.4f,
            )
        }
    }
}

/** Monolith builds from the base upwards, then light runs along the edge. */
private fun DrawScope.drawBuildReveal(index: Int, painter: Painter, p: Float, beat: Float) {
    val bottom = 0.16f + 0.84f * easeOut(min(1f, p * 1.25f))
    drawMark(
        index, painter, p, ghost = false, beat = beat,
        clipTop = 1f - bottom,
        clipBottom = 1f,
    )
    val y = size.height * (1f - bottom)
    drawLine(
        color = Color.White.copy(alpha = 0.16f * (1f - p * 0.4f)),
        start = Offset(size.width * 0.3f, y),
        end = Offset(size.width * 0.7f, y),
        strokeWidth = 1.6f,
        cap = StrokeCap.Round,
    )
    for (i in 0..5) {
        val seed = noise(i * 7 + 3)
        val x = size.width * (0.25f + seed * 0.5f)
        val fall = (p * 1.4f + seed) % 1f
        drawCircle(
            color = Color.White.copy(alpha = 0.12f * (1f - fall)),
            radius = 1.1f,
            center = Offset(x, size.height * (0.15f + 0.7f * fall)),
        )
    }
}

/* -------------------------------- effects --------------------------------- */

private fun noise(seed: Int): Float {
    val v = sin(seed * 12.9898f) * 43758.5453f
    return v - floor(v)
}

private fun DrawScope.drawEffects(index: Int, p: Float, beat: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val white = Color.White
    val wave = sin(TAU * p)

    when (index) {
        11 -> { // sound arcs leaving both cups
            for (i in 0..2) {
                val local = (p * 1.25f + i * 0.24f) % 1f
                val r = size.minDimension * (0.26f + 0.18f * local)
                val a = 0.20f * (1f - local)
                for (side in listOf(0f, 1f)) {
                    val start = if (side == 0f) 132f else -48f
                    drawArc(
                        color = white.copy(alpha = a),
                        startAngle = start,
                        sweepAngle = 96f,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = 2.2f, cap = StrokeCap.Round),
                    )
                }
            }
            for (i in 0 until 10) {
                val seed = noise(i * 13 + 1)
                val travel = (p * 1.3f + seed) % 1f
                val angle = TAU * seed
                val r = size.minDimension * (0.30f + 0.18f * travel)
                drawCircle(
                    color = white.copy(alpha = 0.22f * (1f - travel)),
                    radius = 1f + 1.2f * (1f - travel),
                    center = Offset(cx + r * cos(angle), cy + r * sin(angle)),
                )
            }
        }

        12 -> { // sparks around the arrow and a soft trail behind it
            for (i in 0 until 7) {
                val seed = noise(i * 17 + 5)
                val twinkle = (0.5f + 0.5f * sin(p * 5f * TAU + i * 1.7f)).pow(3)
                val angle = TAU * seed + p * 0.4f
                val r = size.minDimension * (0.30f + 0.06f * sin(p * 3f + i))
                drawCircle(
                    color = white.copy(alpha = 0.08f + 0.26f * twinkle),
                    radius = 0.9f + 1.5f * twinkle,
                    center = Offset(cx + r * cos(angle), cy + r * sin(angle)),
                )
            }
            val x = cx - w * 0.34f + w * 0.68f * p
            drawLine(
                color = white.copy(alpha = 0.12f * sin(PI.toFloat() * p)),
                start = Offset(x, cy - h * 0.22f),
                end = Offset(x + 12f, cy + h * 0.22f),
                strokeWidth = 8f,
                cap = StrokeCap.Round,
            )
        }

        13 -> { // dust orbiting the vortex
            for (i in 0..1) {
                val angle = TAU * p * (if (i == 0) 1f else -0.6f) + i * PI.toFloat()
                repeat(4) { trail ->
                    val a = angle - trail * 0.16f
                    val r = size.minDimension * (0.33f + i * 0.05f)
                    drawCircle(
                        color = white.copy(alpha = 0.20f / (trail + 1)),
                        radius = (2.6f - trail * 0.5f).coerceAtLeast(0.6f),
                        center = Offset(cx + r * cos(a), cy + r * sin(a)),
                    )
                }
            }
            for (i in 0 until 12) {
                val angle = i * TAU / 12f + p * 0.3f
                val local = (p * 2f + i * 0.08f) % 1f
                val r1 = size.minDimension * 0.36f
                val r2 = r1 + size.minDimension * 0.10f * (1f - local)
                drawLine(
                    color = white.copy(alpha = 0.06f * (1f - local)),
                    start = Offset(cx + r1 * cos(angle), cy + r1 * sin(angle)),
                    end = Offset(cx + r2 * cos(angle), cy + r2 * sin(angle)),
                    strokeWidth = 1.2f,
                )
            }
        }

        14 -> { // amplitude ticks on both sides
            for (i in 0 until 7) {
                val amp = (0.5f + 0.5f * sin(p * 2f * TAU + i * 0.8f))
                val len = h * (0.03f + 0.07f * amp)
                val y = cy - h * 0.30f + i * h * 0.10f
                drawLine(
                    color = white.copy(alpha = 0.10f + 0.16f * amp),
                    start = Offset(cx - w * 0.38f, y),
                    end = Offset(cx - w * 0.38f + len * 0.5f, y),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = white.copy(alpha = 0.10f + 0.16f * amp),
                    start = Offset(cx + w * 0.38f, y),
                    end = Offset(cx + w * 0.38f - len * 0.5f, y),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round,
                )
            }
        }

        15 -> { // rotating diamond outline, glint and orbiting dots
            val radius = size.minDimension * 0.34f
            val diamond = Path().apply {
                moveTo(cx, cy - radius)
                lineTo(cx + radius, cy)
                lineTo(cx, cy + radius)
                lineTo(cx - radius, cy)
                close()
            }
            rotate(45f * p, pivot = Offset(cx, cy)) {
                drawPath(
                    path = diamond,
                    color = white.copy(alpha = 0.08f),
                    style = Stroke(width = 1.4f),
                )
            }
            for (i in 0 until 6) {
                val angle = i * TAU / 6f + p * 0.5f
                val r = radius * (0.95f + 0.1f * sin(p * 4f + i))
                drawCircle(
                    color = white.copy(alpha = 0.20f),
                    radius = 1.5f,
                    center = Offset(cx + r * cos(angle), cy + r * sin(angle)),
                )
            }
            val glint = (1f - abs(2f * p - 1f)).pow(2)
            val s = size.minDimension * 0.12f * glint
            if (s > 0.5f) {
                drawLine(
                    color = white.copy(alpha = 0.30f * glint),
                    start = Offset(cx - s, cy - s),
                    end = Offset(cx + s, cy + s),
                    strokeWidth = 1.6f,
                )
                drawLine(
                    color = white.copy(alpha = 0.30f * glint),
                    start = Offset(cx + s, cy - s),
                    end = Offset(cx - s, cy + s),
                    strokeWidth = 1.6f,
                )
            }
        }

        16 -> { // rising seeds + landing shockwave
            for (i in 0 until 8) {
                val seed = noise(i * 11 + 2)
                val local = (p * 1.2f + seed) % 1f
                val x = cx + sin(i * 2.4f) * w * 0.24f
                val y = cy + h * 0.30f - local * h * 0.62f
                drawCircle(
                    color = white.copy(alpha = 0.20f * (1f - local)),
                    radius = 1.1f + 0.8f * (1f - local),
                    center = Offset(x, y),
                )
            }
            val land = (p - 0.22f) / 0.3f
            if (land in 0f..1f) {
                val ease = easeOut(land)
                drawOval(
                    color = white.copy(alpha = 0.18f * (1f - land)),
                    topLeft = Offset(cx - w * 0.22f * ease, cy + h * 0.28f - h * 0.02f * ease),
                    size = Size(w * 0.44f * ease, h * 0.05f + h * 0.02f * ease),
                    style = Stroke(width = 1.6f),
                )
            }
        }

        17 -> { // counter-rotating dashed orbits and a comet
            for (i in 0..1) {
                val rr = size.minDimension * (0.32f + i * 0.06f)
                drawArc(
                    color = white.copy(alpha = 0.12f),
                    startAngle = if (i == 0) p * 360f else -p * 260f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(cx - rr, cy - rr),
                    size = Size(rr * 2f, rr * 2f),
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f)),
                    ),
                )
            }
            val angle = TAU * p
            val r = size.minDimension * 0.36f
            repeat(5) { trail ->
                val a = angle - trail * 0.09f
                drawCircle(
                    color = white.copy(alpha = 0.26f / (trail + 1)),
                    radius = (3.2f - trail * 0.55f).coerceAtLeast(0.7f),
                    center = Offset(cx + r * cos(a), cy + r * sin(a)),
                )
            }
        }

        18 -> { // flash ring on every beat
            val ring = 1f - beat
            drawCircle(
                color = white.copy(alpha = 0.14f * beat),
                radius = size.minDimension * (0.28f + 0.16f * ring),
                center = Offset(cx, cy),
                style = Stroke(width = 2.2f),
            )
            for (i in 0..3) {
                val y = cy - h * 0.24f + i * h * 0.16f
                drawLine(
                    color = white.copy(alpha = 0.05f + 0.06f * beat),
                    start = Offset(cx - w * 0.28f, y),
                    end = Offset(cx + w * 0.28f, y),
                    strokeWidth = 1f,
                )
            }
        }

        19 -> { // vertical light and floating dust
            val x = cx - w * 0.24f + w * 0.48f * p
            drawLine(
                color = white.copy(alpha = 0.10f * sin(PI.toFloat() * p)),
                start = Offset(x, cy - h * 0.34f),
                end = Offset(x, cy + h * 0.34f),
                strokeWidth = 1.6f,
            )
            for (i in 0 until 8) {
                val seed = noise(i * 5 + 9)
                val local = (p + seed) % 1f
                drawCircle(
                    color = white.copy(alpha = 0.10f * (1f - local)),
                    radius = 0.9f,
                    center = Offset(w * (0.22f + 0.56f * seed), h * (0.18f + 0.68f * local)),
                )
            }
        }

        20 -> { // cross flash + four small diamonds on the beat
            val flash = beat
            val len = size.minDimension * (0.10f + 0.14f * flash)
            drawLine(
                color = white.copy(alpha = 0.22f * flash),
                start = Offset(cx - len, cy),
                end = Offset(cx + len, cy),
                strokeWidth = 1.6f,
            )
            drawLine(
                color = white.copy(alpha = 0.22f * flash),
                start = Offset(cx, cy - len),
                end = Offset(cx, cy + len),
                strokeWidth = 1.6f,
            )
            for (i in 0..3) {
                val angle = i * PI.toFloat() / 2f + p * 0.6f
                val r = size.minDimension * 0.33f
                val s = 2.4f + 2.6f * flash
                val px = cx + r * cos(angle)
                val py = cy + r * sin(angle)
                rotate(45f, pivot = Offset(px, py)) {
                    drawRect(
                        color = white.copy(alpha = 0.28f),
                        topLeft = Offset(px - s / 2f, py - s / 2f),
                        size = Size(s, s),
                    )
                }
            }
            val sweep = cx - w * 0.34f + w * 0.68f * p
            drawLine(
                color = white.copy(alpha = 0.14f * sin(PI.toFloat() * p)),
                start = Offset(sweep, cy - h * 0.26f),
                end = Offset(sweep + 12f, cy + h * 0.26f),
                strokeWidth = 7f,
                cap = StrokeCap.Round,
            )
        }

        else -> { // comet tail and twinkles
            val angle = TAU * p
            val r = size.minDimension * 0.35f
            repeat(9) { trail ->
                val a = angle - trail * 0.06f
                drawCircle(
                    color = white.copy(alpha = 0.22f / (trail + 1)),
                    radius = (4.2f - trail * 0.38f).coerceAtLeast(0.6f),
                    center = Offset(cx + r * cos(a), cy + r * sin(a)),
                )
            }
            for (i in 0 until 6) {
                val seed = noise(i * 19 + 4)
                val twinkle = (0.5f + 0.5f * sin(p * 3f * TAU + i * 2.1f)).pow(4)
                drawCircle(
                    color = white.copy(alpha = 0.30f * twinkle),
                    radius = 0.8f + 1.2f * twinkle,
                    center = Offset(w * (0.18f + 0.64f * seed), h * (0.16f + 0.68f * noise(i * 7 + 11))),
                )
            }
        }
    }
}
