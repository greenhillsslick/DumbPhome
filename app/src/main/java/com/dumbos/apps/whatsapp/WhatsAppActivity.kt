package com.dumbos.apps.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.dumbos.core.Bridge
import com.dumbos.core.BridgedNotification
import com.dumbos.core.Clock
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.OsActivity
import com.dumbos.ui.PixelList
import com.dumbos.ui.PixelText
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol

/**
 * Puente WhatsApp: dumbOS cannot impersonate WhatsApp, and does not try to.
 * Instead it reads the notifications that WhatsApp already posts (through the
 * optional notification-access listener) and lets you write back through
 * WhatsApp's own share sheet:
 *
 *   - conversations arrive as text lines, newest last
 *   - "RESPONDER" opens WhatsApp's composer for that chat (action 42)
 *   - if the direct intent is unavailable it falls back to the chat chooser
 */
class WhatsAppActivity : OsActivity() {

    private var conversations: List<Pair<String, List<BridgedNotification>>> = emptyList()
    private var conversation: String? = null
    private var receiver: BroadcastReceiver? = null

    override fun buildLayout() {
        titleText = "WHATSAPP"
        showConversations()
    }

    private fun listenerOk() = Bridge.listenerConnected(this)

    private fun showConversations() {
        conversation = null
        content.removeAllViews()
        titleText = "WHATSAPP"
        setKeys("ABRIR", "BUSCAR")
        conversations = Bridge.conversations(this).toList().sortedByDescending { it.second.lastOrNull()?.millis ?: 0L }
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (!listenerOk()) {
            val warn = PixelText(this,
                "DUMBOS NO VE WHATSAPP TODAVIA.\n\nACTIVA EL ACCESO A NOTIFICACIONES Y DUMBOS LEERA LOS MENSAJES COMO TEXTO PLANO.",
                Sz.SMALL).also { it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(8), Dp.dp(6), Dp.dp(8), Dp.dp(6)) }
            warn.isClickable = true
            warn.setOnClickListener { Bridge.requestAccess(this) }
            col.addView(warn)
            col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        }
        val head = PixelText(this, if (conversations.isEmpty()) "SIN CONVERSACIONES" else "CONVERSACIONES " + conversations.size, Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(3), Dp.dp(6), Dp.dp(2))
        }
        col.addView(head)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))

        val l = PixelList(this, conversations.size, com.dumbos.ui.Rows.two())
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val (name, msgs) = conversations[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dim = if (sel) theme.selInk else theme.dim
            val last = msgs.lastOrNull()
            val block = Sz.BODY.lineH() + Dp.dp(2) + Sz.SMALL.lineH()
            val top = (rowH - block) / 2
            TYPO.drawClipped(canvas, com.dumbos.ui.TYPO.normalize(name), Dp.dp(6), top, width - Dp.dp(64), Sz.BODY, ink)
            TYPO.drawRight(canvas, last?.let { Clock.time(it.millis) } ?: "", width - Dp.dp(6), top, Sz.SMALL, dim)
            TYPO.drawClipped(canvas, last?.text ?: "", Dp.dp(6), top + Sz.BODY.lineH() + Dp.dp(2), width - Dp.dp(70), Sz.SMALL, dim)
            TYPO.drawRight(canvas, msgs.size.toString(), width - Dp.dp(6), top + Sz.BODY.lineH() + Dp.dp(2), Sz.SMALL, dim)
        }
        l.onChoose = { i -> openConversation(conversations[i].first) }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Search a contact to start a chat, which is what a phone book is for.
     * It searches the system contacts and the people already bridged, then
     * opens WhatsApp straight into that chat.
     */
    private fun search() {
        showInput("BUSCAR CONTACTO", "NOMBRE O NUMERO") { q ->
            val term = q.trim()
            if (term.isEmpty()) return@showInput
            val hits = ArrayList<Pair<String, String>>()   // label, phone
            val wanted = com.dumbos.ui.TYPO.normalize(term)
            for (c in com.dumbos.core.Contacts.list(this)) {
                val name = com.dumbos.ui.TYPO.normalize(c.name)
                if (name.contains(wanted) || c.number.contains(term.filter { it.isDigit() })) {
                    hits.add(c.name.ifBlank { c.number } to c.number)
                }
            }
            if (hits.isEmpty()) {
                // No contact match: let the number be used directly, the way a
                // 3310 would.
                if (term.count { it.isDigit() } >= 6) {
                    sendToNumber(term, term)
                } else {
                    toast("SIN RESULTADOS")
                }
                return@showInput
            }
            if (hits.size == 1) {
                sendToNumber(hits[0].first, hits[0].second)
            } else {
                showMenu("CONTACTOS", hits.map { it.first }) { i -> sendToNumber(hits[i].first, hits[i].second) }
            }
        }
    }

    /**
     * Opens WhatsApp's chat with one number. WhatsApp's public API for this is
     * the wa.me deep link, which needs no permissions and no account inside
     * dumbOS: it is the same call the WhatsApp app makes for its own links.
     */
    private fun sendToNumber(label: String, number: String) {
        val digits = number.filter { it.isDigit() || it == '+' }.removePrefix("+")
        if (digits.length < 6) { toast("NUMERO NO VALIDO"); return }
        showMenu("WHATSAPP " + label.uppercase(), listOf("ABRIR CHAT", "ESCRIBIR SMS", "LLAMAR")) { i ->
            when (i) {
                0 -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://wa.me/" + digits)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        toast("WHATSAPP NO INSTALADO")
                    }
                }
                1 -> startActivity(Intent(this, com.dumbos.apps.sms.MessagesActivity::class.java)
                    .putExtra("number", number))
                2 -> startActivity(Intent(this, com.dumbos.apps.phone.PhoneActivity::class.java))
            }
        }
    }

    private fun openConversation(name: String) {
        conversation = name
        content.removeAllViews()
        titleText = name
        setKeys("RESPONDER", "OPCIONES")
        val msgs = Bridge.conversations(this)[name] ?: emptyList()
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val l = PixelList(this, msgs.size, com.dumbos.ui.Rows.two(pad = 14))
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val m = msgs[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dim = if (sel) theme.selInk else theme.dim
            TYPO.draw(canvas, m.app.uppercase(), Dp.dp(4), Dp.dp(4), Sz.SMALL, dim)
            TYPO.drawRight(canvas, Clock.stamp(m.millis), width - Dp.dp(4), Dp.dp(4), Sz.SMALL, dim)
            var y = Dp.dp(4) + Sz.SMALL.lineH() + Dp.dp(2)
            for (line in TYPO.wrap(m.text, width - Dp.dp(12), Sz.SMALL).take(2)) {
                TYPO.draw(canvas, line, Dp.dp(4), y, Sz.SMALL, ink)
                y += Sz.SMALL.lineH()
            }
        }
        l.setSelected(msgs.size - 1)
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int) = l.moveSel(delta)
            override fun activate(): Boolean { reply(name); return true }
            override fun goBack() = false
        }
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // local mini composer
        val quick = PixelText(this, "[ RESPONDER ]  [ LLAMAR ]  [ SMS ]", Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(4), Dp.dp(6), Dp.dp(4))
            it.isClickable = true
            it.setOnClickListener { reply(name) }
        }
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        col.addView(quick)
    }

    /** Opens WhatsApp's own composer: dumbOS writes, WhatsApp sends. */
    private fun reply(name: String) {
        showInput("RESPUESTA", "MENSAJE PARA " + name, multiline = true) { text ->
            if (text.isBlank()) return@showInput
            val direct = Intent("android.intent.action.SEND")
                .setType("text/plain")
                .setPackage("com.whatsapp")
                .putExtra(Intent.EXTRA_TEXT, text)
            val chooser = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            try {
                startActivity(Intent.createChooser(if (direct.resolveActivity(packageManager) != null) direct else chooser, "ENVIAR CON"))
            } catch (_: Exception) {
                toast("WHATSAPP NO INSTALADO")
            }
        }
    }

    override fun openMenu() {
        // On the list, the right soft key is BUSCAR: with no notifications yet
        // the old screen had no way to reach a contact at all.
        if (conversation == null) {
            search()
            return
        }
        if (conversation != null) {
            showMenu("PUENTE", listOf("RESPONDER", "ABRIR WHATSAPP", "COPIAR TEXTO", "VOLVER")) { i ->
                val name = conversation ?: return@showMenu
                when (i) {
                    0 -> reply(name)
                    1 -> openWhatsApp()
                    2 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("dumbOS", name))
                        toast("COPIADO")
                    }
                    3 -> showConversations()
                }
            }
        } else {
            showMenu("PUENTE WHATSAPP", listOf(
                "BUSCAR CONTACTO",
                "CONCEDER ACCESO A NOTIFICACIONES",
                "ABRIR WHATSAPP",
                "REFRESCAR",
                "BORRAR BUZON",
            )) { i ->
                when (i) {
                    0 -> search()
                    1 -> Bridge.requestAccess(this)
                    2 -> openWhatsApp()
                    3 -> showConversations()
                    4 -> showConfirm("BORRAR", "BORRAR TODOS LOS MENSAJES PUENTEADOS?", { Bridge.clear(this); showConversations() })
                }
            }
        }
    }

    private fun openWhatsApp() {
        try {
            val i = packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (i == null) toast("WHATSAPP NO INSTALADO") else startActivity(i)
        } catch (_: Exception) { toast("WHATSAPP NO INSTALADO") }
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (conversation == null) showConversations() else openConversation(conversation!!)
            }
        }
        androidxFreeRegister(receiver)
    }

    private fun androidxFreeRegister(r: BroadcastReceiver?) {
        if (r == null) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, IntentFilter("com.dumbos.BRIDGE_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, IntentFilter("com.dumbos.BRIDGE_CHANGED"))
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun finishOs() {
        if (conversation != null) showConversations() else super.finishOs()
    }
}
