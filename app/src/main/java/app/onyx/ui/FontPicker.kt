package app.onyx.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxSurface
import app.onyx.ui.theme.OnyxWhite
import java.util.concurrent.ConcurrentHashMap

data class FontChoice(
    val id: String,
    val label: String,
    val asset: String?,
)

object FontCatalog {
    val choices = listOf(
        FontChoice("system", "Системный / System", null),
        FontChoice("inter", "Inter", "fonts/inter.ttf"),
        FontChoice("montserrat", "Montserrat", "fonts/montserrat.ttf"),
        FontChoice("oswald", "Oswald", "fonts/oswald.ttf"),
        FontChoice("playfair", "Playfair Display", "fonts/playfair.ttf"),
        FontChoice("bebas", "Bebas Neue", "fonts/bebas.ttf"),
        FontChoice("lora", "Lora", "fonts/lora.ttf"),
        FontChoice("merriweather", "Merriweather", "fonts/merriweather.ttf"),
        FontChoice("spacegrotesk", "Space Grotesk", "fonts/spacegrotesk.ttf"),
        FontChoice("jetbrainsmono", "JetBrains Mono", "fonts/jetbrainsmono.ttf"),
        FontChoice("manrope", "Manrope", "fonts/manrope.ttf"),
    )

    private val cache = ConcurrentHashMap<String, FontFamily>()

    fun family(context: Context, id: String): FontFamily? {
        if (id == "system") return null
        cache[id]?.let { return it }
        val choice = choices.firstOrNull { it.id == id } ?: return null
        val asset = choice.asset ?: return null
        return runCatching {
            val typeface = Typeface.createFromAsset(context.assets, asset)
            FontFamily(typeface).also { cache[id] = it }
        }.getOrNull()
    }
}

@Composable
fun FontPickerDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnyxSurface,
        title = {
            Text(
                tr("Шрифт", "Font"),
                color = OnyxWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            LazyColumn {
                items(FontCatalog.choices, key = { it.id }) { choice ->
                    val family = FontCatalog.family(context, choice.id)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(choice.id)
                                onDismiss()
                            }
                            .padding(vertical = 9.dp),
                    ) {
                        Text(
                            text = choice.label,
                            color = if (selected == choice.id) OnyxWhite else OnyxDim,
                            fontSize = 17.sp,
                            fontWeight = if (selected == choice.id) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = family,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Закрыть", "Close"), color = OnyxWhite)
            }
        },
    )
}
