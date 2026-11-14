package app.onyx.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.onyx.core.IconSwitcher
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxSurface
import app.onyx.ui.theme.OnyxWhite

/**
 * Icon chooser. The built-in ONYX set is static; the exclusive artist set moves
 * here because the launcher itself cannot animate the shortcut.
 */
@Composable
fun IconPickerDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val spin = rememberInfiniteTransition(label = "exclusive")
    val progress by spin.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "exclusiveProgress",
    )
    val classics = (1 until IconSwitcher.FIRST_EXCLUSIVE).toList()
    val exclusives = (IconSwitcher.FIRST_EXCLUSIVE..IconSwitcher.COUNT).toList()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(OnyxSurface)
                .border(1.dp, OnyxLine, RoundedCornerShape(26.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = tr("Иконка приложения", "App icon"),
                color = OnyxWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr(
                    "Смена иконки может убрать значок с домашнего экрана — добавь ONYX заново из меню приложений.",
                    "Changing the icon may remove it from the home screen — add ONYX again from the app drawer.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GroupLabel(tr("ONYX", "ONYX"))
                }
                items(classics) { index ->
                    MarkTile(
                        index = index,
                        selected = current == index,
                        onClick = { onPick(index) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ExclusiveHeader()
                }
                items(exclusives) { index ->
                    MarkTile(
                        index = index,
                        selected = current == index,
                        progress = progress,
                        onClick = { onPick(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        color = OnyxDim,
        fontSize = 9.sp,
        letterSpacing = 2.2.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ExclusiveHeader() {
    Column(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, OnyxWhite.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "EXCLUSIVE",
                color = OnyxWhite,
                fontSize = 7.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = tr(
                "В коллаборации с художницей",
                "In collaboration with the artist",
            ),
            color = OnyxFaint,
            fontSize = 9.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
private fun MarkTile(
    index: Int,
    selected: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(Color.Black)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) OnyxWhite else OnyxLine,
                    shape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null) {
                ExclusiveMark(
                    index = index,
                    progress = progress,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    painter = painterResource(appIconRes(index)),
                    contentDescription = markCaption(index),
                    modifier = Modifier.fillMaxSize(0.82f),
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = markCaption(index),
                color = if (selected) OnyxWhite else OnyxFaint,
                fontSize = 7.sp,
                maxLines = 1,
            )
        } else {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "%02d".format(index),
                color = if (selected) OnyxWhite else OnyxFaint,
                fontSize = 7.sp,
                maxLines = 1,
            )
        }
    }
}
