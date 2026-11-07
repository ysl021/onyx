package app.onyx.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onyx.R
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.delay

@Composable
fun IntroScreen(onFinish: () -> Unit) {
    val scale = remember { Animatable(0.72f) }
    val alpha = remember { Animatable(0f) }
    val spacing = remember { Animatable(30f) }
    val exit = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(420))
    }
    LaunchedEffect(Unit) {
        spacing.animateTo(7f, tween(950, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        glow.animateTo(1f, tween(900))
        delay(650)
        exit.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnyxBlack)
            .graphicsLayer { this.alpha = 1f - exit.value }
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
                    val s = scale.value * (1f + 0.28f * exit.value)
                    scaleX = s
                    scaleY = s
                    this.alpha = alpha.value
                },
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = OnyxWhite.copy(alpha = 0.35f + 0.65f * alpha.value),
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "ONYX",
                color = OnyxWhite,
                fontSize = 50.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = spacing.value.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "music control center",
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
            )
        }
    }
}
