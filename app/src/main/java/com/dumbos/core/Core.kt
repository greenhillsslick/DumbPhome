package com.dumbos.core

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import com.dumbos.ui.Dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

// ---------------------------------------------------------------------------
// Clock helpers
// ---------------------------------------------------------------------------
object Clock {
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val day = SimpleDateFormat("EEE dd MMM", Locale.getDefault())
    private val full = SimpleDateFormat("EEE dd MMM yyyy", Locale.getDefault())
    private val dayNum = SimpleDateFormat("dd/MM", Locale.getDefault())

    fun hm(): String = hm.format(Date())
    fun hms(): String = hms.format(Date())
    fun today(): String = day.format(Date()).uppercase()
    fun todayLong(): String = full.format(Date()).uppercase()
    fun stamp(millis: Long): String = dayNum.format(Date(millis)) + " " + hm.format(Date(millis))
    fun time(millis: Long): String = hm.format(Date(millis))
    fun millis(hh: Int, mm: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

// ---------------------------------------------------------------------------
// Preferences (SharedPreferences, the only storage dumbOS needs for config)
// ---------------------------------------------------------------------------
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("dumbos", Context.MODE_PRIVATE)

    var silent: Boolean
        get() = sp.getBoolean("silent", false)
        set(v) = sp.edit().putBoolean("silent", v).apply()
    var dnd: Boolean
        get() = sp.getBoolean("dnd", false)
        set(v) = sp.edit().putBoolean("dnd", v).apply()
    /**
     * SILENCIO / NO MOLESTAR / TODO. Defaults to TODO: a launcher should never
     * silently change how the phone behaves before the user asks for it.
     */
    var daysMode: String
        get() = sp.getString("daysMode", NotifMode.ALL) ?: NotifMode.ALL
        set(v) = sp.edit().putString("daysMode", v).apply()
    var greeting: String
        get() = sp.getString("greeting", "HOLA") ?: "HOLA"
        set(v) = sp.edit().putString("greeting", v).apply()
    var weather: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) = sp.edit().putBoolean("weather", v).apply()
    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()
    var bigText: Boolean
        get() = sp.getBoolean("bigText", false)
        set(v) = sp.edit().putBoolean("bigText", v).apply()

    companion object {
        fun themeId(ctx: Context): String =
            ctx.getSharedPreferences("dumbos", Context.MODE_PRIVATE).getString("theme", "PHOSPHOR") ?: "PHOSPHOR"
        fun setTheme(ctx: Context, id: String) {
            ctx.getSharedPreferences("dumbos", Context.MODE_PRIVATE).edit().putString("theme", id).apply()
        }
    }
}

// ---------------------------------------------------------------------------
// Signal / battery
// ---------------------------------------------------------------------------
data class SignalInfo(val bars: Int, val battery: Int, val charging: Boolean)

object Signal {
    fun snapshot(ctx: Context): SignalInfo {
        var bars = 0
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = if (Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(cm.activeNetwork) else null
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                bars = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 4 else 3
            }
        } catch (_: Exception) {}
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return SignalInfo(bars, (level.coerceIn(0, 100)), charging)
    }
}

// ---------------------------------------------------------------------------
// Contacts (read-only view over the system address book)
// ---------------------------------------------------------------------------
data class Contact(val name: String, val number: String)

object Contacts {
    fun list(ctx: Context): List<Contact> {
        val out = ArrayList<Contact>()
        try {
            val cr: ContentResolver = ctx.contentResolver
            val cur: Cursor? = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cur?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: "SIN NOMBRE"
                    val num = it.getString(1) ?: ""
                    if (num.isNotBlank() && out.none { c -> c.name == name && c.number == num }) {
                        out.add(Contact(name, num))
                    }
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
        return out
    }

    fun nameFor(ctx: Context, number: String): String {
        if (number.isBlank()) return ""
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: number
            }
        } catch (_: Exception) {}
        return number
    }
}

// ---------------------------------------------------------------------------
// Messages: the single source of truth for SMS
// ---------------------------------------------------------------------------
data class SmsMessage(val id: Long, val number: String, val body: String, val millis: Long, val incoming: Boolean)

