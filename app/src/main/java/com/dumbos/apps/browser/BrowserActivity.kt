package com.dumbos.apps.browser

import android.graphics.Canvas
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.dumbos.core.MiniWebClient
import com.dumbos.core.WebHome
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.OsActivity
import com.dumbos.ui.PixelList
import com.dumbos.ui.PixelText
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol
import kotlin.concurrent.thread

/**
 * Navegador de texto: pulls a page over HTTPS, strips every tag, and renders
 * the words plus a numbered list of links. No images, no scripts, no tabs,
 * no cookies. Exactly the web a 3310 could have shown.
 */
class BrowserActivity : OsActivity() {

    private val client = MiniWebClient()
    private var stack = ArrayList<String>()
    private var current: MiniWebClient.Page? = null
    private var loading = false
    private var anim = 0.1f

    override fun buildLayout() {
        titleText = "WEB"
        showHome()
    }

    private fun showHome() {
        current = null
        content.removeAllViews()
        titleText = "WEB TEXTO"
        setKeys("ABRIR", "URL")
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val intro = PixelText(this, "WEB DE SOLO TEXTO.\n\nSIN IMAGENES, SIN VIDEOS, SIN\nANUNCIOS ANIMADOS. PAGINAS QUE\nCABEN EN UNA PANTALLA.", Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(8), Dp.dp(8), Dp.dp(8), Dp.dp(8))
        }
        col.addView(intro)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val l = PixelList(this, WebHome.LINKS.size, com.dumbos.ui.Rows.one())
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val ink = if (sel) theme.selInk else theme.ink
            TYPO.iconRect(canvas, "GLOBE", com.dumbos.ui.SYMBOL_GLYPHS["GLOBE"]!!, Dp.dp(2), (rowH - 21) / 2, Dp.dp(1), if (sel) ink else theme.dim)
            TYPO.drawClipped(canvas, (i + 1).toString() + ". " + WebHome.LINKS[i].second, Dp.dp(12),
                (rowH - Sz.BODY.lineH()) / 2, width - Dp.dp(16), Sz.BODY, ink)
        }
        l.onChoose = { i ->
            val url = WebHome.LINKS[i].first
            if (url.startsWith("dumb://")) toast("ESTAS EN INICIO") else load(url, push = true)
        }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun load(url: String, push: Boolean) {
        if (loading) return
        loading = true
        anim = 0.1f
        showLoading(url)
        thread {
            val res = client.get(url)
            runOnUiThread {
                loading = false
                if (res.isSuccess) {
                    if (push) stack.add(url)
                    showPage(res.getOrThrow())
                } else {
                    showError(url, res.exceptionOrNull()?.message ?: "ERROR")
                }
            }
        }
    }

    private fun showLoading(url: String) {
        content.removeAllViews()
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val t = PixelText(this, "CONECTANDO" + dots(), Sz.BODY).also {
            it.theme = theme; it.setPadding(Dp.dp(8), Dp.dp(10), Dp.dp(8), Dp.dp(4))
        }
        val h = PixelText(this, MiniWebClient().host(url), Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(8), 0, Dp.dp(8), Dp.dp(4))
        }
        col.addView(t)
        col.addView(h)
        val bar = object : android.view.View(this) {
            override fun onDraw(canvas: Canvas) {
                val p = android.graphics.Paint().apply { isAntiAlias = false; color = theme.dim }
                canvas.drawRect(Dp.dpf(8f), Dp.dpf(8f), (width - Dp.dp(8)).toFloat(), Dp.dpf(8f) + 1f, p)
                p.color = theme.ink
                canvas.drawRect(Dp.dpf(8f), Dp.dpf(8f), (width - Dp.dp(8)).toFloat() * anim, Dp.dpf(8f) + 1f, p)
            }
        }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(20)))
        t.postDelayed(object : Runnable {
            var step = 0
            override fun run() {
                if (!loading) return
                step = (step + 1) % 8
                t.text = "CONECTANDO" + dots(step)
                bar.invalidate()
                t.postDelayed(this, 220)
            }
        }, 220)
    }

    private fun dots(step: Int = 0): String = ".".repeat(step % 4)

    private fun showPage(page: MiniWebClient.Page) {
        current = page
        content.removeAllViews()
        titleText = "WEB"
        setKeys("ENLACES", "OPCIONES")
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val urlBar = PixelText(this, page.url, Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(3), Dp.dp(6), Dp.dp(2))
        }
        urlBar.isClickable = true
        urlBar.setOnClickListener { goToUrl() }
        col.addView(urlBar)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val title = PixelText(this, page.title, Sz.BODY).also { it.theme = theme; it.setPadding(Dp.dp(6), Dp.dp(4), Dp.dp(6), Dp.dp(2)) }
        col.addView(title)
        val body = PixelText(this, page.text, Sz.SMALL).also { it.theme = theme; it.setPadding(Dp.dp(6), Dp.dp(2), Dp.dp(6), Dp.dp(8)) }
        val scroll = ScrollView(this).apply {
            addView(body)
            setBackgroundColor(theme.bg)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int): Boolean {
                scroll.scrollBy(0, delta * Dp.dp(60)); return true
            }
            override fun activate(): Boolean { showLinks(); return true }
            override fun goBack(): Boolean {
                if (stack.size > 1) { stack.removeAt(stack.size - 1); load(stack.last(), push = false); return true }
                if (stack.size == 1) { stack.clear(); showHome(); return true }
                return false
            }
        }
    }

    private fun showLinks() {
        val page = current ?: return
        if (page.links.isEmpty()) { toast("SIN ENLACES"); return }
        val labels = page.links.mapIndexed { i, p -> "${i + 1}. " + p.second }
        showMenu("ENLACES (" + labels.size + ")", labels) { i ->
            val href = page.links[i].first
            if (href.startsWith("http")) load(href, push = true) else toast("ENLACE NO SOPORTADO")
        }
    }

    private fun showError(url: String, msg: String) {
        content.removeAllViews()
        setKeys("REINTENTAR", "URL")
        val col = vcol(this, 8)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val t = PixelText(this, "SIN CONEXION\n\nNO SE PUDO CARGAR:\n$url\n\n$msg\n\nCOMPRUEBA LOS DATOS O EL WIFI.", Sz.SMALL).also { it.theme = theme }
        col.addView(t)
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int) = false
            override fun activate(): Boolean { load(url, push = true); return true }
            override fun goBack() = false
        }
    }

    private fun goToUrl() {
        showInput("URL", "EJEMPLO.COM", current?.url ?: "") { u ->
            if (u.isNotBlank()) load(u.trim(), push = true)
        }
    }

    override fun openMenu() {
        showMenu("NAVEGADOR", listOf("IR A URL", "INICIO", "ATRAS", "ENLACES", "COMPARTIR TEXTO")) { i ->
            when (i) {
                0 -> goToUrl()
                1 -> { stack.clear(); showHome() }
                2 -> if (stack.size > 1) { stack.removeAt(stack.size - 1); load(stack.last(), push = false) } else showHome()
                3 -> showLinks()
                4 -> {
                    val page = current ?: return@showMenu
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dumbOS", page.text))
                    toast("TEXTO COPIADO")
                }
            }
        }
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        val data = intent?.data
        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            load(data.toString(), push = true)
        }
    }

    override fun finishOs() {
        if (current != null) showHome() else super.finishOs()
    }
}
