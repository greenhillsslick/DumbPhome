package com.dumbos.core

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * dumbOS notification bridge.
 *
 * A 3310 does not have a notification shade: it has one line of text. This
 * service watches the system shade, keeps a trimmed inbox of messaging-app
 * notifications (WhatsApp, Signal, Telegram...) and the WhatsApp bridge app
 * renders it as plain text conversations.
 *
 * Results are cached in SharedPreferences so the bridge works even when the
 * user has not granted notification access yet.
 */
data class BridgedNotification(
    val id: String,
    val app: String,
    val title: String,
    val text: String,
    val millis: Long,
    val conversation: String,
)

object Bridge {
    private const val STORE = "dumbos.bridge"
    private const val MAX = 200

    /** Packages whose notifications are considered "messages". */
    val MESSAGING = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.snapchat.android",
    )

    fun listenerConnected(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { it.contains(ctx.packageName) }
    }

    fun requestAccess(ctx: Context) {
        try {
            ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    fun all(ctx: Context): List<BridgedNotification> {
        val raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("json", "") ?: ""
        if (raw.isBlank()) return seed()
        val out = ArrayList<BridgedNotification>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(BridgedNotification(o.getString("id"), o.optString("app"), o.optString("title"),
                    o.optString("text"), o.optLong("millis"), o.optString("conversation")))
            }
        }
        return out.sortedBy { it.millis }
    }

    fun save(ctx: Context, list: List<BridgedNotification>) {
        val arr = JSONArray()
        for (n in list.takeLast(MAX)) {
            arr.put(JSONObject().apply {
                put("id", n.id); put("app", n.app); put("title", n.title); put("text", n.text)
                put("millis", n.millis); put("conversation", n.conversation)
            })
        }
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("json", arr.toString()).apply()
    }

    fun push(ctx: Context, n: BridgedNotification) {
        val list = all(ctx).toMutableList()
        list.removeAll { it.id == n.id }
        list.add(n)
        save(ctx, list)
        ctx.sendBroadcast(Intent("com.dumbos.BRIDGE_CHANGED").setPackage(ctx.packageName))
    }

    fun conversations(ctx: Context): Map<String, List<BridgedNotification>> =
        all(ctx).filter { it.id != "seed" }.groupBy { it.conversation }

    fun clear(ctx: Context) = save(ctx, emptyList())

    /** Sample bridge traffic so the UI can be explored before access is granted. */
    fun seed(): List<BridgedNotification> {
        val now = System.currentTimeMillis()
        return listOf(
            BridgedNotification("s1", "WhatsApp", "MAMA", "TE HAS DEJADO LAS LLAVES EN CASA", now - 1000 * 60 * 5, "MAMA"),
            BridgedNotification("s2", "WhatsApp", "MAMA", "ABREME QUE ESTOY EN LA PUERTA", now - 1000 * 60 * 3, "MAMA"),
            BridgedNotification("s3", "Telegram", "BANDA DEL BARRIO", "ENSAYO EL JUEVES 19:00", now - 1000 * 60 * 60 * 2, "BANDA DEL BARRIO"),
        )
    }

    /** Best-effort label for an app package. */
    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** Collapses a notification's extended text into one paragraph. */
    fun flatten(n: Notification): String {
        val parts = ArrayList<String>()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (title != null) parts.add(title)
        if (text != null) parts.add(text)
        if (big != null && big != text) parts.add(big)
        return parts.joinToString(" - ").trim()
    }
}

/**
 * Runs as a bound notification listener. It does not post anything itself; it
 * trims the shade down to plain text for the bridge inbox.
 */
class DumbNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        if (!Bridge.MESSAGING.contains(n.packageName)) return
        val title = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isBlank()) return
        val conversation = n.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: title.ifBlank { Bridge.appLabel(this, n.packageName) }
        Bridge.push(this, BridgedNotification(
            id = n.key ?: UUID.randomUUID().toString(),
            app = Bridge.appLabel(this, n.packageName),
            title = title,
            text = text,
            millis = n.postTime,
            conversation = conversation.uppercase(),
        ))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("dumbOS", "notification bridge connected")
        sendBroadcast(Intent("com.dumbos.BRIDGE_CHANGED").setPackage(packageName))
    }
}

// ---------------------------------------------------------------------------
// Receivers
// ---------------------------------------------------------------------------
class AlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val isTimer = intent.getBooleanExtra("timer", false)
        val label = intent.getStringExtra("label") ?: if (isTimer) "TEMPORIZADOR" else "ALARMA"
        val alarmId = intent.getStringExtra("alarmId")
        if (!isTimer && alarmId != null) {
            val a = Alarms.all(ctx).firstOrNull { it.id == alarmId }
            // reschedule the next repetition before waking the user
            if (a != null && a.days.isNotEmpty()) Alarms.schedule(ctx, a)
            if (a != null && a.days.isEmpty()) {
                Alarms.save(ctx, Alarms.all(ctx).map { if (it.id == a.id) it.copy(enabled = false) else it })
            }
        }
        AlarmNotify.show(ctx, label)
    }
}

class SmsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.sendBroadcast(Intent("com.dumbos.UNREAD_CHANGED").setPackage(ctx.packageName))
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(900, SmsNotifications.build(ctx))
        } catch (_: Exception) {}
    }
}

object SmsNotifications {
    const val CHANNEL = "dumbos_sms"

    fun build(ctx: Context): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL, "Mensajes dumbOS", android.app.NotificationManager.IMPORTANCE_HIGH))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        return b.setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("MENSAJE")
            .setContentText("NUEVO MENSAJE EN DUMBOS")
            .setWhen(System.currentTimeMillis())
            .build()
    }
}

/**
 * Since Android 10 an app in the background cannot simply start an activity.
 * A full-screen notification is the sanctioned path: the system shows it even
 * over the lock screen, and RingingActivity is launched by the user's tap (or
 * by the system when the device is idle).
 */
object AlarmNotify {
    const val CHANNEL = "dumbos_alarms"
    const val ID = 4242

    fun show(ctx: Context, label: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL, "Alarmas dumbOS", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        val full = android.app.PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, com.dumbos.apps.alarm.RingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("label", label),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("TOCA PARA PARAR")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC)
        nm.notify(ID, b.build())
    }

    fun clear(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(ID)
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alarms.scheduleAll(ctx)
        NotifMode.apply(ctx)
    }
}
