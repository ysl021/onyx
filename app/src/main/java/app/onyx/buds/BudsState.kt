package app.onyx.buds

import app.onyx.ui.tr

data class BudsState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val name: String = "Galaxy Buds",
    val batteryL: Int = -1,
    val batteryR: Int = -1,
    val batteryCase: Int = -1,
    val wearingL: Boolean = false,
    val wearingR: Boolean = false,
    val mode: Int = 0,
    val oneEar: Boolean = false,
    val ancHigh: Boolean = false,
    val revision: Int = 0,
    val error: String? = null,
) {
    val batteryKnown: Boolean get() = batteryL >= 0 || batteryR >= 0
    val modeName: String
        get() = when (mode) {
            MODE_ANC -> "ANC"
            MODE_AMBIENT -> "Ambient"
            MODE_ADAPTIVE -> "Adaptive"
            else -> tr("Выкл", "Off")
        }

    companion object {
        const val MODE_OFF = 0
        const val MODE_ANC = 1
        const val MODE_AMBIENT = 2
        const val MODE_ADAPTIVE = 3
    }
}
