package com.dumbos.apps.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.dumbos.core.Contact
import com.dumbos.core.Contacts
import com.dumbos.core.Sms
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
 * Telefono: contacts first (like the 3310 phone book), a real keypad a key
 * press away, and an in-call screen that owns the screen while a call runs.
 *
 * Views: [CONTACTOS]  -> list of contacts, OK calls, MENU sends SMS
 *        [TECLADO]    -> dial pad, CALL / GUARDAR
 *        [EN LLAMADA] -> current call, loudspeaker and hang up
 */
class PhoneActivity : OsActivity() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private enum class Screen { CONTACTS, KEYPAD, CALL }

    private var screen = Screen.CONTACTS
    private var contacts: List<Contact> = emptyList()
    private var list: PixelList? = null
    private var dialed = ""
    private var keypadView: KeypadView? = null
    private var callTarget: String? = null
    private var callActive = false
    private var callSeconds = 0
    private var inCallUi: InCallView? = null
    private var filter = ""

    override fun buildLayout() {
        titleText = "TELEFONO"
        contacts = Contacts.list(this)
        showContacts()
    }

    private fun clearContent() {
        content.removeAllViews()
        list = null
        keypadView = null
        inCallUi = null
    }

    // -- contacts -----------------------------------------------------------

    private fun filtered(): List<Contact> {
        val f = TYPO.normalize(filter)
        val base = if (filter.isBlank()) contacts else contacts.filter {
            TYPO.normalize(it.name).contains(f) || it.number.replace(" ", "").contains(f)
        }
        return base
    }

    private fun showContacts() {
        screen = Screen.CONTACTS
        clearContent()
        setKeys("LLAMAR", "OPCIONES")
        val col = vcol(this)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val head = PixelText(this, "CONTACTOS ${contacts.size}", Sz.SMALL).also { it.theme = theme; it.colorOverride = theme.dim }
        head.setPadding(Dp.dp(6), Dp.dp(4), Dp.dp(6), Dp.dp(2))
        col.addView(head)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))

        val shown = filtered()
        val empty = PixelText(this, if (contacts.isEmpty()) "SIN CONTACTOS.\nCONCEDE PERMISO O ANADE UN\nNUMERO CON EL TECLADO." else "SIN RESULTADOS",
            Sz.SMALL).also { it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(8), Dp.dp(10), Dp.dp(8), 0) }
        if (shown.isEmpty()) {
            col.addView(empty)
            list = null
            keyTarget = object : com.dumbos.ui.OsView {
                override fun moveSel(delta: Int) = false
                override fun activate(): Boolean { showKeypad(); return true }
                override fun goBack() = false
            }
            return
        }
        val l = PixelList(this, shown.size, Dp.dp(30))
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val c = shown[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dimColor = if (sel) theme.selInk else theme.dim
            TYPO.drawClipped(canvas, c.name, Dp.dp(6), Dp.dp(4), width - Dp.dp(12), Sz.BODY, ink)
            TYPO.drawClipped(canvas, c.number, Dp.dp(6), Dp.dp(4) + Sz.BODY.lineH(), width - Dp.dp(12), Sz.SMALL, dimColor)
        }
        l.onChoose = { i -> call(shown[i].number) }
        list = l
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (filter.isNotEmpty()) {
            val f = PixelText(this, "FILTRO: $filter", Sz.SMALL).also { it.theme = theme; it.colorOverride = theme.dim }
            f.setPadding(Dp.dp(6), Dp.dp(2), Dp.dp(6), Dp.dp(2))
            col.addView(f)
        }
    }

    // -- keypad -------------------------------------------------------------

    private fun showKeypad() {
        screen = Screen.KEYPAD
        clearContent()
        setKeys("LLAMAR", "OPCIONES")
        titleText = "MARCAR"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Dp.dp(8), Dp.dp(4), Dp.dp(8), Dp.dp(4))
        }
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val kp = KeypadView(this, dialed).also { it.theme = theme }
        kp.onDigit = { d -> if (dialed.length < 20) { dialed += d; kp.text = dialed; kp.invalidate() } }
        kp.onDelete = { if (dialed.isNotEmpty()) { dialed = dialed.dropLast(1); kp.text = dialed; kp.invalidate() } }
        kp.onCall = { if (dialed.isNotEmpty()) call(dialed) }
        kp.onDigitLong = { d ->
            val map = mapOf('0' to "+", '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL",
                '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ")
            if (d == '0') { dialed += "+"; kp.text = dialed; kp.invalidate() }
            else map[d]?.let { toast(it) }
        }
        keypadView = kp
        col.addView(kp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int) = false
            override fun activate(): Boolean { kp.onCall?.invoke(); return true }
            override fun goBack(): Boolean { if (dialed.isNotEmpty()) { kp.onDelete?.invoke(); return true }; return false }
        }
    }

    private fun call(number: String) {
        callTarget = number
        callSeconds = 0
        showCallScreen(number)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            callActive = true
        } catch (_: SecurityException) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        } catch (_: Exception) {}
    }

    private fun showCallScreen(number: String) {
        screen = Screen.CALL
        clearContent()
        setKeys("COLGAR", "ALTAVOZ")
        titleText = "LLAMADA"
        val v = InCallView(this, number, Contacts.nameFor(this, number)).also { it.theme = theme }
        inCallUi = v
        content.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int) = false
            override fun activate(): Boolean { endCall(); return true }
            override fun goBack(): Boolean { endCall(); return true }
        }
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (callActive) {
                    callSeconds++
                    v.seconds = callSeconds
                    v.invalidate()
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    private fun endCall() {
        callActive = false
        callTarget = null
        showContacts()
    }

    override fun openMenu() {
        when (screen) {
            Screen.CONTACTS -> {
                showMenu("OPCIONES", listOf(
                    "TECLADO", "BUSCAR CONTACTO", "ENVIAR SMS AL SELECCIONADO", "RECARGAR CONTACTOS"
                )) { i ->
                    when (i) {
                        0 -> showKeypad()
                        1 -> showInput("BUSCAR", "NOMBRE O NUMERO", filter) { t -> filter = t.trim(); showContacts() }
                        2 -> {
                            val shown = filtered()
                            val idx = list?.selected ?: 0
                            if (shown.isNotEmpty()) {
                                val c = shown[idx.coerceIn(0, shown.size - 1)]
                                startActivity(Intent(this, com.dumbos.apps.sms.MessagesActivity::class.java)
                                    .putExtra("number", c.number))
                            }
                        }
                        3 -> { contacts = Contacts.list(this); showContacts(); toast(contacts.size.toString() + " CONTACTOS") }
                    }
                }
            }
            Screen.KEYPAD -> showMenu("OPCIONES", listOf("GUARDAR EN CONTACTOS", "BORRAR TODO", "PEGAR DEL PORTAPAPELES")) { i ->
                when (i) {
                    0 -> if (dialed.isNotEmpty()) saveContact(dialed) else toast("MARCA UN NUMERO")
                    1 -> { dialed = ""; keypadView?.text = ""; keypadView?.invalidate() }
                    2 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                        if (t != null) { dialed = t.toString().filter { d -> d.isDigit() || d == '+' }; keypadView?.text = dialed; keypadView?.invalidate() }
                    }
                }
            }
            Screen.CALL -> {
                catMode = !catMode
                toast(if (catMode) "ALTAVOZ ON" else "ALTAVOZ OFF")
            }
        }
    }

    private var catMode = false

    private fun saveContact(number: String) {
        showInput("NUEVO CONTACTO", "NOMBRE") { name ->
            try {
                val intent = Intent(android.provider.ContactsContract.Intents.Insert.ACTION)
                    .setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name.uppercase())
                startActivity(intent)
            } catch (_: Exception) { toast("NO SE PUDO GUARDAR") }
        }
    }

    override fun finishOs() {
        when (screen) {
            Screen.KEYPAD -> showContacts()
            Screen.CALL -> endCall()
            else -> super.finishOs()
        }
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 12)
        val uri: Uri? = intent?.data
        if (uri != null && intent?.action == Intent.ACTION_DIAL) {
            val num = uri.schemeSpecificPart
            if (!num.isNullOrBlank()) { dialed = num; showKeypad() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        contacts = Contacts.list(this)
        if (screen == Screen.CONTACTS) showContacts()
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}

/**
 * A 3310 keypad: 3x4 chunky keys with the classic letters, drawn with the
 * bitmap font so it looks printed rather than rendered.
 */
class KeypadView(ctx: Context, text0: String) : View(ctx) {

    var theme: Theme = com.dumbos.ui.PALETTES[0]
        set(v) { field = v; invalidate() }
    var text: String = text0
        set(v) { field = v; invalidate() }
    var onDigit: ((Char) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onCall: (() -> Unit)? = null
    var onDigitLong: ((Char) -> Unit)? = null

    private val keys = listOf(
        '1' to "", '2' to "ABC", '3' to "DEF",
        '4' to "GHI", '5' to "JKL", '6' to "MNO",
        '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
        '*' to "+", '0' to "ESP", '#' to ""
    )

    private var downKey = -1
    private var downAt = 0L
    private var longFired = false

    private fun displayRect(): android.graphics.Rect {
        val h = Sz.TITLE.lineH() + Dp.dp(12)
        return android.graphics.Rect(Dp.dp(2), Dp.dp(2), width - Dp.dp(2), Dp.dp(2) + h)
    }

    private fun gridRect(): android.graphics.Rect {
        val top = displayRect().bottom + Dp.dp(8)
        return android.graphics.Rect(Dp.dp(2), top, width - Dp.dp(2), height - Dp.dp(2))
    }

    private fun keyRect(i: Int): android.graphics.Rect {
        val g = gridRect()
        val cw = g.width() / 3
        val ch = g.height() / 4
        val col = i % 3
        val row = i / 3
        val pad = Dp.dp(2)
        return android.graphics.Rect(g.left + col * cw + pad, g.top + row * ch + pad,
            g.left + (col + 1) * cw - pad, g.top + (row + 1) * ch - pad)
    }

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false }
        // display
        val d = displayRect()
        p.color = theme.panel
        canvas.drawRect(d, p)
        p.color = theme.dim
        canvas.drawRect(d.left.toFloat(), d.top.toFloat(), d.right.toFloat(), d.top + 1f, p)
        canvas.drawRect(d.left.toFloat(), (d.bottom - 1).toFloat(), d.right.toFloat(), d.bottom.toFloat(), p)
        val shown = text.ifEmpty { "MARCA UN NUMERO" }
        TYPO.drawClipped(canvas, shown, d.left + Dp.dp(6),
            d.centerY() - Sz.TITLE.lineH() / 2, d.width() - Dp.dp(12), Sz.TITLE,
            if (text.isEmpty()) theme.dim else theme.ink)

        for (i in keys.indices) {
            val r = keyRect(i)
            val pressed = i == downKey
            p.color = if (pressed) theme.ink else theme.panel
            canvas.drawRect(r, p)
            p.color = if (pressed) theme.ink else theme.dim
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.top + 1f, p)
            canvas.drawRect(r.left.toFloat(), (r.bottom - 1).toFloat(), r.right.toFloat(), r.bottom.toFloat(), p)
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.left + 1f, r.bottom.toFloat(), p)
            canvas.drawRect((r.right - 1).toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), p)
            val ink = if (pressed) theme.bg else theme.ink
            val sub = if (pressed) theme.bg else theme.dim
            val (digit, letters) = keys[i]
            if (letters.isEmpty()) {
                TYPO.drawCenter(canvas, digit.toString(), r.centerX(), r.centerY() - Sz.TITLE.lineH() / 2, Sz.TITLE, ink)
            } else {
                TYPO.drawCenter(canvas, digit.toString(), r.centerX(), r.top + Dp.dp(4), Sz.BODY, ink)
                TYPO.drawCenter(canvas, letters, r.centerX(), r.top + Dp.dp(4) + Sz.BODY.lineH() + Dp.dp(1), Sz.SMALL, sub)
            }
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downKey = hit(e.x, e.y)
                downAt = System.currentTimeMillis()
                longFired = false
                invalidate()
                if (downKey >= 0) postDelayed(longPress, 500)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val i = downKey
                downKey = -1
                invalidate()
                if (i >= 0 && !longFired) {
                    val d = keys[i].first
                    if (d == '*') onDelete?.invoke() else onDigit?.invoke(d)
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> { downKey = -1; removeCallbacks(longPress); invalidate(); return true }
        }
        return true
    }

    private val longPress = Runnable {
        if (downKey >= 0) {
            longFired = true
            onDigitLong?.invoke(keys[downKey].first)
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
            invalidate()
        }
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in keys.indices) if (keyRect(i).contains(x.toInt(), y.toInt())) return i
        return -1
    }
}

