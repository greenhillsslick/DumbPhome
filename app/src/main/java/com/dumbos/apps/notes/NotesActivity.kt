package com.dumbos.apps.notes

import android.graphics.Canvas
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.dumbos.core.Clock
import com.dumbos.core.Note
import com.dumbos.core.Notes
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.OsActivity
import com.dumbos.ui.PixelList
import com.dumbos.ui.PixelText
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol
import java.util.UUID

/**
 * Notas: a list, a reader and an editor. Plain text, stored locally, no sync,
 * no tags, no colours. Exactly enough to write down a phone number.
 */
class NotesActivity : OsActivity() {

    private var notes: List<Note> = emptyList()
    private var open: Note? = null

    override fun buildLayout() {
        titleText = "NOTAS"
        showList()
    }

    private fun showList() {
        open = null
        content.removeAllViews()
        titleText = "NOTAS"
        setKeys("LEER", "NUEVA")
        notes = Notes.all(this).sortedByDescending { it.millis }
        val col = vcol(this)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val l = PixelList(this, notes.size, Dp.dp(44))
        l.theme = theme
        l.paintRow = { canvas, i, sel, rowH ->
            val n = notes[i]
            val ink = if (sel) theme.selInk else theme.ink
            val dim = if (sel) theme.selInk else theme.dim
            TYPO.iconRect(canvas, "PENCIL", com.dumbos.ui.SYMBOL_GLYPHS["PENCIL"]!!, Dp.dp(2), rowH / 2 - 3 * Dp.dp(2), Dp.dp(2), if (sel) ink else dim)
            TYPO.drawClipped(canvas, n.title.ifBlank { "(SIN TITULO)" }, Dp.dp(12), Dp.dp(4), width - Dp.dp(60), Sz.BODY, ink)
            TYPO.drawRight(canvas, Clock.time(n.millis), width - Dp.dp(6), Dp.dp(4), Sz.SMALL, dim)
            val first = n.body.lines().firstOrNull { it.isNotBlank() } ?: ""
            TYPO.drawClipped(canvas, first, Dp.dp(12), Dp.dp(4) + Sz.BODY.lineH() + Dp.dp(1), width - Dp.dp(20), Sz.SMALL, dim)
        }
        l.onChoose = { i -> openNote(notes[i]) }
        keyTarget = l
        col.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openNote(n: Note) {
        open = n
        content.removeAllViews()
        titleText = n.title.ifBlank { "NOTA" }
        setKeys("ATRAS", "EDITAR")
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val meta = PixelText(this, Clock.stamp(n.millis), Sz.SMALL).also {
            it.theme = theme; it.colorOverride = theme.dim; it.setPadding(Dp.dp(6), Dp.dp(4), Dp.dp(6), Dp.dp(2))
        }
        col.addView(meta)
        val title = PixelText(this, n.title.ifBlank { "(SIN TITULO)" }, Sz.BODY).also { it.theme = theme; it.setPadding(Dp.dp(6), Dp.dp(2), Dp.dp(6), Dp.dp(2)) }
        col.addView(title)
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val body = PixelText(this, n.body.ifBlank { "(VACIA)" }, Sz.SMALL).also { it.theme = theme; it.setPadding(Dp.dp(6), Dp.dp(6), Dp.dp(6), Dp.dp(6)) }
        col.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int) = false
            override fun activate(): Boolean { showList(); return true }
            override fun goBack(): Boolean = false
        }
    }

    private fun editor(existing: Note?) {
        showInput("TITULO", "TITULO", existing?.title ?: "") { title ->
            showInput("TEXTO", "ESCRIBE", existing?.body ?: "", multiline = true) { body ->
                if (title.isBlank() && body.isBlank()) return@showInput
                val n = Note(existing?.id ?: UUID.randomUUID().toString().take(8), title.trim().uppercase(), body, System.currentTimeMillis())
                Notes.upsert(this, n)
                open = n
                toast("GUARDADA")
                openNote(n)
            }
        }
    }

    override fun openMenu() {
        if (open != null) {
            showMenu("NOTA", listOf("EDITAR", "BORRAR", "ANADIR HORA", "VOLVER")) { i ->
                val n = open ?: return@showMenu
                when (i) {
                    0 -> editor(n)
                    1 -> showConfirm("BORRAR", "BORRAR ESTA NOTA?", { Notes.delete(this, n.id); toast("BORRADA"); showList() })
                    2 -> {
                        n.body = n.body + "\n" + Clock.hm()
                        n.millis = System.currentTimeMillis()
                        Notes.upsert(this, n)
                        openNote(n)
                    }
                    3 -> showList()
                }
            }
        } else {
            showMenu("NOTAS", listOf("NUEVA NOTA", "BORRAR TODAS")) { i ->
                when (i) {
                    0 -> editor(null)
                    1 -> showConfirm("BORRAR TODO", "SE PERDERAN TODAS LAS NOTAS.", {
                        Notes.save(this, emptyList()); showList()
                    })
                }
            }
        }
    }

    override fun finishOs() {
        if (open != null) showList() else super.finishOs()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {}
}
