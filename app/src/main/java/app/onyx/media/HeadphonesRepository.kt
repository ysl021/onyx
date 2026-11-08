package app.onyx.media

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private val gattInFlight = mutableSetOf<String>()

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
            val manager = app.getSystemService(BluetoothManager::class.java)
            runCatching {
                manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
            }.getOrDefault(emptyList()).forEach { readGattBattery(it) }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun readGattBattery(device: BluetoothDevice) {
        val address = device.address ?: return
        if (address in gattInFlight) return
        val name = runCatching { device.name }.getOrNull() ?: return
        if (_batteryByName.value.containsKey(name)) return
        gattInFlight += address
        val gatt = runCatching {
            device.connectGatt(app, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        runCatching { g.discoverServices() }
                    } else {
                        finish(address, g)
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val characteristic = runCatching {
                        g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                    }.getOrNull()
                    if (characteristic == null) {
                        finish(address, g)
                    } else {
                        runCatching { g.readCharacteristic(characteristic) }
                    }
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: android.bluetooth.BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (characteristic.uuid == BATTERY_LEVEL && value.isNotEmpty()) {
                        val level = value[0].toInt() and 0xFF
                        if (level in 0..100) {
                            _batteryByName.value = _batteryByName.value + (name to level)
                            Log.d(TAG, "gatt battery $name=$level")
                        }
                    }
                    finish(address, g)
                }
            })
        }.getOrNull() ?: run {
            gattInFlight -= address
            return
        }
        handler.postDelayed({
            finish(address, gatt)
        }, 8_000L)
    }

    private fun finish(address: String, gatt: BluetoothGatt) {
        gattInFlight -= address
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private const val TAG = "ONYX_Headphones"
        private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
        private val BATTERY_SERVICE: java.util.UUID =
            java.util.UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL: java.util.UUID =
            java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }
}
