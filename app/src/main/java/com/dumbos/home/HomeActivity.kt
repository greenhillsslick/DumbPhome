package com.dumbos.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.dumbos.apps.alarm.AlarmActivity
import com.dumbos.apps.browser.BrowserActivity
import com.dumbos.apps.mail.MailActivity
import com.dumbos.apps.notes.NotesActivity
import com.dumbos.apps.phone.PhoneActivity
import com.dumbos.apps.radio.RadioActivity
import com.dumbos.apps.settings.SettingsActivity
import com.dumbos.apps.sms.MessagesActivity
import com.dumbos.apps.whatsapp.WhatsAppActivity
import com.dumbos.core.Alarms
import com.dumbos.core.Clock
import com.dumbos.core.Mail
import com.dumbos.core.Notes
import com.dumbos.core.Prefs
import com.dumbos.core.Signal
import com.dumbos.core.Sms
import com.dumbos.ui.Dp
import com.dumbos.ui.PALETTES
import com.dumbos.ui.PixelList
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import kotlin.math.max
import kotlin.math.min

/**
 * The dumbOS home screen: a clock, your day at a glance and six app tiles.
 *
 *   FOSFORO VERDE    [bars]           <- status strip
 *   +------------------------------+
 *   |                              |
 *   |   09:41                      |
 *   |   LUNES 23 NOV               |
 *   |   ----------------------------|
 *   |   [PHONE]     MAMA, 2 MSG    |
 *   |   [SMS]       2 SIN LEER     |
 *   |   [MAIL]      1 SIN LEER     |
 *   |   [NOTES]     3 NOTAS        |
 *   |   ----------------------------|
 *   |   ALARMA 06:30                |
 *   +------------------------------+
 *   |  APPS   |   OK   |  MENU     |
 *   +------------------------------+
 */
class HomeActivity : com.dumbos.ui.OsActivity() {

    private lateinit var clockView: ClockView
    private lateinit var tiles: PixelList
    private lateinit var telem: TelemetryView
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var unreadSms = 0
    private var unreadMail = 0
    private var nextAlarm: String? = null

    private data class Tile(val icon: String, val label: String, val badge: String, val open: Class<*>)

    private var rowTiles: List<Tile> = emptyList()

    private fun buildTiles(): List<Tile> = listOf(
        Tile("PHONE", "TELEFONO", "", PhoneActivity::class.java),
        Tile("SMS", "MENSAJES", if (unreadSms > 0) "$unreadSms SIN LEER" else "AL DIA", MessagesActivity::class.java),
        Tile("MAIL", "CORREO", if (unreadMail > 0) "$unreadMail SIN LEER" else "AL DIA", MailActivity::class.java),
        Tile("NOTES", "NOTAS", countNotes(), NotesActivity::class.java),
        Tile("BROWSER", "WEB TEXTO", "", BrowserActivity::class.java),
        Tile("WHATSAPP", "WHATSAPP", bridgeStatus(), WhatsAppActivity::class.java),
        Tile("RADIO", "RADIO FM", radioStatus(), RadioActivity::class.java),
        Tile("ALARM", "ALARMAS", nextAlarm ?: "SIN ALARMAS", AlarmActivity::class.java),
    )

    private fun countNotes(): String {
        val n = Notes.all(this).size
        return "$n " + (if (n == 1) "NOTA" else "NOTAS")
    }

    private fun bridgeStatus(): String = if (com.dumbos.core.Bridge.listenerConnected(this)) "CONECTADO" else "ACTIVAR"

    private fun radioStatus(): String = "FM 87.6 MHZ"

