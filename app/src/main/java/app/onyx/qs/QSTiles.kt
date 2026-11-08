package app.onyx.qs

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.onyx.buds.BudsService
import app.onyx.buds.BudsState
import app.onyx.ui.tr

class NoiseTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        BudsService.cycle(this)
        updateTile()
    }

    private fun updateTile() {
        val st = BudsService.state.value
        qsTile?.apply {
            label = tr("Шумодав", "Noise control") + " · ${st.modeName}"
            state = if (st.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = when {
                st.connecting -> tr("подключение…", "connecting…")
                st.connected && st.oneEar -> tr("одно ухо", "one ear")
                st.connected -> "Galaxy Buds"
                else -> tr("не подключено", "not connected")
            }
            updateTile()
        }
    }
}

class OneEarTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        BudsService.toggleOneEar(this)
        updateTile()
    }

    private fun updateTile() {
        val st = BudsService.state.value
        qsTile?.apply {
            label = tr("ANC на одно ухо", "ANC one ear")
            state = if (st.connected && st.oneEar) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (st.oneEar) tr("включено", "on") else tr("выключено", "off")
            updateTile()
        }
    }
}
