package com.dumbos.apps.alarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dumbos.core.AlarmReceiver
import com.dumbos.core.Alarms
import com.dumbos.core.Clock
import com.dumbos.core.OsAlarm
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.OsActivity
import com.dumbos.ui.PixelList
import com.dumbos.ui.PixelText
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol
import kotlin.math.max

/**
 * Alarmas y temporizador. Alarms are exact (AlarmManager exact-and-allow-
 * while-idle), repeat by weekday like a real alarm clock, and survive reboot
 * through the boot receiver.
 */
class AlarmActivity : OsActivity() {

    private var list: List<OsAlarm> = emptyList()
    private var timerRunning = false
    private var timerEndsAt = 0L
    private var timerView: TimerStrip? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            timerView?.invalidate()
            if (timerRunning && System.currentTimeMillis() > timerEndsAt) {
                timerRunning = false
                toast("TEMPORIZADOR")
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun buildLayout() {
        titleText = "ALARMAS"
        setKeys("EDITAR", "NUEVA")
        showAlarms()
        handler.post(tick)
    }

    private fun showAlarms() {
        content.removeAllViews()
        list = Alarms.all(this).sortedBy { it.hour * 60 + it.minute }
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val t = TimerStrip(this).also { it.theme = theme; it.get = { timerRunning to timerEndsAt } }
        timerView = t
        // The strip draws a TITLE-size countdown; 38dp clipped it once the type
        // scale grew. Derive the height from the font instead.
        col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Sz.TITLE.lineH() + Dp.dp(16)))
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))

        if (list.isEmpty()) {
            val empty = PixelText(this, "SIN ALARMAS.\n\nPULSA MENU > NUEVA PARA\nPROGRAMAR UNA.", Sz.SMALL).also {
                it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(8), Dp.dp(10), Dp.dp(8), 0)
            }
            col.addView(empty)
            keyTarget = object : com.dumbos.ui.OsView {
                override fun moveSel(delta: Int) = false
                override fun activate(): Boolean { edit(null); return true }
                override fun goBack() = false
            }
            return
        }

        val l = PixelList(this, list.size, com.dumbos.ui.Rows.two())
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val a = list[i]
            val ink = if (sel) theme.selInk else if (a.enabled) theme.ink else theme.dim
            val dim = if (sel) theme.selInk else theme.dim
            TYPO.draw(canvas, String.format("%02d:%02d", a.hour, a.minute), Dp.dp(6), Dp.dp(4), Sz.TITLE, ink)
            TYPO.drawRight(canvas, if (a.enabled) "ON" else "OFF", width - Dp.dp(6), Dp.dp(4), Sz.SMALL, dim)
            TYPO.drawClipped(canvas, a.label, Dp.dp(6), Dp.dp(4) + Sz.TITLE.lineH() + Dp.dp(1), width - Dp.dp(50), Sz.SMALL, dim)
            TYPO.drawRight(canvas, daysLabel(a), width - Dp.dp(6), Dp.dp(4) + Sz.TITLE.lineH() + Dp.dp(1), Sz.SMALL, dim)
        }
        l.onChoose = { i -> edit(list[i]) }
        l.onLongPress = { i ->
            val a = list.getOrNull(i)
            if (a != null) showConfirm("BORRAR", "BORRAR ALARMA " + String.format("%02d:%02d", a.hour, a.minute) + "?", {
                Alarms.delete(this, a.id)
                toast("BORRADA")
                showAlarms()
            })
        }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun daysLabel(a: OsAlarm): String {
        if (a.days.isEmpty()) return "UNA VEZ"
        if (a.days.size == 7) return "DIARIO"
        val names = listOf("LU", "MA", "MI", "JU", "VI", "SA", "DO")
        return a.days.sorted().joinToString(" ") { names[it - 1] }
    }

    override fun openMenu() {
        val sel = (keyTarget as? PixelList)?.selected ?: 0
        val current = list.getOrNull(sel)

        val items = if (current == null) listOf("NUEVA ALARMA", "TEMPORIZADOR", "CUENTA ATRAS")
        else listOf("EDITAR", "ACTIVAR/DESACTIVAR", "BORRAR", "NUEVA ALARMA", "TEMPORIZADOR")
        showMenu("ALARMAS", items) { i ->
            if (current == null) {
                when (i) {
                    0 -> edit(null)
                    1, 2 -> timerDialog()
                }
            } else {
                when (i) {
                    0 -> edit(current)
                    1 -> { Alarms.toggle(this, current.id); showAlarms() }
                    2 -> showConfirm("BORRAR", "BORRAR ALARMA " + String.format("%02d:%02d", current.hour, current.minute) + "?", {
                        Alarms.delete(this, current.id); showAlarms()
                    })
                    3 -> edit(null)
                    4 -> timerDialog()
                }
            }
        }
    }

    private fun edit(existing: OsAlarm?) {
        val h0 = existing?.hour ?: 7
        val m0 = existing?.minute ?: 0
        showInput("HORA (0-23)", "07", String.format("%02d", h0), numeric = true) { hs ->
            val h = hs.toIntOrNull()?.coerceIn(0, 23) ?: return@showInput
            showInput("MINUTOS (0-59)", "00", String.format("%02d", m0), numeric = true) { ms ->
                val m = ms.toIntOrNull()?.coerceIn(0, 59) ?: return@showInput
                chooseDays(existing, h, m)
            }
        }
    }

    private fun chooseDays(existing: OsAlarm?, h: Int, m: Int) {
        val options = listOf("UNA VEZ", "CADA DIA", "LUNES A VIERNES", "FIN DE SEMANA",
            "SOLO LUNES", "SOLO MARTES", "SOLO MIERCOLES", "SOLO JUEVES", "SOLO VIERNES", "SOLO SABADO", "SOLO DOMINGO")
        showMenu("REPETIR", options) { i ->
            val days = when (i) {
                0 -> emptyList<Int>()
                1 -> listOf(1, 2, 3, 4, 5, 6, 7)
                2 -> listOf(1, 2, 3, 4, 5)
                3 -> listOf(6, 7)
                else -> listOf(i - 3)
            }
            showInput("ETIQUETA", "ALARMA", existing?.label ?: "ALARMA") { label ->
                val a = OsAlarm(existing?.id ?: Alarms.newId(), label.ifBlank { "ALARMA" }.uppercase(), h, m, days, true)
                Alarms.upsert(this, a)
                toast("ALARMA " + String.format("%02d:%02d", h, m))
                showAlarms()
            }
        }
    }

    private fun timerDialog() {
        showMenu("TEMPORIZADOR", listOf("1 MIN", "5 MIN", "10 MIN", "15 MIN", "30 MIN", "45 MIN", "60 MIN")) { i ->
            val minutes = listOf(1, 5, 10, 15, 30, 45, 60)[i]
            timerRunning = true
            timerEndsAt = System.currentTimeMillis() + minutes * 60_000L
            Alarms.scheduleTimer(this, minutes * 60, "TEMPORIZADOR $minutes MIN")
            toast("TEMPORIZADOR $minutes MIN")
            showAlarms()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {}
}

/** Countdown strip at the top of the alarms screen. */
class TimerStrip(ctx: Context) : android.view.View(ctx) {
    var theme: Theme = com.dumbos.ui.PALETTES[0]
        set(v) { field = v; invalidate() }
    var get: () -> Pair<Boolean, Long> = { false to 0L }

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false; color = theme.panel }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        val (running, end) = get()
        val p2 = Paint().apply { isAntiAlias = false; color = theme.dim }
        canvas.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), p2)
        if (running) {
            val left = end - System.currentTimeMillis()
            TYPO.draw(canvas, "TEMPORIZADOR", Dp.dp(6), (height - Sz.SMALL.lineH()) / 2, Sz.SMALL, theme.dim)
            TYPO.drawRight(canvas, Alarms.countdownLabel(left), width - Dp.dp(6), (height - Sz.TITLE.lineH()) / 2, Sz.TITLE, theme.ink)
        } else {
            TYPO.draw(canvas, "SIN TEMPORIZADOR ACTIVO", Dp.dp(6), (height - Sz.SMALL.lineH()) / 2, Sz.SMALL, theme.dim)
            TYPO.iconRect(canvas, "TIMER", com.dumbos.ui.ICON_GLYPHS["TIMER"]!!, width - Dp.dp(40), Dp.dp(3), max(1, Dp.dp(2)), theme.dim)
        }
    }
}

