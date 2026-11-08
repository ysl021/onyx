package app.onyx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onyx.ui.theme.OnyxAccent
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import app.onyx.ui.theme.LocalOnyx
import app.onyx.ui.tr
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    strong: Boolean = false,
    art: Any? = null,
    artBlur: Dp = 40.dp,
    hazeState: HazeState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalOnyx.current
    val base = if (palette.isLight) Color.Black else Color.White
    val hazeFactor = if (hazeState != null) 0.18f else 1f
    val top = when {
        palette.isLight && strong -> 0.07f
        palette.isLight -> 0.045f
        strong -> 0.16f
        else -> 0.085f
    } * hazeFactor
    val bottom = when {
        palette.isLight && strong -> 0.035f
        palette.isLight -> 0.02f
        strong -> 0.06f
        else -> 0.03f
    } * hazeFactor
    val border = when {
        palette.isLight && strong -> 0.10f
        palette.isLight -> 0.07f
        strong -> 0.16f
        else -> 0.09f
    }
    val haze = if (hazeState != null) {
        Modifier.hazeChild(
            state = hazeState,
            shape = shape,
            style = HazeStyle(
                tint = base.copy(alpha = if (palette.isLight) 0.04f else 0.02f),
                blurRadius = 42.dp,
                noiseFactor = 0.03f,
            ),
        )
    } else {
        Modifier
    }
    Box(modifier = modifier.clip(shape).then(haze)) {
        if (art != null && hazeState == null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .blur(artBlur),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = if (palette.isLight) 0.16f else 0.26f,
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(base.copy(alpha = top), base.copy(alpha = bottom)),
                    ),
                )
                .border(
                    BorderStroke(1.dp, base.copy(alpha = border)),
                    shape,
                ),
        )
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    strong: Boolean = false,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier, shape = shape, strong = strong) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = OnyxFaint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun GlassPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalOnyx.current
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> if (palette.isLight) Color.Black.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.03f)
            selected -> OnyxAccent
            else -> if (palette.isLight) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.055f)
        },
        label = "pillBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            !enabled -> OnyxFaint
            selected -> if (palette.isLight) Color.White else Color(0xFF08080A)
            else -> OnyxWhite
        },
        label = "pillFg",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        label = "pillPress",
    )
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else OnyxLine, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) { onClick() }
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    filled: Boolean = false,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    val palette = LocalOnyx.current
    val bg = when {
        filled -> OnyxAccent
        palette.isLight -> Color.Black.copy(alpha = 0.05f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val fg = when {
        filled -> if (palette.isLight) Color.White else Color(0xFF08080A)
        enabled -> OnyxWhite
        else -> OnyxFaint
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.93f else 1f,
        label = "circlePress",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (filled) Color.Transparent else OnyxLine, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun OnyxSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val palette = LocalOnyx.current
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        modifier = modifier.height(28.dp),
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = if (palette.isLight) Color.White else OnyxWhite,
            activeTrackColor = OnyxAccent,
            inactiveTrackColor = if (palette.isLight) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.13f),
            disabledThumbColor = OnyxFaint,
            disabledActiveTrackColor = OnyxFaint,
            disabledInactiveTrackColor = if (palette.isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.07f),
        ),
    )
}

@Composable
fun OnyxSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val palette = LocalOnyx.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = if (palette.isLight) Color.White else Color(0xFF08080A),
            checkedTrackColor = OnyxAccent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = OnyxDim,
            uncheckedTrackColor = if (palette.isLight) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.06f),
            uncheckedBorderColor = OnyxLine,
        ),
    )
}

@Composable
fun BatteryMeter(
    label: String,
    level: Int,
    wearing: Boolean,
    modifier: Modifier = Modifier,
) {
    val known = level >= 0
    val fraction by animateFloatAsState(
        targetValue = if (known) level / 100f else 0f,
        label = "battery",
    )
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = OnyxDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(6.dp))
            if (wearing) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(OnyxWhite),
                )
                Spacer(Modifier.width(4.dp))
                Text(tr("в ухе", "worn"), color = OnyxFaint, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(OnyxWhite.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(OnyxWhite),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (known) "$level" else "—",
                color = OnyxWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun AsyncArt(model: Any?, modifier: Modifier = Modifier) {
    val palette = LocalOnyx.current
    Box(
        modifier = modifier.background(
            if (palette.isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f),
        ),
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@Composable
fun CustomBackdrop(
    art: Any?,
    blurPercent: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyx.current
    val base = palette.black
    val radius = (blurPercent.coerceIn(0, 100) / 100f * 48f).dp
    Box(modifier.fillMaxSize().background(base)) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.04f
                        scaleY = 1.04f
                    }
                    .blur(radius),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = if (palette.isLight) 0.88f else 0.92f,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            base.copy(alpha = 0.08f),
                            base.copy(alpha = 0.16f),
                            base.copy(alpha = 0.30f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val palette = LocalOnyx.current
    val base = palette.black
    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            base,
                            base,
                            Color.White.copy(alpha = if (palette.isLight) 0.10f else 0.035f),
                        ),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = if (palette.isLight) 0.16f else 0.07f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height * 1.02f),
                        radius = size.height * 0.62f,
                    ),
                    radius = size.height * 0.62f,
                    center = Offset(size.width / 2f, size.height * 1.02f),
                )
            },
    )
}

@Composable
fun BlurredBackdrop(
    art: Any?,
    modifier: Modifier = Modifier,
    blur: Dp = 80.dp,
    artAlpha: Float = 0.5f,
    scrimTop: Float = 0.86f,
    scrimMid: Float = 0.94f,
    scrimBottom: Float = 0.98f,
) {
    val palette = LocalOnyx.current
    val base = palette.black
    Box(modifier = modifier.fillMaxSize().background(base)) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                    }
                    .blur(blur),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = artAlpha * if (palette.isLight) 0.55f else 1f,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to base.copy(alpha = scrimTop),
                        0.35f to base.copy(alpha = scrimMid),
                        0.62f to base.copy(alpha = scrimMid),
                        1f to base.copy(alpha = scrimBottom),
                    ),
                ),
        )
    }
}
