package app.onyx.media

import android.content.Context
import android.content.Intent
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioRoute(
    val id: String,
    val name: String,
    val description: String = "",
    val selected: Boolean = false,
    val bluetooth: Boolean = false,
    val phone: Boolean = false,
)

class OutputRouter(private val context: Context) {

    private val _routes = MutableStateFlow<List<AudioRoute>>(emptyList())
    val routes: StateFlow<List<AudioRoute>> = _routes.asStateFlow()

    private var latest: Map<String, MediaRoute2Info> = emptyMap()

    private val router: MediaRouter2? = try {
        MediaRouter2.getInstance(context)
    } catch (_: Exception) {
        null
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) = publish(routes)
        override fun onRoutesAdded(routes: List<MediaRoute2Info>) = reload()
        override fun onRoutesRemoved(routes: List<MediaRoute2Info>) = reload()
        override fun onRoutesChanged(routes: List<MediaRoute2Info>) = publish(routes)
    }

    private val controllerCallback = object : MediaRouter2.ControllerCallback() {
        override fun onControllerUpdated(controller: MediaRouter2.RoutingController) = reload()
    }

    init {
        val r = router
        if (r != null) {
            try {
                val prefs = RouteDiscoveryPreference.Builder(
                    listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_REMOTE_PLAYBACK),
                    true,
                ).build()
                r.registerRouteCallback(context.mainExecutor, routeCallback, prefs)
                r.registerControllerCallback(context.mainExecutor, controllerCallback)
                publish(r.routes)
            } catch (_: Exception) {
            }
        }
    }

    private fun reload() {
        val r = router ?: return
        try {
            publish(r.routes)
        } catch (_: Exception) {
        }
    }

    private fun publish(list: List<MediaRoute2Info>) {
        val r = router ?: return
        latest = list.associateBy { it.id }
        val selected = try {
            r.systemController.selectedRoutes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val mapped = list.mapNotNull { info ->
            val name = info.name?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = info.id
            val desc = info.description?.toString().orEmpty()
            val lower = (id + " " + name + " " + desc).lowercase()
            val isPhone = lower.contains("speaker") || lower.contains("phone") ||
                lower.contains("телефон") || lower.contains("this device") ||
                lower.contains("builtin")
            val typeBt = if (android.os.Build.VERSION.SDK_INT >= 34) {
                info.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP ||
                    info.type == MediaRoute2Info.TYPE_BLE_HEADSET ||
                    info.type == MediaRoute2Info.TYPE_HEARING_AID
            } else {
                false
            }
            val isBt = typeBt ||
                lower.contains("bluetooth") || lower.contains("a2dp") ||
                lower.contains("buds") || lower.contains("headset") ||
                lower.contains("наушник")
            AudioRoute(
                id = id,
                name = name,
                description = desc,
                selected = selected.contains(id),
                bluetooth = isBt,
                phone = isPhone,
            )
        }.distinctBy { it.id }
        _routes.value = mapped
    }

    fun select(id: String) {
        val r = router ?: return
        val candidates = buildList {
            latest[id]?.let { add(it) }
            try {
                r.routes.firstOrNull { it.id == id }?.let { if (none { c -> c.id == it.id }) add(it) }
            } catch (_: Exception) {
            }
        }
        for (c in candidates) {
            try {
                r.transferTo(c)
                return
            } catch (_: Exception) {
            }
        }
        openBluetoothSettings()
    }

    fun openBluetoothSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }
}
