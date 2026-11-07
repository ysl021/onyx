package app.onyx.buds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.onyx.MainActivity
import app.onyx.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BudsService : Service() {

    private var client: BudsClient? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(state.value))
        } catch (_: Exception) {
            stopSelf()
            return
        }
        client = BudsClient(this) { st ->
            _state.value = st
            notifyState(st)
            // Do not keep a foreground notification (and a reconnect loop) alive
            // when the Buds are off/unreachable. The user starts it again with
            // the "Подключить Buds" button.
            if (!st.connected && !st.connecting && st.error != null) {
                handler.postDelayed({ stopSelf() }, 900)
            }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacksAndMessages(null)
        when (intent?.action) {
            null, ACTION_START -> {
                if (state.value.error != null || (!state.value.connected && !state.value.connecting)) {
                    client?.reconnect()
                }
            }
            ACTION_SET_MODE -> client?.setMode(intent.getIntExtra(EXTRA_MODE, BudsState.MODE_ANC))
            ACTION_ONE_EAR -> client?.setOneEar(intent.getBooleanExtra(EXTRA_ON, true))
            ACTION_TOGGLE_ONE_EAR -> client?.setOneEar(!state.value.oneEar)
            ACTION_ANC_HIGH -> client?.setAncLevel(intent.getBooleanExtra(EXTRA_ON, true))
            ACTION_CYCLE -> {
                val next = when (state.value.mode) {
                    BudsState.MODE_OFF -> BudsState.MODE_ANC
                    BudsState.MODE_ANC -> BudsState.MODE_AMBIENT
                    BudsState.MODE_AMBIENT -> BudsState.MODE_ADAPTIVE
                    else -> BudsState.MODE_OFF
                }
                client?.setMode(next)
            }
            ACTION_RECONNECT -> {
                handler.removeCallbacksAndMessages(null)
                client?.reconnect()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client?.stop()
        client = null
        _state.value = _state.value.copy(connected = false, connecting = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ONYX · наушники", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Состояние наушников и шумоподавления"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(st: BudsState): Notification {
        val text = when {
            st.connected -> buildString {
                append(st.modeName)
                if (st.oneEar) append(" · одно ухо")
                if (st.batteryL >= 0) append(" · L ${st.batteryL}%")
                if (st.batteryR >= 0) append(" · R ${st.batteryR}%")
                if (st.batteryCase >= 0) append(" · кейс ${st.batteryCase}%")
            }
            st.connecting -> "Подключение к наушникам…"
            else -> st.error ?: "Ожидание наушников"
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_onyx)
            .setContentTitle("ONYX · ${st.name}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyState(st: BudsState) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(st))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_START = "app.onyx.buds.START"
        const val ACTION_SET_MODE = "app.onyx.buds.SET_MODE"
        const val ACTION_ONE_EAR = "app.onyx.buds.ONE_EAR"
        const val ACTION_TOGGLE_ONE_EAR = "app.onyx.buds.TOGGLE_ONE_EAR"
        const val ACTION_ANC_HIGH = "app.onyx.buds.ANC_HIGH"
        const val ACTION_CYCLE = "app.onyx.buds.CYCLE"
        const val ACTION_RECONNECT = "app.onyx.buds.RECONNECT"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ON = "on"

        private const val CHANNEL = "onyx_buds"
        private const val NOTIF_ID = 1001

        private val _state = MutableStateFlow(BudsState())
        val state: StateFlow<BudsState> = _state.asStateFlow()

        private fun send(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val i = Intent(context, BudsService::class.java).setAction(action)
            configure(i)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (_: Exception) {
            }
        }

        fun ensure(context: Context) = send(context, ACTION_START)
        fun setMode(context: Context, mode: Int) = send(context, ACTION_SET_MODE) {
            it.putExtra(EXTRA_MODE, mode)
        }
        fun setOneEar(context: Context, on: Boolean) = send(context, ACTION_ONE_EAR) {
            it.putExtra(EXTRA_ON, on)
        }
        fun toggleOneEar(context: Context) = send(context, ACTION_TOGGLE_ONE_EAR)
        fun setAncHigh(context: Context, on: Boolean) = send(context, ACTION_ANC_HIGH) {
            it.putExtra(EXTRA_ON, on)
        }
        fun cycle(context: Context) = send(context, ACTION_CYCLE)
        fun reconnect(context: Context) = send(context, ACTION_RECONNECT)
    }
}