data class Thread(val number: String, val name: String, val last: String, val millis: Long, val unread: Int)

object Sms {
    fun canRead(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    fun canSend(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun unreadCount(ctx: Context): Int {
        if (!canRead(ctx)) return 0
        var n = 0
        try {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id"), "read = 0", null, null)?.use {
                n = it.count
            }
        } catch (_: Exception) {}
        return n
    }

    /** Builds the conversation list from the SMS store (falls back when empty). */
    fun threads(ctx: Context, limit: Int = 60): List<Thread> {
        val out = ArrayList<Thread>()
        if (canRead(ctx)) {
            try {
                val cols = arrayOf("_id", "address", "body", "date", "read", "type")
                ctx.contentResolver.query(Uri.parse("content://sms"), cols, null, null, "date DESC")?.use { cur ->
                    val seen = HashSet<String>()
                    while (cur.moveToNext() && out.size < limit) {
                        val addr = cur.getString(1) ?: continue
                        if (seen.contains(addr)) continue
                        seen.add(addr)
                        val body = cur.getString(2) ?: ""
                        val date = cur.getLong(3)
                        val unread = unreadIn(ctx, addr)
                        out.add(Thread(addr, Contacts.nameFor(ctx, addr), body, date, unread))
                    }
                }
            } catch (_: Exception) {}
        }
        if (out.isEmpty()) out.addAll(Demo.threads)
        return out
    }

    private fun unreadIn(ctx: Context, address: String): Int {
        var n = 0
        try {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id"),
                "address = ? AND read = 0", arrayOf(address), null)?.use { n = it.count }
        } catch (_: Exception) {}
        return n
    }

    fun conversation(ctx: Context, number: String, limit: Int = 400): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        if (canRead(ctx)) {
            try {
                ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id", "address", "body", "date", "type"),
                    "address = ?", arrayOf(number), "date ASC")?.use { cur ->
                    while (cur.moveToNext()) {
                        out.add(SmsMessage(cur.getLong(0), cur.getString(1), cur.getString(2) ?: "",
                            cur.getLong(3), cur.getInt(4) == 1))
                    }
                }
            } catch (_: Exception) {}
        }
        if (out.isEmpty()) out.addAll(Demo.conversation(number))
        return out.takeLast(400)
    }

    fun markRead(ctx: Context, number: String) {
        try {
            val values = android.content.ContentValues().apply { put("read", 1) }
            ctx.contentResolver.update(Uri.parse("content://sms/inbox"), values, "address = ? AND read = 0", arrayOf(number))
        } catch (_: Exception) {}
    }

    fun send(ctx: Context, number: String, body: String): Boolean {
        if (!canSend(ctx)) return false
        return try {
            val sms = android.telephony.SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, body, null, null)
            val values = android.content.ContentValues().apply {
                put("address", number); put("body", body); put("date", System.currentTimeMillis()); put("read", 1)
                put("type", 2)
            }
            ctx.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun watch(ctx: Context, resolver: ContentResolver, onChange: () -> Unit): ContentObserver {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { onChange() }
        }
        resolver.registerContentObserver(Uri.parse("content://sms"), true, obs)
        return obs
    }

    fun dial(ctx: Context, number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun call(ctx: Context, number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (_: Exception) { dial(ctx, number) }
    }
}

/**
 * Canned conversations shown before the device has any messages, so the
 * ecosystem is explorable (and screenshots look alive) on a fresh install.
 */
object Demo {
    val threads = listOf(
        Thread("+34600111222", "MAMA", "LLEGO A CASA A LAS 20:00, NO TARDES", System.currentTimeMillis() - 1000 * 60 * 12, 1),
        Thread("+34611333444", "HUGO", "EL CONCIERTO ERA A LAS 9 NO A LAS 8", System.currentTimeMillis() - 1000 * 60 * 95, 0),
        Thread("+34622555666", "TALLER", "SU BICI ESTA LISTA PARA RECOGER", System.currentTimeMillis() - 1000 * 60 * 60 * 26, 0),
        Thread("+34633777888", "89391", "INFO: SALDO 4,50 EUR. GRACIAS", System.currentTimeMillis() - 1000 * 60 * 60 * 50, 0),
    )