    override fun buildLayout() {
        titleText = "DUMBOS"
        header.rightText = theme.name
        header.showSignal = true
        setKeys(
            com.dumbos.ui.SoftKey("BACK", "APPS"),
            com.dumbos.ui.SoftKey("OK", "ABRIR"),
            com.dumbos.ui.SoftKey("MENU", "MENU"),
        )
        header.onMenu = { openMenu() }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Dp.dp(10), Dp.dp(4), Dp.dp(10), 0)
        }
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        clockView = ClockView(this).also {
            it.theme = theme
            // Holding the big clock is the phone's "home" gesture: it is where a
            // user naturally presses to choose which launcher owns the screen.
            it.isClickable = true
            it.isLongClickable = true
            it.setOnLongClickListener {
                chooseLauncher()
                true
            }
        }
        col.addView(clockView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        telem = TelemetryView(this).also { it.theme = theme }
        col.addView(telem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        reload()
        tiles = PixelList(this, buildTiles().size, com.dumbos.ui.Rows.two()).also { list ->
            list.theme = theme
            list.paintRow = { canvas, i, sel, rowH ->
                val t = rowTiles[i]
                val ink = if (sel) theme.selInk else theme.ink
                val dim = if (sel) theme.selInk else theme.dim
                val iconPx = max(1, Dp.dp(2))
                TYPO.iconRect(canvas, t.icon, ICON_ROWS(t.icon), Dp.dp(2), (rowH - 16 * iconPx) / 2, iconPx, ink)
                // Centred as a two-line block, so a taller row never leaves a
                // gap at the top and never pushes the badge into the next row.
                val block = Sz.BODY.lineH() + Dp.dp(2) + Sz.SMALL.lineH()
                val top = (rowH - block) / 2
                TYPO.draw(canvas, t.label, Dp.dp(40), top, Sz.BODY, ink)
                TYPO.drawClipped(canvas, t.badge, Dp.dp(40), top + Sz.BODY.lineH() + Dp.dp(2),
                    width - Dp.dp(50), Sz.SMALL, dim)
                if (sel) TYPO.draw(canvas, "[RIGHT]", width - Dp.dp(44), (rowH - Sz.SMALL.lineH()) / 2, Sz.SMALL, ink)
            }
            list.onChoose = { i -> startActivity(Intent(this, rowTiles[i].open)) }
        }.apply { loop = false }
        keyTarget = tiles
        col.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // drag down on the wallpaper opens the app list, like a 3310 left key
        setKeys(
            com.dumbos.ui.SoftKey("BACK", "APPS"),
            com.dumbos.ui.SoftKey("OK", "ABRIR"),
            com.dumbos.ui.SoftKey("MENU", "MENU"),
        )
    }

    private fun ICON_ROWS(name: String) = com.dumbos.ui.ICON_GLYPHS[name] ?: com.dumbos.ui.ICON_GLYPHS["APPS"]!!

    override fun openMenu() {
        val items = listOf(
            "TODAS LAS APPS",
            "BUSCAR APP",
            "AUSENTE / SILENCIO",
            "NO MOLESTAR",
            "TODO",
            "AJUSTES DUMBOS",
        )
        showMenu("MENU", items) { i ->
            when (i) {
                0 -> showAllApps()
                1 -> showSearch()
                2 -> setMode("SILENCIO")
                3 -> setMode("NO MOLESTAR")
                4 -> setMode("TODO")
                5 -> openApp(SettingsActivity::class.java)
            }
        }
    }

    private fun showAllApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        val entries = all.filter { it.activityInfo.packageName != packageName }
            .map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.first.lowercase() }
        if (entries.isEmpty()) { toast("NO HAY OTRAS APPS"); return }
        showMenu("APPS DEL SISTEMA", entries.map { it.first.uppercase() }) { i ->
            val e = entries[i]
            startActivity(Intent().setClassName(e.second, e.third).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun showSearch() {
        showInput("BUSCAR APP", "NOMBRE") { text ->
            val q = text.trim().lowercase()
            if (q.isEmpty()) return@showInput
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val hit = @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
                .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            if (hit == null) toast("NO ENCONTRADA") else startActivity(
                Intent().setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun setMode(mode: String) {
        val p = Prefs(this)
        p.daysMode = mode
        com.dumbos.core.NotifMode.apply(this)
        toast(mode)
        telem.invalidate()
        reload()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        ticker = object : Runnable {
            override fun run() {
                clockView.invalidate()
                telem.invalidate()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(ticker!!)
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == "com.dumbos.UNREAD_CHANGED" || i?.action == "android.provider.Telephony.SMS_RECEIVED") {
                    reload()
                }
            }
        }
        registerReceiver(smsReceiver, IntentFilter().apply {
            addAction("com.dumbos.UNREAD_CHANGED")
            addAction("android.provider.Telephony.SMS_RECEIVED")
        })
    }

    /**
     * Asks the system to make dumbOS the home app.
     *
     * The role manager shows Android's own confirmation dialog with dumbOS
     * pre-selected, which is as close to "choose your launcher" as an app is
     * allowed to get; on older releases it falls back to the Home settings page.
     */
    private fun chooseLauncher() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rm = getSystemService(ROLE_SERVICE) as android.app.role.RoleManager
                if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                    !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(
                        rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 7001
                    )
                    return
                }
            } catch (_: Exception) {
                // fall through to the settings page
            }
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("AJUSTES NO DISPONIBLES")
        }
    }

    private fun reload() {
        rowTiles = buildTiles()
        unreadSms = Sms.unreadCount(this)
        unreadMail = Mail.unread(this)
        val a = Alarms.all(this).firstOrNull { it.enabled }
        nextAlarm = a?.let { "PROX " + String.format("%02d:%02d", it.hour, it.minute) }
        if (::tiles.isInitialized) {
            tiles.invalidate()
        }
        if (::telem.isInitialized) telem.invalidate()
    }

    override fun finishOs() { showAllApps() }

    override fun onDestroy() {
        ticker?.let { handler.removeCallbacks(it) }
        smsReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        reload()
        com.dumbos.core.Alarms.scheduleAll(this)
    }
}

