package com.dumbos.apps.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dumbos.core.Clock
import com.dumbos.core.Contacts
import com.dumbos.core.Sms
import com.dumbos.core.SmsMessage
import com.dumbos.core.Thread
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
 * Mensajes. A list of conversations that opens into a pager of SMS bubbles:
 * incoming messages hang left, outgoing hang right, and long conversations
 * page with the d-pad or a swipe.
 */
class MessagesActivity : OsActivity() {

    private var threads: List<Thread> = emptyList()
    private var current: String? = null
    private var messages: List<SmsMessage> = emptyList()
    private var page = 0
    private var pages: List<List<SmsMessage>> = emptyList()
    private var convView: ConversationView? = null

    override fun buildLayout() {
        titleText = "MENSAJES"
        if (!hasPerm(Manifest.permission.READ_SMS) && !hasPerm(Manifest.permission.SEND_SMS)) {
            requestSmsPermissions()
        }
        showThreads()
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermissions() {
        requestPermissions(arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        ), 77)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showThreads()
    }

    private fun showThreads() {
        current = null
        convView = null
        content.removeAllViews()
        titleText = "MENSAJES"
        setKeys("ABRIR", "NUEVO")
        threads = Sms.threads(this)
        val col = vcol(this)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (!Sms.canRead(this)) {
            val warn = PixelText(this, "SIN PERMISO DE MENSAJES.\n\nDUMBOS NO PUEDE LEER TU BANDEJA. CONCEDE EL PERMISO CON EL BOTON DE ABAJO O MIRA LAS CONVERSACIONES DE EJEMPLO.",
                Sz.SMALL).also { it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(8), Dp.dp(8), Dp.dp(8), Dp.dp(8)) }
            col.addView(warn)
        }
        val head = PixelText(this, "CONVERSACIONES " + threads.size, Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(3), Dp.dp(6), Dp.dp(2))
        }
        col.addView(head)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))

        val l = PixelList(this, threads.size, Dp.dp(44))
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val t = threads[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dim = if (sel) theme.selInk else theme.dim
            val iconPx = max(1, Dp.dp(2))
            TYPO.iconRect(canvas, "SMS", com.dumbos.ui.ICON_GLYPHS["SMS"]!!, Dp.dp(2), (rowH - 16 * iconPx) / 2, iconPx,
                if (t.unread > 0) ink else dim)
            val name = t.name.ifBlank { t.number }
            TYPO.drawClipped(canvas, name, Dp.dp(40), Dp.dp(4), width - Dp.dp(66), Sz.BODY, ink)
            if (t.unread > 0) TYPO.draw(canvas, t.unread.toString(), width - Dp.dp(10) - TYPO.measure(t.unread.toString(), Sz.SMALL),
                Dp.dp(4), Sz.SMALL, ink)
            TYPO.drawClipped(canvas, t.last, Dp.dp(40), Dp.dp(4) + Sz.BODY.lineH() + Dp.dp(1), width - Dp.dp(44), Sz.SMALL, dim)
            TYPO.drawRight(canvas, Clock.time(t.millis), width - Dp.dp(6), Dp.dp(4) + Sz.BODY.lineH() + Dp.dp(1), Sz.SMALL, dim)
        }
        l.onChoose = { i -> openThread(threads[i].number) }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openThread(number: String) {
        current = number
        Sms.markRead(this, number)
        messages = Sms.conversation(this, number)
        paginate()
        titleText = Contacts.nameFor(this, number).ifBlank { number }
        setKeys("ESCRIBIR", "OPCIONES")
        content.removeAllViews()
        val v = ConversationView(this).also { it.theme = theme }
        convView = v
        content.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int): Boolean {
                if (delta > 0 && page < pages.size - 1) { page++; v.page = page; v.invalidate(); return true }
                if (delta < 0 && page > 0) { page--; v.page = page; v.invalidate(); return true }
                return false
            }
            override fun activate(): Boolean { compose(); return true }
            override fun goBack(): Boolean {
                if (page > 0) { page--; v.page = page; v.invalidate(); return true }
                return false
            }
        }
    }

    private fun paginate() {
        val perPage = 4
        pages = messages.chunked(perPage).ifEmpty { listOf(emptyList()) }
        page = pages.size - 1
        convView?.page = page
    }

    private fun compose() {
        val number = current ?: return
        showInput("MENSAJE", "ESCRIBE AQUI", multiline = true) { body ->
            if (body.isBlank()) return@showInput
            if (!Sms.canSend(this)) {
                toast("SIN PERMISO PARA ENVIAR")
                requestSmsPermissions()
                return@showInput
            }
            val ok = Sms.send(this, number, body)
            if (!ok) toast("NO SE PUDO ENVIAR")
            messages = Sms.conversation(this, number)
            paginate()
            convView?.messages = messages
            convView?.invalidate()
        }
    }

    override fun openMenu() {
        if (current == null) {
            showMenu("MENSAJES", listOf("NUEVO MENSAJE", "ACTUALIZAR", "PERMISOS")) { i ->
                when (i) {
                    0 -> newMessage()
                    1 -> showThreads()
                    2 -> requestSmsPermissions()
                }
            }
        } else {
            showMenu("CONVERSACION", listOf("ESCRIBIR", "LLAMAR", "MARCAR LEIDO", "BORRAR DE LA VISTA")) { i ->
                val n = current ?: return@showMenu
                when (i) {
                    0 -> compose()
                    1 -> startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:" + android.net.Uri.encode(n))))
                    2 -> { Sms.markRead(this, n); toast("MARCADO") }
                    3 -> { current = null; showThreads() }
                }
            }
        }
    }

    private fun newMessage() {
        val contacts = Contacts.list(this)
        val options = ArrayList<String>()
        options.add("ESCRIBIR NUMERO")
        contacts.take(30).forEach { options.add(it.name + " " + it.number) }
        showMenu("NUEVO MENSAJE", options) { i ->
            val number = if (i == 0) null else contacts[i - 1].number
            if (number != null) { openThread(number); compose() }
            else showInput("NUMERO", "+34") { n -> if (n.isNotBlank()) { openThread(n.trim()); compose() } }
        }
    }

    override fun finishOs() {
        if (current != null) showThreads() else super.finishOs()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        val n = intent?.getStringExtra("number")
        val data = intent?.data
        val fromData = data?.schemeSpecificPart?.substringBefore('?')?.replace(" ", "")
        val target = n ?: fromData
        if (!target.isNullOrBlank()) openThread(target)
    }

    override fun onResume() {
        super.onResume()
        if (current == null) showThreads()
    }
}

