package app.onyx.buds

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import app.onyx.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BudsClient(
    private val context: Context,
    private val onState: (BudsState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var pendingMode: Int? = null
    @Volatile private var pendingOneEar: Boolean? = null
    @Volatile private var pendingAncHigh: Boolean? = null

    private var last = BudsState()

    fun start() {
        if (running && job?.isActive == true) return
        running = true
        job?.cancel()
        job = scope.launch { loop() }
    }

    fun stop() {
        running = false
        closeSocket()
        job?.cancel()
        scope.cancel()
    }

    fun reconnect() {
        closeSocket()
        if (!running) {
            start()
        } else {
            job?.cancel()
            job = scope.launch { loop() }
        }
    }

    fun setMode(mode: Int) {
        pendingMode = mode
        if (last.connected) {
            send(BudsProtocol.ID_NOISE_CONTROLS, byteArrayOf(mode.toByte()))
            mutate { it.copy(mode = mode) }
        }
    }

    fun setOneEar(enabled: Boolean) {
        pendingOneEar = enabled
        if (last.connected) {
            send(BudsProtocol.ID_SET_ANC_ONE_EAR, byteArrayOf(if (enabled) 1 else 0))
            mutate { it.copy(oneEar = enabled) }
        }
    }

    fun setAncLevel(high: Boolean) {
        pendingAncHigh = high
        if (last.connected) {
            send(BudsProtocol.ID_ANC_LEVEL, byteArrayOf(if (high) 1 else 0))
            mutate { it.copy(ancHigh = high) }
        }
    }

    private suspend fun loop() {
        try {
            val device = findBudsDevice()
            if (device == null) {
                mutate {
                    it.copy(
                        connected = false,
                        connecting = false,
                        error = "Buds не найдены. Проверь сопряжение.",
                    )
                }
                return
            }
            mutate { it.copy(connecting = true, connected = false, error = null) }
            adapter?.cancelDiscovery()
            val sock = connectSocket(device)
            socket = sock
            output = sock.outputStream
            mutate {
                it.copy(
                    connected = true,
                    connecting = false,
                    name = device.name ?: "Galaxy Buds",
                    error = null,
                )
            }
            send(
                BudsProtocol.ID_MANAGER_INFO,
                byteArrayOf(1, 1, 34),
            )
            pendingMode?.let {
                send(BudsProtocol.ID_NOISE_CONTROLS, byteArrayOf(it.toByte()))
                pendingMode = null
            }
            pendingOneEar?.let {
                send(BudsProtocol.ID_SET_ANC_ONE_EAR, byteArrayOf(if (it) 1 else 0))
                pendingOneEar = null
            }
            pendingAncHigh?.let {
                send(BudsProtocol.ID_ANC_LEVEL, byteArrayOf(if (it) 1 else 0))
                pendingAncHigh = null
            }
            readLoop(sock.inputStream)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "buds link lost: ${e.javaClass.simpleName}: ${e.message}", e)
            mutate {
                it.copy(
                    connected = false,
                    connecting = false,
                    error = friendly(e),
                )
            }
        } finally {
            closeSocket()
        }
    }

    private fun readLoop(input: InputStream) {
        var pending = ByteArray(0)
        val buf = ByteArray(4096)
        while (running && scope.isActive) {
            val n = input.read(buf)
            if (n < 0) throw IOException("SPP stream closed")
            if (n == 0) continue
            pending += buf.copyOf(n)
            if (pending.size > 32_768) {
                pending = pending.copyOfRange(pending.size - 8192, pending.size)
            }
            val (frames, consumed) = BudsProtocol.decode(pending)
            if (consumed > 0) {
                pending = if (consumed >= pending.size) ByteArray(0)
                else pending.copyOfRange(consumed, pending.size)
            }
            for (frame in frames) handle(frame)
        }
    }

    private fun handle(frame: BudsProtocol.Frame) {
        if (BuildConfig.DEBUG) Log.d(TAG, "rx id=${frame.id} len=${frame.payload.size}")
        when (frame.id) {
            BudsProtocol.ID_EXTENDED_STATUS -> {
                parseStatus(frame.payload)
                // GalaxyBudsClient answers every status push with manager info; the
                // firmware expects the companion to identify itself.
                send(BudsProtocol.ID_MANAGER_INFO, byteArrayOf(1, 1, 34))
            }
            BudsProtocol.ID_NOISE_UPDATE -> {
                val p = frame.payload
                if (p.isNotEmpty()) {
                    val mode = p[0].toInt() and 0x0F
                    if (mode in 0..3) mutate { it.copy(mode = mode) }
                }
            }
        }
    }

    private fun parseStatus(p: ByteArray) {
        if (p.size < 8) return
        val revision = p[0].toInt() and 0xFF
        val bL = p[2].toInt() and 0xFF
        val bR = p[3].toInt() and 0xFF
        val placement = p[6].toInt() and 0xFF
        val bCase = p[7].toInt() and 0xFF
        val mode = if (p.size > 12) p[12].toInt() and 0x0F else last.mode
        val ancHigh = if (p.size > 24) (p[24].toInt() != 0) else last.ancHigh
        val oneEar = if (p.size > 28) (p[28].toInt() != 0) else last.oneEar
        mutate {
            it.copy(
                revision = revision,
                batteryL = if (bL in 0..100) bL else -1,
                batteryR = if (bR in 0..100) bR else -1,
                // GalaxyBudsClient treats 0 and 101 as "case unavailable/undocked".
                batteryCase = if (bCase in 1..100) bCase else -1,
                wearingL = (placement shr 4) == 1,
                wearingR = (placement and 0x0F) == 1,
                mode = if (mode in 0..3) mode else it.mode,
                ancHigh = ancHigh,
                oneEar = oneEar,
            )
        }
    }

    private fun send(id: Int, payload: ByteArray) {
        val out = output ?: return
        try {
            val data = BudsProtocol.encode(id, payload)
            if (BuildConfig.DEBUG) Log.d(TAG, "tx id=$id len=${payload.size}")
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "tx failed id=$id", e)
            closeSocket()
        }
    }

    private fun mutate(block: (BudsState) -> BudsState) {
        synchronized(this) {
            last = block(last)
            onState(last)
        }
    }

    private fun closeSocket() {
        output = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun findBudsDevice(): BluetoothDevice? {
        val a = adapter ?: return null
        val bonded = try {
            a.bondedDevices
        } catch (_: SecurityException) {
            return null
        }
        return bonded.firstOrNull { it.name?.contains("buds", ignoreCase = true) == true }
    }

    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        val uuid = UUID.fromString(BudsProtocol.SPP_UUID)
        val factories: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(uuid) },
            { device.createInsecureRfcommSocketToServiceRecord(uuid) },
            {
                val m = device.javaClass.getMethod(
                    "createInsecureRfcommSocket",
                    Int::class.javaPrimitiveType,
                )
                m.invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (factory in factories) {
            val s = try {
                factory()
            } catch (e: Exception) {
                lastError = e
                continue
            }
            try {
                s.connect()
                return s
            } catch (e: Exception) {
                lastError = e
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }
        throw lastError ?: IOException("Не удалось открыть SPP-канал")
    }

    private fun friendly(e: Exception): String = when {
        e is SecurityException -> "Нет разрешения Bluetooth"
        e.message?.contains("read failed", true) == true -> "Соединение потеряно"
        e.message?.contains("socket", true) == true -> "Нет SPP-канала (занят Galaxy Wearable?)"
        else -> "Нет связи с Buds"
    }

    companion object {
        private const val TAG = "ONYX_Buds"
    }
}
