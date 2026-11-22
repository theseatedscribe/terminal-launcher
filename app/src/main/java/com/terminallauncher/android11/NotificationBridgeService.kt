package com.terminallauncher.android11

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationBridgeService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val pkgName = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isNotEmpty() || text.isNotEmpty()) {
            NotificationBus.emit("[$pkgName] $title: $text")
        }
    }
}

object NotificationBus {
    private val listeners = mutableListOf<(String) -> Unit>()

    fun subscribe(cb: (String) -> Unit) {
        listeners.add(cb)
    }

    fun emit(message: String) {
        listeners.forEach { it(message) }
    }
}
