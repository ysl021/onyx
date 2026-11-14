package app.onyx.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.onyx.AppController
import app.onyx.ui.components.GlassPill

/**
 * One place to look for music that is not in the library: YouTube (with the
 * usual download/cut flow) and open-licence catalogues, where the result is
 * already a direct audio file. Paste-a-link handling stays inside the YouTube
 * section because it shares the extractor and the download queue.
 */
@Composable
fun SearchScreen(
    controller: AppController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = "YouTube",
                selected = section == 0,
                onClick = { section = 0 },
            )
            GlassPill(
                text = tr("Открытые источники", "Open sources"),
                selected = section == 1,
                onClick = { section = 1 },
            )
        }
        Box(Modifier.weight(1f)) {
            if (section == 0) {
                YouTubeScreen(controller = controller, onBack = onBack)
            } else {
                OpenSourcesScreen(controller = controller)
            }
        }
    }
}
