package com.autosense.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lee las notificaciones de Waze para mostrar la indicación de navegación en el tablero.
 * Su permiso (acceso a notificaciones) también habilita leer la canción que suena
 * en Spotify mediante MediaSessionManager.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        CarBus.connectListeners.toList().forEach { it() }
        activeNotifications?.forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NAV_PACKAGES) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isNotBlank() || text.isNotBlank()) {
            CarBus.navListeners.toList().forEach { it(title, text) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NAV_PACKAGES) CarBus.navListeners.toList().forEach { it("", "") }
    }

    companion object {
        val NAV_PACKAGES = setOf("com.waze", "com.google.android.apps.maps")
    }
}

/** Puente entre el servicio de notificaciones y las vistas del tablero. */
object CarBus {
    val navListeners = mutableSetOf<(title: String, text: String) -> Unit>()
    val connectListeners = mutableSetOf<() -> Unit>()
}