/** Big 3310 clock with the date under it. */
class ClockView(ctx: Context) : View(ctx) {
    var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val ww = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) 320 else MeasureSpec.getSize(w)
        val unit = max(1, (ww * 0.80f / 29f).toInt())
        setMeasuredDimension(resolveSize(0, w), Dp.dp(2) + 7 * unit + Dp.dp(7) + Sz.SMALL.lineH() + Dp.dp(4))
    }

    override fun onDraw(canvas: Canvas) {
        val time = Clock.hm()
        // One "unit" is the width of a font pixel: glyphs advance 6 units and a
        // space 3, minus the trailing gap of the last glyph.
        val glyphUnits = TYPO.normalize(time).fold(0) { acc: Int, c: Char -> acc + if (c == ' ') 3 else 6 } - 1
        val unit = max(1, (width * 0.80f / glyphUnits.toFloat()).toInt())
        val tw = glyphUnits * unit
        // Centre on whole pixels: a half-pixel offset blurs vertical strokes.
        val x = ((width - tw) / 2).toInt()
        drawBig(canvas, time, x, Dp.dp(2), unit, theme.ink)
        // Small glyphs are taller now, so the date needs its own real gap or it
        // sits on the descender of the clock.
        TYPO.drawCenter(canvas, Clock.today(), width / 2, Dp.dp(2) + 7 * unit + Dp.dp(7), Sz.SMALL, theme.dim)
    }

    /** Renders the clock well past any Sz by scaling glyph rects. */
    private fun drawBig(canvas: Canvas, text: String, x0: Int, y0: Int, unit: Int, color: Int) {
        // A whole-number unit is mandatory: a 4.6 px font pixel cannot land on
        // the screen grid, and Android blurs whatever falls between pixels.
        val p = com.dumbos.ui.Px.blockPaint(color)
        var x = x0
        for (c in TYPO.normalize(text)) {
            if (c == ' ') { x += 3 * unit; continue }
            val rows = com.dumbos.ui.BIG_GLYPHS[c.toString()] ?: com.dumbos.ui.symbolGlyph(c) ?: continue
            val bmp = com.dumbos.ui.Px.glyph("b" + c.code, rows)
            canvas.drawBitmap(bmp, null, android.graphics.Rect(x, y0, x + rows[0].length * unit, y0 + rows.size * unit), p)
            x += 6 * unit
        }
    }
}

/** The "energy" line under the clock: mode, next alarm and counters. */
class TelemetryView(ctx: Context) : View(ctx) {
    var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(0, w), Dp.dp(44))
    }

    override fun onDraw(canvas: Canvas) {
        val p = Prefs(context)
        val mode = when (p.daysMode) {
            com.dumbos.core.NotifMode.DND -> "NO MOLESTAR"
            com.dumbos.core.NotifMode.ALL -> "TODO"
            else -> "SILENCIO"
        }
        val inbox = Sms.unreadCount(context) + Mail.unread(context)
        val a = Alarms.all(context).firstOrNull { it.enabled }
        val next = a?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: "--:--"
        val iconPx = max(1, Dp.dp(2))
        var x = Dp.dp(2)
        val row1 = Dp.dp(1)
        TYPO.iconRect(canvas, "BELL", com.dumbos.ui.SYMBOL_GLYPHS["BELL"]!!, x, row1, iconPx, if (p.daysMode == "TODO") theme.ink else theme.dim)
        x += 22 * iconPx
        TYPO.draw(canvas, mode, x, row1 - Dp.dp(1), Sz.SMALL, theme.ink)
        TYPO.drawRight(canvas, "ALARMA " + next, width - Dp.dp(4), row1 - Dp.dp(1), Sz.SMALL, theme.dim)

        val row2 = row1 + Sz.SMALL.lineH() + Dp.dp(6)
        TYPO.iconRect(canvas, "ENVELOPE", com.dumbos.ui.SYMBOL_GLYPHS["ENVELOPE"]!!, Dp.dp(2), row2, iconPx, theme.dim)
        TYPO.draw(canvas, if (inbox > 0) "$inbox PENDIENTES" else "TODO LEIDO", Dp.dp(2) + 22 * iconPx, row2 - Dp.dp(1), Sz.SMALL, theme.dim)
        val s = Signal.snapshot(context)
        TYPO.drawRight(canvas, "BATERIA " + s.battery + "%", width - Dp.dp(4), row2 - Dp.dp(1), Sz.SMALL, theme.dim)
    }
}
