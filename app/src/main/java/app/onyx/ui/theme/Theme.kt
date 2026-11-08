package app.onyx.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.onyx.core.ThemeMode

data class OnyxColors(
    val black: Color,
    val deep: Color,
    val surface: Color,
    val elevated: Color,
    val white: Color,
    val dim: Color,
    val faint: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val glassBorder: Color,
    val isLight: Boolean,
)

val DarkOnyx = OnyxColors(
    black = Color(0xFF050506),
    deep = Color(0xFF0A0A0C),
    surface = Color(0xFF101013),
    elevated = Color(0xFF17171B),
    white = Color(0xFFF6F6F7),
    dim = Color(0xFF8C8C93),
    faint = Color(0xFF5B5B63),
    line = Color(0x1FFFFFFF),
    lineStrong = Color(0x33FFFFFF),
    accent = Color(0xFFEDEDEF),
    glassTop = Color(0x2BFFFFFF),
    glassBottom = Color(0x08FFFFFF),
    glassBorder = Color(0x17FFFFFF),
    isLight = false,
)

val LightOnyx = OnyxColors(
    black = Color(0xFFF4F4F1),
    deep = Color(0xFFEDEDEA),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFF8F8F5),
    white = Color(0xFF0A0A0C),
    dim = Color(0xFF5B5B63),
    faint = Color(0xFF8C8C93),
    line = Color(0x1F000000),
    lineStrong = Color(0x33000000),
    accent = Color(0xFF141416),
    glassTop = Color(0xD9FFFFFF),
    glassBottom = Color(0xA6FFFFFF),
    glassBorder = Color(0x14000000),
    isLight = true,
)

val LocalOnyx = staticCompositionLocalOf { DarkOnyx }

val OnyxBlack: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.black
val OnyxDeep: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.deep
val OnyxSurface: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.surface
val OnyxElevated: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.elevated
val OnyxWhite: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.white
val OnyxDim: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.dim
val OnyxFaint: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.faint
val OnyxLine: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.line
val OnyxLineStrong: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.lineStrong
val OnyxAccent: Color
    @Composable @ReadOnlyComposable get() = LocalOnyx.current.accent

private val baseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 54.sp,
        lineHeight = 54.sp,
        letterSpacing = (-2.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.9).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.6).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    ),
)

private fun typographyWith(family: FontFamily?): Typography {
    if (family == null) return baseTypography
    val b = baseTypography
    return b.copy(
        displayLarge = b.displayLarge.copy(fontFamily = family),
        displayMedium = b.displayMedium.copy(fontFamily = family),
        headlineLarge = b.headlineLarge.copy(fontFamily = family),
        headlineMedium = b.headlineMedium.copy(fontFamily = family),
        titleLarge = b.titleLarge.copy(fontFamily = family),
        titleMedium = b.titleMedium.copy(fontFamily = family),
        bodyLarge = b.bodyLarge.copy(fontFamily = family),
        bodyMedium = b.bodyMedium.copy(fontFamily = family),
        labelLarge = b.labelLarge.copy(fontFamily = family),
        labelSmall = b.labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun OnyxTheme(
    mode: ThemeMode = ThemeMode.DARK,
    uiFontFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val target = if (dark) DarkOnyx else LightOnyx
    val spec = tween<Color>(durationMillis = 420)
    val black by animateColorAsState(target.black, spec, label = "themeBlack")
    val deep by animateColorAsState(target.deep, spec, label = "themeDeep")
    val surface by animateColorAsState(target.surface, spec, label = "themeSurface")
    val elevated by animateColorAsState(target.elevated, spec, label = "themeElevated")
    val white by animateColorAsState(target.white, spec, label = "themeWhite")
    val dim by animateColorAsState(target.dim, spec, label = "themeDim")
    val faint by animateColorAsState(target.faint, spec, label = "themeFaint")
    val line by animateColorAsState(target.line, spec, label = "themeLine")
    val lineStrong by animateColorAsState(target.lineStrong, spec, label = "themeLineStrong")
    val accent by animateColorAsState(target.accent, spec, label = "themeAccent")
    val glassTop by animateColorAsState(target.glassTop, spec, label = "themeGlassTop")
    val glassBottom by animateColorAsState(target.glassBottom, spec, label = "themeGlassBottom")
    val glassBorder by animateColorAsState(target.glassBorder, spec, label = "themeGlassBorder")

    val palette = OnyxColors(
        black = black,
        deep = deep,
        surface = surface,
        elevated = elevated,
        white = white,
        dim = dim,
        faint = faint,
        line = line,
        lineStrong = lineStrong,
        accent = accent,
        glassTop = glassTop,
        glassBottom = glassBottom,
        glassBorder = glassBorder,
        isLight = target.isLight,
    )

    val material = if (target.isLight) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.surface,
            secondary = palette.dim,
            onSecondary = palette.surface,
            background = palette.black,
            onBackground = palette.white,
            surface = palette.surface,
            onSurface = palette.white,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.dim,
            outline = palette.line,
            error = Color(0xFFC83A3A),
        )
    } else {
        darkColorScheme(
            primary = palette.white,
            onPrimary = palette.black,
            secondary = palette.dim,
            onSecondary = palette.black,
            background = palette.black,
            onBackground = palette.white,
            surface = palette.surface,
            onSurface = palette.white,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.dim,
            outline = palette.line,
            error = Color(0xFFFF6B6B),
        )
    }

    CompositionLocalProvider(LocalOnyx provides palette) {
        MaterialTheme(
            colorScheme = material,
            typography = typographyWith(uiFontFamily),
            content = content,
        )
    }
}