/** Paged SMS conversation: four messages per screen, 3310 style. */
class ConversationView(ctx: Context) : android.view.View(ctx) {

    var theme: Theme = com.dumbos.ui.PALETTES[0]
        set(v) { field = v; invalidate() }
    var messages: List<SmsMessage> = emptyList()
    var page: Int = 0

    private val perPage = 4

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false; color = theme.bg }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        val start = page * perPage
        val slice = messages.drop(start).take(perPage)
        var y = Dp.dp(6)
        val bubbleW = (width * 0.76f).toInt()
        for (m in slice) {
            val lines = TYPO.wrap(m.body, bubbleW - Dp.dp(14), Sz.SMALL)
            val bh = lines.size * Sz.SMALL.lineH() + Dp.dp(10)
            val left = if (m.incoming) Dp.dp(4) else width - bubbleW - Dp.dp(4)
            // bubble chrome: 1px frame, no radius, no shadow
            p.color = theme.panel
            canvas.drawRect(left.toFloat(), y.toFloat(), (left + bubbleW).toFloat(), (y + bh).toFloat(), p)
            p.color = if (m.incoming) theme.dim else theme.ink
            canvas.drawRect(left.toFloat(), y.toFloat(), (left + bubbleW).toFloat(), y + 1f, p)
            canvas.drawRect(left.toFloat(), (y + bh - 1).toFloat(), (left + bubbleW).toFloat(), (y + bh).toFloat(), p)
            canvas.drawRect(left.toFloat(), y.toFloat(), left + 1f, (y + bh).toFloat(), p)
            canvas.drawRect((left + bubbleW - 1).toFloat(), y.toFloat(), (left + bubbleW).toFloat(), (y + bh).toFloat(), p)
            var ty = y + Dp.dp(5)
            for (line in lines) {
                TYPO.draw(canvas, line, left + Dp.dp(6), ty, Sz.SMALL, theme.ink)
                ty += Sz.SMALL.lineH()
            }
            val stamp = Clock.time(m.millis)
            TYPO.draw(canvas, stamp, if (m.incoming) left + Dp.dp(6) else left + bubbleW - Dp.dp(6) - TYPO.measure(stamp, Sz.SMALL),
                y + bh - Sz.SMALL.lineH() - Dp.dp(4), Sz.SMALL, theme.dim)
            y += bh + Dp.dp(8)
        }
        // page indicator
        if (page > 0) TYPO.iconRect(canvas, "UP", com.dumbos.ui.SYMBOL_GLYPHS["ARROW_UP"]!!, width / 2 - 8 * Dp.dp(1), Dp.dp(1), Dp.dp(1), theme.dim)
        if (page < (messages.size - 1) / perPage) {
            TYPO.iconRect(canvas, "DOWN", com.dumbos.ui.SYMBOL_GLYPHS["ARROW_DOWN"]!!, width / 2 - 8 * Dp.dp(1), height - Dp.dp(10), Dp.dp(1), theme.dim)
        }
    }
}