/**
 * The ringing screen: full screen, big pixel clock, one soft key to snooze and
 * one to dismiss. Sound and vibration loop until either is used.
 */
class RingingActivity : com.dumbos.ui.OsActivity() {

    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null
    private var label = "ALARMA"
    private var blink = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { blink = !blink; (content.getChildAt(0))?.invalidate(); handler.postDelayed(this, 500) }
    }

    override fun buildLayout() {
        label = intent?.getStringExtra("label") ?: "ALARMA"
        titleText = "ALARMA"
        header.visibility = android.view.View.GONE
        setKeys(com.dumbos.ui.SoftKey("BACK", "POSPONER"), com.dumbos.ui.SoftKey("OK", "PARAR"), com.dumbos.ui.SoftKey("MENU", "POSPONER 10"))
        keyBar.onKey = { k ->
            when (k.id) {
                "OK" -> stopAll()
                else -> snooze()
            }
        }
        val v = object : android.view.View(this) {
            override fun onDraw(canvas: Canvas) {
                val p = Paint().apply { isAntiAlias = false; color = theme.bg }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                val cy = height / 2
                val big = if (blink) theme.ink else theme.dim
                TYPO.drawCenter(canvas, Clock.hm(), width / 2, cy - Sz.HUGE.lineH() * 2 - Dp.dp(10), Sz.HUGE, big)
                TYPO.drawCenter(canvas, label, width / 2, cy, Sz.TITLE, theme.ink)
                TYPO.drawCenter(canvas, Clock.todayLong(), width / 2, cy + Sz.TITLE.lineH() + Dp.dp(8), Sz.SMALL, theme.dim)
                TYPO.iconRect(canvas, "ALARM", com.dumbos.ui.ICON_GLYPHS["ALARM"]!!,
                    width / 2 - 8 * Dp.dp(4), cy - Sz.HUGE.lineH() * 2 - Dp.dp(10) - 16 * Dp.dp(4) - Dp.dp(16), Dp.dp(4), theme.dim)
                TYPO.drawCenter(canvas, "OK PARAR   BACK POSPONER", width / 2, height - Dp.dp(30), Sz.SMALL, theme.dim)
            }
        }
        content.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        startRinging()
        handler.post(tick)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun startRinging() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.audioAttributes = AudioAttributesHolder.alarm()
            ringtone?.play()
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 400, 900), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 600, 400, 600, 400, 900), 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopAll() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        handler.removeCallbacks(tick)
        com.dumbos.core.AlarmNotify.clear(this)
        finish()
    }

    private fun snooze() {
        stopAll()
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(this, 8888,
            android.content.Intent(this, com.dumbos.core.AlarmReceiver::class.java)
                .putExtra("label", label).putExtra("timer", true),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or 0x04000000)
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60_000L, pi)
        } catch (_: Exception) {}
        toast("POSPUESTO 10 MIN")
    }

    override fun finishOs() { snooze() }

    override fun onDestroy() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        handler.removeCallbacks(tick)
        com.dumbos.core.AlarmNotify.clear(this)
        super.onDestroy()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {}
}

object AudioAttributesHolder {
    fun alarm(): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}
