package app.onyx.media

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Battery levels for generic Bluetooth headphones. Samsung/the AOSP stack emits a
 * hidden BATTERY_LEVEL_CHANGED broadcast for devices exposing the Battery Service;
 * Galaxy Buds use their own SPP values instead.
 */
class HeadphonesRepository(context: Context) {

    private val app = context.applicationContext
    private val _batteryByName = MutableStateFlow<Map<String, Int>>(emptyMap())
    val batteryByName: StateFlow<Map<String, Int>> = _batteryByName.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_BATTERY) return
            val level = intent.getIntExtra(EXTRA_LEVEL, -1)
            if (level !in 0..100) return
            val device = @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DEVICE) as? BluetoothDevice ?: return
            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            } ?: return
            _batteryByName.value = _batteryByName.value + (name to level)
            Log.d(TAG, "battery $name=$level")
        }
    }

    init {
        try {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(ACTION_BATTERY),
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            Log.w(TAG, "battery receiver unavailable", e)
        }
    }

    /** Some stacks only broadcast while a GATT client is connected; ask once on open. */
    fun refresh() {
        if (ContextCompat.checkSelfPermission(
                app,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            for (device in adapter.bondedDevices) {
                val level = runCatching {
                    val m = device.javaClass.getMethod("getBatteryLevel")
                    m.invoke(device) as? Int ?: -1
                }.getOrNull() ?: -1
                if (level in 0..100) {
                    val name = device.name ?: continue
                    _batteryByName.value = _batteryByName.value + (name to level)
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "ONYX_Headphones"
        private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
    }
}