/** Full-screen call state: who you are talking to and for how long. */
class InCallView(ctx: Context, private val number: String, private val name: String) : View(ctx) {
    var theme: Theme = com.dumbos.ui.PALETTES[0]
        set(v) { field = v; invalidate() }
    var seconds: Int = 0

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false; color = theme.bg }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        val cy = height / 3
        TYPO.iconRect(canvas, "PHONE", com.dumbos.ui.ICON_GLYPHS["PHONE"]!!, (width - 14 * Dp.dp(4)) / 2, cy - Dp.dp(60), Dp.dp(4), theme.ink)
        TYPO.drawCenter(canvas, name, width / 2, cy + Dp.dp(6), Sz.TITLE, theme.ink)
        if (name != number) TYPO.drawCenter(canvas, number, width / 2, cy + Dp.dp(6) + Sz.TITLE.lineH() + Dp.dp(4), Sz.SMALL, theme.dim)
        val t = String.format("%02d:%02d", seconds / 60, seconds % 60)
        TYPO.drawCenter(canvas, t, width / 2, cy + Dp.dp(40) + Sz.TITLE.lineH(), Sz.BODY, theme.dim)
        TYPO.drawCenter(canvas, "EN LLAMADA", width / 2, height - Dp.dp(40), Sz.SMALL, theme.dim)
    }
}
