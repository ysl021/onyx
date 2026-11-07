package app.onyx.qs

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.onyx.buds.BudsService
import app.onyx.buds.BudsState

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
            label = "Шумодав · ${st.modeName}"
            state = if (st.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = when {
                st.connecting -> "подключение…"
                st.connected && st.oneEar -> "одно ухо"
                st.connected -> "Galaxy Buds"
                else -> "не подключено"
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
            label = "ANC на одно ухо"
            state = if (st.connected && st.oneEar) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (st.oneEar) "включено" else "выключено"
            updateTile()
        }
    }
}
