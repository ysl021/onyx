package app.onyx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val OnyxBlack = Color(0xFF050506)
val OnyxDeep = Color(0xFF0A0A0C)
val OnyxSurface = Color(0xFF101013)
val OnyxElevated = Color(0xFF17171B)
val OnyxWhite = Color(0xFFF6F6F7)
val OnyxDim = Color(0xFF8C8C93)
val OnyxFaint = Color(0xFF5B5B63)
val OnyxLine = Color(0x1FFFFFFF)
val OnyxLineStrong = Color(0x33FFFFFF)
val OnyxAccent = Color(0xFFEDEDEF)

private val scheme = darkColorScheme(
    primary = OnyxWhite,
    onPrimary = OnyxBlack,
    secondary = OnyxDim,
    onSecondary = OnyxBlack,
    background = OnyxBlack,
    onBackground = OnyxWhite,
    surface = OnyxSurface,
    onSurface = OnyxWhite,
    surfaceVariant = OnyxElevated,
    onSurfaceVariant = OnyxDim,
    outline = OnyxLine,
    error = Color(0xFFFF6B6B),
)

private val onyxTypography = Typography(
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

@Composable
fun OnyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = onyxTypography,
        content = content,
    )
}
