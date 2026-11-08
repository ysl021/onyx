package app.onyx.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.onyx.core.CustomTheme

fun CustomTheme.toOnyxColors(): OnyxColors {
    val bg = parseColor(background, Color(0xFF050506))
    val surface = parseColor(surface, bg)
    val text = parseColor(text, Color(0xFFF6F6F7))
    val accent = parseColor(accent, text)
    val isLight = light || bg.luminance() > 0.5f
    val deep = lerp(bg, text, if (isLight) 0.035f else 0.045f)
    val elevated = lerp(surface, text, if (isLight) 0.035f else 0.06f)
    val dim = lerp(bg, text, if (isLight) 0.58f else 0.62f)
    val faint = lerp(bg, text, if (isLight) 0.34f else 0.38f)
    return OnyxColors(
        black = bg,
        deep = deep,
        surface = surface,
        elevated = elevated,
        white = text,
        dim = dim,
        faint = faint,
        line = text.copy(alpha = if (isLight) 0.14f else 0.14f),
        lineStrong = text.copy(alpha = if (isLight) 0.24f else 0.24f),
        accent = accent,
        glassTop = Color.White.copy(alpha = if (isLight) 0.72f else 0.16f),
        glassBottom = Color.White.copy(alpha = if (isLight) 0.50f else 0.05f),
        glassBorder = Color.White.copy(alpha = if (isLight) 0.20f else 0.12f),
        isLight = isLight,
    )
}

private fun parseColor(raw: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrDefault(fallback)