    fun conversation(number: String): List<SmsMessage> {
        val now = System.currentTimeMillis()
        val t = threads.firstOrNull { it.number == number }?.millis ?: now
        return when (number) {
            "+34600111222" -> listOf(
                SmsMessage(1, number, "HOLA, HAS COMPRADO PAN?", t - 900_000, true),
                SmsMessage(2, number, "SI, Y LECHE. LLEGO A LAS 7", t - 600_000, false),
                SmsMessage(3, number, "LLEGO A CASA A LAS 20:00, NO TARDES", t, true),
            )
            "+34611333444" -> listOf(
                SmsMessage(1, number, "QUE HACEMOS EL SABADO?", t - 900_000, true),
                SmsMessage(2, number, "IR A VER LA BANDA DEL BARRIO", t - 600_000, false),
                SmsMessage(3, number, "EL CONCIERTO ERA A LAS 9 NO A LAS 8", t, true),
            )
            else -> listOf(SmsMessage(1, number, threads.firstOrNull { it.number == number }?.last ?: "", t, true))
        }
    }
}

// ---------------------------------------------------------------------------
// Notification mode (silence / DND / everything) enforced by NotifGuard
// ---------------------------------------------------------------------------
object NotifMode {
    const val SILENT = "SILENCIO"
    const val DND = "NO MOLESTAR"
    const val ALL = "TODO"

    fun label(ctx: Context): String = when (Prefs(ctx).daysMode) {
        DND -> DND
        ALL -> "TODO"
        else -> SILENT
    }

    /**
     * True when the bridge should hide a notification coming from another app.
     * In SILENCIO the shade only shows what the user explicitly opened; in
     * TODO everything comes through untouched.
     */
    fun suppress(ctx: Context, pkg: String?, channelImportance: Int = 3): Boolean {
        val p = Prefs(ctx)
        return when (p.daysMode) {
            ALL -> false
            DND -> false // the system filter already silences these
            else -> pkg != null && pkg != ctx.packageName
        }
    }

    /**
     * Asks the system to apply the interruption filter. This needs the
     * notification policy access grant; without it we record the user's choice
     * and the ringer switch below is still applied.
     */
    fun apply(ctx: Context) {
        val p = Prefs(ctx)
        p.silent = p.daysMode == SILENT
        p.dnd = p.daysMode == DND
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            when (p.daysMode) {
                SILENT -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                DND -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                else -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }
}

// ---------------------------------------------------------------------------
// Alarms & timers
// ---------------------------------------------------------------------------
data class OsAlarm(
    val id: String,
    val label: String,
    var hour: Int,
    var minute: Int,
    var days: List<Int>,   // 1..7 = Mon..Sun, empty = one-shot
    var enabled: Boolean,
)

object Alarms {
    private const val STORE = "dumbos.alarms"

