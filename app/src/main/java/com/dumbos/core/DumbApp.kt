package com.dumbos.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * dumbOS keeps its process intentionally boring: one channel for its own SMS
 * notice, alarms re-armed, and the saved notification mode re-applied.
 */
class DumbApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.dumbos.ui.Dp.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                SmsNotifications.CHANNEL, "Mensajes dumbOS", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(
                ALARM_CHANNEL, "Alarmas dumbOS", NotificationManager.IMPORTANCE_HIGH))
        }
        Alarms.scheduleAll(this)
    }

    companion object {
        const val ALARM_CHANNEL = "dumbos_alarms"
    }
}
