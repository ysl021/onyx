package app.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.audio.EqBand
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.OnyxSlider
import app.onyx.ui.components.OnyxSwitch
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite

@Composable
fun EqualizerScreen(
    controller: AppController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eq by controller.sound.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                size = 42.dp,
                iconSize = 20.dp,
                onClick = onBack,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Эквалайзер",
                    color = OnyxWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Звук · ONYX",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                )
            }
        }

        if (!eq.available) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("Недоступно")
                Spacer(Modifier.height(8.dp))
                Text(
                    eq.error ?: "Эквалайзер не поддерживается на этом устройстве.",
                    color = OnyxDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            return@Column
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Включить эквалайзер",
                        color = OnyxWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Общий эффект для активного аудиовыхода",
                        color = OnyxFaint,
                        fontSize = 11.sp,
                    )
                }
                OnyxSwitch(checked = eq.enabled, onCheckedChange = { controller.sound.setEnabled(it) })
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Пресеты")
            Spacer(Modifier.height(10.dp))
            if (eq.presets.isEmpty()) {
                Text("Пресеты не найдены — настрой полосы вручную.", color = OnyxFaint, fontSize = 11.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    eq.presets.chunked(2).forEach { chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunk.forEachIndexed { i, name ->
                                val index = eq.presets.indexOf(name)
                                GlassPill(
                                    text = name,
                                    selected = eq.preset == index,
                                    onClick = { controller.sound.setPreset(index) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (eq.preset < 0) "Режим: своя настройка"
                else "Режим: ${eq.presets.getOrNull(eq.preset) ?: "—"}",
                color = OnyxFaint,
                fontSize = 10.sp,
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Полосы")
            Spacer(Modifier.height(6.dp))
            eq.bands.forEach { band ->
                BandSlider(
                    band = band,
                    min = eq.minLevel,
                    max = eq.maxLevel,
                    onChange = { controller.sound.setBand(band.index, it, persist = false) },
                    onFinish = { controller.sound.persistNow() },
                )
            }
        }
    }
}

@Composable
private fun BandSlider(
    band: EqBand,
    min: Short,
    max: Short,
    onChange: (Short) -> Unit,
    onFinish: () -> Unit,
) {
    val span = (max - min).coerceAtLeast(1)
    val value = ((band.level - min).toFloat() / span).coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (band.freqHz >= 1000) "${band.freqHz / 1000}.${(band.freqHz % 1000) / 100} кГц"
                else "${band.freqHz} Гц",
                color = OnyxDim,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%+.1f дБ".format(band.level / 100f),
                color = OnyxWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        OnyxSlider(
            value = value,
            onValueChange = { v ->
                val level = (min + (v * span).toInt()).coerceIn(min.toInt(), max.toInt()).toShort()
                onChange(level)
            },
            onValueChangeFinished = onFinish,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