    fun all(ctx: Context): List<OsAlarm> {
        val sp = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val raw = sp.getString("json", "[]") ?: "[]"
        val out = ArrayList<OsAlarm>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val days = ArrayList<Int>()
                val d = o.optJSONArray("days") ?: JSONArray()
                for (j in 0 until d.length()) days.add(d.getInt(j))
                out.add(OsAlarm(o.getString("id"), o.optString("label", "ALARMA"),
                    o.getInt("hour"), o.getInt("minute"), days, o.optBoolean("enabled", true)))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<OsAlarm>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("id", a.id); put("label", a.label); put("hour", a.hour); put("minute", a.minute)
                put("days", JSONArray(a.days)); put("enabled", a.enabled)
            })
        }
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("json", arr.toString()).apply()
        scheduleAll(ctx)
    }

    fun upsert(ctx: Context, alarm: OsAlarm) {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) list[idx] = alarm else list.add(alarm)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    fun toggle(ctx: Context, id: String) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun nextOccurrence(a: OsAlarm): Long? {
        if (!a.enabled) return null
        val now = Calendar.getInstance()
        if (a.days.isEmpty()) {
            var cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        for (offset in 0..7) {
            val cal = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            // Calendar.MONDAY..SUNDAY = 2..1 ; store 1..7 Mon..Sun
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val iso = if (dow == Calendar.SUNDAY) 7 else dow - 1
            if (a.days.contains(iso) && cal.timeInMillis > now.timeInMillis) return cal.timeInMillis
        }
        return null
    }

    fun scheduleAll(ctx: Context) {
        for (a in all(ctx)) if (a.enabled) schedule(ctx, a)
    }

    fun schedule(ctx: Context, a: OsAlarm) {
        val at = nextOccurrence(a) ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, a.id.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).putExtra("alarmId", a.id).putExtra("label", a.label),
            PendingIntent.FLAG_UPDATE_CURRENT or 0x04000000)
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun scheduleTimer(ctx: Context, seconds: Int, label: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 424242,
            Intent(ctx, AlarmReceiver::class.java).putExtra("timer", true).putExtra("label", label),
            PendingIntent.FLAG_UPDATE_CURRENT or 0x04000000)
        val at = System.currentTimeMillis() + seconds * 1000L
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun countdownLabel(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}

// ---------------------------------------------------------------------------
// Notes (local JSON store)
// ---------------------------------------------------------------------------
data class Note(val id: String, var title: String, var body: String, var millis: Long)

object Notes {
    private const val STORE = "dumbos.notes"

    fun all(ctx: Context): List<Note> {
        val raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("json", "") ?: ""
        if (raw.isBlank()) return seed()
        val out = ArrayList<Note>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Note(o.getString("id"), o.optString("title"), o.optString("body"), o.optLong("millis")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<Note>) {
        val arr = JSONArray()
        for (n in list) arr.put(JSONObject().apply {
            put("id", n.id); put("title", n.title); put("body", n.body); put("millis", n.millis)
        })
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("json", arr.toString()).apply()
    }

    fun upsert(ctx: Context, n: Note) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == n.id }
        if (i >= 0) list[i] = n else list.add(0, n)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })

    private fun seed() = listOf(
        Note("n1", "COMPRA", "PAN\nLECHE\nHUEVOS\nPLATANOS", System.currentTimeMillis() - 86400000),
        Note("n2", "BICI", "PINCHARON LAS DOS RUEDAS. EL TALLER ABRE A LAS 9.", System.currentTimeMillis() - 3600000),
        Note("n3", "IDEA", "UNA RADIO QUE SOLO SUENE A LAS 7:00 Y DIGA EL TIEMPO.", System.currentTimeMillis() - 600000),
    )
}

// ---------------------------------------------------------------------------
// Mail (local mailbox + optional IMAP)
// ---------------------------------------------------------------------------
data class MailMessage(val uid: String, val from: String, val subject: String, val body: String, val millis: Long, var unread: Boolean)

data class MailAccount(val address: String, val host: String, val port: Int, val user: String, val pass: String, val name: String)

object Mail {
    private const val STORE = "dumbos.mail"

