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
        setKeys("ABRIR", "PERMISO")
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

        val l = PixelList(this, conversations.size, Dp.dp(40))
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val (name, msgs) = conversations[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dim = if (sel) theme.selInk else theme.dim
            val last = msgs.lastOrNull()
            TYPO.drawClipped(canvas, com.dumbos.ui.TYPO.normalize(name), Dp.dp(6), Dp.dp(4), width - Dp.dp(64), Sz.BODY, ink)
            TYPO.drawRight(canvas, last?.let { Clock.time(it.millis) } ?: "", width - Dp.dp(6), Dp.dp(4), Sz.SMALL, dim)
            TYPO.drawClipped(canvas, last?.text ?: "", Dp.dp(6), Dp.dp(4) + Sz.BODY.lineH() + Dp.dp(1), width - Dp.dp(70), Sz.SMALL, dim)
            TYPO.drawRight(canvas, msgs.size.toString(), width - Dp.dp(6), Dp.dp(4) + Sz.BODY.lineH() + Dp.dp(1), Sz.SMALL, dim)
        }
        l.onChoose = { i -> openConversation(conversations[i].first) }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        val l = PixelList(this, msgs.size, Dp.dp(52))
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
                "CONCEDER ACCESO A NOTIFICACIONES",
                "ABRIR WHATSAPP",
                "REFRESCAR",
                "BORRAR BUZON",
            )) { i ->
                when (i) {
                    0 -> Bridge.requestAccess(this)
                    1 -> openWhatsApp()
                    2 -> showConversations()
                    3 -> showConfirm("BORRAR", "BORRAR TODOS LOS MENSAJES PUENTEADOS?", { Bridge.clear(this); showConversations() })
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
