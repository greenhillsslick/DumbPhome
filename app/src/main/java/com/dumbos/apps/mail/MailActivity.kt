package com.dumbos.apps.mail

import android.graphics.Canvas
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dumbos.core.Clock
import com.dumbos.core.Mail
import com.dumbos.core.MailAccount
import com.dumbos.core.MailMessage
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
 * Correo: an inbox, a readable message view and a compose sheet. It works out
 * of the box with a local mailbox and can pull a real POP3 mailbox when the
 * user stores an account in Ajustes > Correo.
 */
class MailActivity : OsActivity() {

    private var messages: List<MailMessage> = emptyList()
    private var reading: MailMessage? = null

    override fun buildLayout() {
        titleText = "CORREO"
        showInbox()
    }

    private fun showInbox() {
        reading = null
        content.removeAllViews()
        titleText = "CORREO"
        setKeys("LEER", "ESCRIBIR")
        messages = Mail.all(this).sortedByDescending { it.millis }
        val col = vcol(this)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val acct = Mail.account(this)
        val head = PixelText(this, if (acct != null) acct.address else "BUZON LOCAL - SIN CUENTA", Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(3), Dp.dp(6), Dp.dp(2))
        }
        col.addView(head)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))

        val l = PixelList(this, messages.size, com.dumbos.ui.Rows.two())
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val m = messages[i]
            val ink = if (sel) theme.selInk else if (m.unread) theme.ink else theme.dim
            val dim = if (sel) theme.selInk else theme.dim
            // Centre the from+subject block in the row instead of pinning it to
            // 4dp from the top, which pushed the subject out of the row.
            val block = Sz.BODY.lineH() + Dp.dp(2) + Sz.SMALL.lineH()
            val top = ((rowH - block) / 2).coerceAtLeast(0)
            if (m.unread) TYPO.iconRect(canvas, "DOT", com.dumbos.ui.SYMBOL_GLYPHS["DOT"]!!, Dp.dp(2), rowH / 2 - 3 * Dp.dp(2), Dp.dp(2), ink)
            val tm = Clock.time(m.millis)
            TYPO.drawClipped(canvas, m.from, Dp.dp(10), top, width - Dp.dp(14) - TYPO.measure(tm, Sz.SMALL), Sz.BODY, ink)
            TYPO.drawRight(canvas, tm, width - Dp.dp(6), top, Sz.SMALL, dim)
            TYPO.drawClipped(canvas, m.subject, Dp.dp(10), top + Sz.BODY.lineH() + Dp.dp(2), width - Dp.dp(16), Sz.SMALL, dim)
        }
        l.onChoose = { i -> openMessage(messages[i]) }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openMessage(m: MailMessage) {
        reading = m
        if (m.unread) {
            m.unread = false
            val list = Mail.all(this).map { if (it.uid == m.uid) m else it }
            Mail.save(this, list)
        }
        titleText = "CORREO"
        setKeys("ATRAS", "ACCIONES")
        content.removeAllViews()
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val head = PixelText(this, "DE " + m.from + "\n" + Clock.stamp(m.millis), Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(4), Dp.dp(6), Dp.dp(2))
        }
        col.addView(head)
        val subj = PixelText(this, m.subject, Sz.BODY).also { it.theme = theme; it.setPadding(Dp.dp(6), Dp.dp(2), Dp.dp(6), Dp.dp(2)) }
        col.addView(subj)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val body = PixelText(this, m.body.ifBlank { "(SIN TEXTO)" }, Sz.SMALL).also {
            it.theme = theme; it.setPadding(Dp.dp(6), Dp.dp(6), Dp.dp(6), Dp.dp(6))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int) = false
            override fun activate(): Boolean { showInbox(); return true }
            override fun goBack(): Boolean = false
        }
    }

    private fun compose() {
        val acct = Mail.account(this)
        showInput("PARA", "DESTINATARIO") { to ->
            if (to.isBlank()) return@showInput
            showInput("ASUNTO", "") { subject ->
                showInput("MENSAJE", "", multiline = true) { body ->
                    // Hand off to the user's mail provider: dumbOS composes, it
                    // does not own the sending account or its credentials.
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("message/rfc822")
                        .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to.trim()))
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                        .putExtra(android.content.Intent.EXTRA_TEXT, body)
                    try {
                        startActivity(android.content.Intent.createChooser(intent, "ENVIAR CON"))
                    } catch (_: Exception) { toast("NO HAY APP DE CORREO") }
                }
            }
        }
    }

    override fun openMenu() {
        if (reading != null) {
            showMenu("ACCIONES", listOf("VOLVER", "RESPONDER POR SMS", "MARCAR SIN LEER")) { i ->
                val m = reading ?: return@showMenu
                when (i) {
                    0 -> showInbox()
                    1 -> showInput("SMS A", m.from) { num ->
                        startActivity(android.content.Intent(this, com.dumbos.apps.sms.MessagesActivity::class.java)
                            .putExtra("number", num))
                    }
                    2 -> { m.unread = true; Mail.save(this, Mail.all(this).map { if (it.uid == m.uid) m else it }); showInbox() }
                }
            }
            return
        }
        showMenu("CORREO", listOf("ESCRIBIR", "CUENTA POP3", "REFRESCAR", "MARCAR TODO LEIDO")) { i ->
            when (i) {
                0 -> compose()
                1 -> configureAccount()
                2 -> {
                    val acct = Mail.account(this)
                    if (acct == null) toast("SIN CUENTA") else toast("SINCRONIZANDO...")
                    showInbox()
                }
                3 -> {
                    val list = Mail.all(this).map { it.copy(unread = false) }
                    Mail.save(this, list)
                    toast("TODO LEIDO")
                    showInbox()
                }
            }
        }
    }

    private fun configureAccount() {
        showInput("SERVIDOR POP3", "POP.EJEMPLO.COM") { host ->
            showInput("USUARIO", "TU@CORREO.COM") { user ->
                showInput("CONTRASENA", "") { pass ->
                    showInput("DIRECCION COMPLETA", "TU@CORREO.COM") { addr ->
                        Mail.saveAccount(this, MailAccount(addr.ifBlank { user }, host, 995, user, pass, "BUZON"))
                        toast("CUENTA GUARDADA")
                        showInbox()
                    }
                }
            }
        }
    }

    override fun finishOs() {
        if (reading != null) showInbox() else super.finishOs()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        val data = intent?.data
        if (data != null && intent?.action == android.content.Intent.ACTION_SENDTO) {
            val to = data.schemeSpecificPart
            if (!to.isNullOrBlank()) showInput("ASUNTO", "") { subject ->
                showInput("MENSAJE", "", multiline = true) { body ->
                    startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("message/rfc822")
                        .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to))
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                        .putExtra(android.content.Intent.EXTRA_TEXT, body), "ENVIAR CON"))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (reading == null) showInbox()
    }
}