    fun account(ctx: Context): MailAccount? {
        val raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("acct", "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            MailAccount(o.getString("address"), o.getString("host"), o.getInt("port"),
                o.getString("user"), o.getString("pass"), o.optString("name", "BUZON"))
        } catch (_: Exception) { null }
    }

    fun saveAccount(ctx: Context, a: MailAccount?) {
        val e = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        if (a == null) e.remove("acct") else e.putString("acct", JSONObject().apply {
            put("address", a.address); put("host", a.host); put("port", a.port)
            put("user", a.user); put("pass", a.pass); put("name", a.name)
        }.toString())
        e.apply()
    }

    fun all(ctx: Context): List<MailMessage> {
        val raw = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("inbox", "") ?: ""
        if (raw.isBlank()) return demo()
        val out = ArrayList<MailMessage>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(MailMessage(o.getString("uid"), o.optString("from"), o.optString("subject"),
                    o.optString("body"), o.optLong("millis"), o.optBoolean("unread", false)))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<MailMessage>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().apply {
            put("uid", m.uid); put("from", m.from); put("subject", m.subject)
            put("body", m.body); put("millis", m.millis); put("unread", m.unread)
        })
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("inbox", arr.toString()).apply()
    }

    fun unread(ctx: Context): Int = all(ctx).count { it.unread }

    private fun demo(): List<MailMessage> = listOf(
        MailMessage("d1", "BIBLIOTECA", "VENCIMIENTO DE PRESTAMO",
            "EL LIBRO 'EL NOMBRE DE LA ROSA' VENCE EL VIERNES. RECUERDA DEVOLVERLO EN RECEPCION.",
            System.currentTimeMillis() - 7200000, true),
        MailMessage("d2", "TALLER SANJUAN", "BICI LISTA",
            "SU BICICLETA ESTA REPARADA. HORARIO 9:00 A 14:00.\nTOTAL: 18 EUR.",
            System.currentTimeMillis() - 86400000, false),
        MailMessage("d3", "RADIO LOCAL 87.9", "PROGRAMA DE MADRUGADA",
            "ESTA NOCHE A LAS 00:00, SESION DE ROCK EN DIRECTO.\nNO HACE FALTA ENTRADA.",
            System.currentTimeMillis() - 172800000, false),
    )
}

// ---------------------------------------------------------------------------
// A deliberately tiny text-only web
// ---------------------------------------------------------------------------
class MiniWebClient {
    data class Page(val url: String, val title: String, val text: String, val links: List<Pair<String, String>>)

    fun get(url: String): Result<Page> = try {
        val clean = if (!url.startsWith("http")) "https://$url" else url
        val conn = java.net.URL(clean).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "dumbOS/1.0 (text)$")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val html = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) Result.failure(Exception("HTTP $code"))
        else Result.success(parse(conn.url.toString(), html))
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun parse(url: String, html: String): Page {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { unescape(stripTags(it)) }?.trim()?.take(80) ?: url
        val links = ArrayList<Pair<String, String>>()
        val base = url
        val linkRe = Regex("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in linkRe.findAll(html)) {
            val label = unescape(stripTags(m.groupValues[2])).trim().replace(Regex("\\s+"), " ")
            val href = absolutize(base, m.groupValues[1])
            if (label.isNotEmpty() && links.size < 24 && !href.startsWith("javascript")) links.add(href to label)
        }
        val bodyHtml = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<head.*?</head>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h1|h2|h3|h4|h5|h6|blockquote|pre)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "\n- ")
        var text = unescape(stripTags(bodyHtml))
        text = text.replace('\u00A0', ' ')
        text = text.lines().map { it.trim().replace(Regex("[ \\t]+"), " ") }.filter { it.isNotEmpty() }
            .joinToString("\n")
        text = text.replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.length > 12000) text = text.substring(0, 12000) + "..."
        return Page(url, title, text, links)
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), " ")

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&mdash;", "-")
        .replace(Regex("&#(\\d+);")) { m -> String(Character.toChars(m.groupValues[1].toIntOrNull() ?: 63)) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> String(Character.toChars(m.groupValues[1].toIntOrNull(16) ?: 63)) }

    fun absolutize(base: String, href: String): String {
        if (href.startsWith("http")) return href
        return try {
            java.net.URI(base).resolve(href).toString()
        } catch (_: Exception) { href }
    }

    fun host(url: String): String = try {
        java.net.URI(if (url.startsWith("http")) url else "https://$url").host ?: url
    } catch (_: Exception) { url }

}

/** A handful of tiny, text-friendly starting points. */
object WebHome {
    val LINKS = listOf(
        "dumb://inicio" to "INICIO",
        "https://example.com" to "EXAMPLE.COM",
        "https://lite.cnn.com" to "NOTICIAS CNN LITE",
        "https://text.npr.org" to "NPR TEXTO",
        "https://daringfireball.net" to "DARING FIREBALL",
        "https://68k.news" to "68K NEWS",
        "https://en.m.wikipedia.org" to "WIKIPEDIA",
    )
}
