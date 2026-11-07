package app.onyx.media

import android.service.notification.NotificationListenerService

class OnyxNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaRepository.get(this).onListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaRepository.get(this).onListenerDisconnected()
    }
}
