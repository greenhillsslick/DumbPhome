package com.dumbos.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Toast
import com.dumbos.core.Prefs
import com.dumbos.core.Signal
import kotlin.math.max
import kotlin.math.min

interface Themed {
    var theme: Theme
    fun applyTheme(t: Theme) { theme = t }
}

/**
 * Base for every dumbOS screen. Gives all bundled apps the same chrome:
 *
 *   +--------------------------------+
 *   | APP TITLE           12:04 []]] |  header (panel colour)
 *   +--------------------------------+
 *   |                                |
 *   |           content              |
 *   |                                |
 *   +--------------------------------+
 *   |   BACK   |     OK     |  MENU  |  soft-key bar
 *   +--------------------------------+
 *
 * D-pad moves the selection, centre/OK activates, left soft key goes back and
 * the right soft key opens the screen menu.
 */
abstract class OsActivity : Activity(), Themed {

    override lateinit var theme: Theme
    protected lateinit var prefs: Prefs
        private set

    lateinit var root: LinearLayout
    lateinit var header: HeaderView
    lateinit var content: FrameLayout
    lateinit var keyBar: KeyBar

    var keyTarget: OsView? = null
    private var dialogView: View? = null

    var titleText: String = ""
        set(v) { field = v; if (::header.isInitialized) header.title = v }

    var statusText: String = ""
        set(v) { field = v; if (::header.isInitialized) header.status = v }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = PALETTES.firstOrNull { it.id == Prefs.themeId(this) } ?: PALETTES[0]
        Dp.init(this)
        prefs = Prefs(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
        }
        header = HeaderView(this).also {
            it.theme = theme
            it.onMenu = { openMenu() }
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content = FrameLayout(this).also {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        keyBar = KeyBar(this).also {
            it.theme = theme
            it.onKey = { k ->
                when (k.id) {
                    "BACK" -> if (!handleDialogKey("BACK")) finishOs()
                    "OK" -> if (!handleDialogKey("OK")) (keyTarget as? OsView)?.activate()
                    "MENU" -> if (!handleDialogKey("MENU")) openMenu()
                }
            }
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        buildLayout()
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            val cut = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            v.setPadding(0, max(top, cut), 0, bottom)
            insets
        }
        onCreateOs(savedInstanceState)
    }

    protected abstract fun buildLayout()

    protected open fun onCreateOs(savedInstanceState: Bundle?) {}

    /** Called when the right soft key is pressed with no dialog open. */
    protected open fun openMenu() {}

    protected open fun finishOs() { finish() }

    fun setKeys(ok: String = "OK", menu: String = "MENU", backLabel: String = "BACK", showBack: Boolean = true) {
        val list = ArrayList<SoftKey>()
        if (showBack) list.add(SoftKey("BACK", backLabel))
        list.add(SoftKey("OK", ok))
        list.add(SoftKey("MENU", menu))
        keyBar.keys = list
    }

    fun setKeys(vararg keys: SoftKey) {
        keyBar.keys = keys.toList()
    }

    override fun applyTheme(t: Theme) {
        theme = t
        root.setBackgroundColor(t.bg)
        header.theme = t
        keyBar.theme = t
        window.statusBarColor = t.bg
        window.navigationBarColor = t.bg
        walk(root)
    }

    private fun walk(v: View) {
        if (v is Themed) v.applyTheme(theme)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    // -- dialogs -------------------------------------------------------------

    private fun showDialogView(v: View) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xA0000000.toInt())
            isClickable = true
            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialogView = overlay
    }

    private fun hideDialog() {
        val d = dialogView ?: return
        root.removeView(d)
        dialogView = null
    }

    private fun handleDialogKey(key: String): Boolean {
        val d = dialogView
        if (d is KeyTarget) return d.onKeyCommand(key)
        return d != null
    }

    fun isDialogOpen(): Boolean = dialogView != null

    /** 3310-style popup list. Returns the chosen index or -1 when cancelled. */
    fun showMenu(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val panel = PixelList(this, items.size, Dp.dp(30)).apply {
            this.theme = this@OsActivity.theme
            paintRow = { canvas, i, sel, rowH ->
                val ink = if (sel) this@OsActivity.theme.selInk else this@OsActivity.theme.ink
                TYPO.drawClipped(canvas, (i + 1).toString() + " " + items[i], Dp.dp(6),
                    (rowH - Sz.BODY.lineH()) / 2, width - Dp.dp(10), Sz.BODY, ink)
                if (sel) TYPO.draw(canvas, "[RIGHT]", width - Dp.dp(46), (rowH - Sz.SMALL.lineH()) / 2, Sz.SMALL, ink)
            }
            onChoose = { i -> hideDialog(); onPick(i) }
        }
        val box = OsDialogFrame(this, title, panel, object : OsDialogActions {
            override fun onOk() { panel.activate() }
            override fun onMenu() { panel.moveSel(1) }
            override fun onBack() { hideDialog() }
        })
        keyTarget = panel
        showDialogView(box)
    }

    fun showConfirm(title: String, message: String, onYes: () -> Unit, onNo: () -> Unit = {}) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = PixelText(this, message, Sz.BODY).also { it.theme = theme }
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val box = OsDialogFrame(this, title, col, object : OsDialogActions {
            override fun onOk() { hideDialog(); onYes() }
            override fun onMenu() { hideDialog(); onNo() }
            override fun onBack() { hideDialog(); onNo() }
        })
        showDialogView(box)
    }

    /** Text prompt with a native (monospace) editor inside pixel chrome. */
    fun showInput(
        title: String,
        hint: String = "",
        initial: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
        onDone: (String) -> Unit,
    ) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val edit = EditText(this).apply {
            setText(initial)
            setHint(hint)
            isSingleLine = !multiline
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else InputType.TYPE_CLASS_TEXT or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            minLines = if (multiline) 4 else 1
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(theme.ink)
            setHintTextColor(theme.dim)
            setBackgroundColor(theme.panel)
            setPadding(Dp.dp(8), Dp.dp(6), Dp.dp(8), Dp.dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSelection(text.length)
        }
        col.addView(edit)
        val box = OsDialogFrame(this, title, col, object : OsDialogActions {
            override fun onOk() { val v = edit.text.toString(); hideDialog(); onDone(v) }
            override fun onMenu() { hideDialog() }
            override fun onBack() { hideDialog() }
        })
        showDialogView(box)
        edit.requestFocus()
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    fun openApp(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }

    override fun onResume() {
        super.onResume()
        header.invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (dialogView != null && handleDialogKey("NONE")) {
            // dialogs own the D-pad too
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> (keyTarget as? OsView)?.moveSel(1)
                KeyEvent.KEYCODE_DPAD_UP -> (keyTarget as? OsView)?.moveSel(-1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    return handleDialogKey("OK")
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return handleDialogKey("BACK")
                KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU -> return handleDialogKey("MENU")
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> (keyTarget as? OsView)?.moveSel(1)
            KeyEvent.KEYCODE_DPAD_UP -> (keyTarget as? OsView)?.moveSel(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                (keyTarget as? OsView)?.activate()
            KeyEvent.KEYCODE_SOFT_LEFT -> if (::keyBar.isInitialized) keyBar.performSoft("BACK")
            KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU -> keyBar.performSoft("MENU")
            KeyEvent.KEYCODE_VOLUME_UP -> return false
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onBackPressed() {
        if (dialogView != null) { hideDialog(); return }
        var t: OsView? = keyTarget
        if (t != null && t.goBack()) return
        super.onBackPressed()
    }
}

interface OsDialogActions { fun onOk(); fun onMenu(); fun onBack() }
interface KeyTarget { fun onKeyCommand(command: String): Boolean }

/** Bordered pixel panel used for dialogs, with a title and soft-key hints. */
class OsDialogFrame(
    ctx: OsActivity,
    title: String,
    body: View,
    private val actions: OsDialogActions,
) : LinearLayout(ctx), KeyTarget {

    private val t: Theme = ctx.theme

    init {
        orientation = VERTICAL
        setBackgroundColor(t.bg)
        setPadding(Dp.dp(8), Dp.dp(6), Dp.dp(8), Dp.dp(8))
        val titleView = PixelText(ctx, title, Sz.SMALL).also { it.theme = t; it.colorOverride = t.ink }
        addView(titleView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val rule = View(ctx).apply { setBackgroundColor(t.dim) }
        addView(rule, LayoutParams(MATCH_PARENT, max(1, Dp.dp(1))).apply { topMargin = Dp.dp(2); bottomMargin = Dp.dp(3) })
        addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val keys = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        keys.addView(hint(ctx, "[OK] ACEPTAR") {
            actions.onOk()
        })
        keys.addView(hint(ctx, "[MENU] OPT") {
            actions.onMenu()
        })
        keys.addView(hint(ctx, "[BACK]") {
            actions.onBack()
        })
        addView(keys, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Dp.dp(6) })
        backdrop(this)
    }

    private fun hint(context: Context, label: String, onClick: () -> Unit): View =
        PixelText(context, label, Sz.SMALL).also { v ->
            v.theme = t
            v.colorOverride = t.ink
            v.setPadding(Dp.dp(6), Dp.dp(2), 0, Dp.dp(2))
            v.isClickable = true
            v.setOnClickListener { onClick() }
        }

    private fun backdrop(v: View) {
        v.setBackgroundDrawable(PixelBorder(t.ink, t.bg))
    }

    override fun onKeyCommand(command: String): Boolean {
        when (command) {
            "OK" -> actions.onOk()
            "MENU" -> actions.onMenu()
            "BACK" -> actions.onBack()
        }
        return true
    }

    override fun onMeasure(w: Int, h: Int) {
        val maxW = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(min(maxW, MeasureSpec.getSize(w)), MeasureSpec.AT_MOST),
            h
        )
    }
}

/** Hard 1px frame, the only "border" dumbOS uses. */
class PixelBorder(private val color: Int, private val fill: Int) : android.graphics.drawable.Drawable() {
    override fun draw(c: Canvas) {
        val p = Paint().apply { isAntiAlias = false; this.color = fill }
        c.drawRect(bounds, p)
        p.color = color
        c.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), (bounds.top + 1).toFloat(), p)
        c.drawRect(bounds.left.toFloat(), (bounds.bottom - 1).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), p)
        c.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), (bounds.left + 1).toFloat(), bounds.bottom.toFloat(), p)
        c.drawRect((bounds.right - 1).toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(c: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** Thin status strip inside apps: app name, clock, signal and battery. */
class HeaderView(ctx: Context) : View(ctx), Themed {

    override var theme: Theme = PALETTES[0]
        set(v) { field = v; postInvalidate() }
    var title: String = ""
        set(v) { field = v; invalidate() }
    var status: String = ""
        set(v) { field = v; invalidate() }
    /** When set, replaces the clock on the right (home shows the date). */
    var rightText: String? = null
        set(v) { field = v; invalidate() }
    var showSignal: Boolean = true
        set(v) { field = v; invalidate() }
    var onMenu: (() -> Unit)? = null

    init {
        isClickable = true
        setOnClickListener { onMenu?.invoke() }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(0, w), Dp.dp(22))
    }

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false; color = theme.panel }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = theme.dim
        canvas.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), p)
        val cy = (height - Sz.SMALL.lineH()) / 2
        TYPO.drawClipped(canvas, title, Dp.dp(5), cy, width - Dp.dp(60), Sz.SMALL, theme.ink)
        val right = rightText ?: com.dumbos.core.Clock.hm()
        val signalRoom = if (showSignal) Dp.dp(34) else Dp.dp(6)
        TYPO.drawRight(canvas, right, width - signalRoom, cy, Sz.SMALL, theme.dim)
        if (showSignal) {
            val st = Signal.snapshot(context)
            val bars = st.bars
            for (i in 0 until 4) {
                val h = Dp.dp(1) * (i + 1)
                val x = width - Dp.dp(28) + i * Dp.dp(2)
                p.color = if (i < bars) theme.ink else theme.dim
                canvas.drawRect(x.toFloat(), (height / 2 + Dp.dp(3) - h).toFloat(), (x + Dp.dp(1)).toFloat(), (height / 2 + Dp.dp(3)).toFloat(), p)
            }
        }
    }
}

/** Applies theme colours to standard framework widgets we do use sparingly. */
fun View.tintTheme(t: Theme) {
    if (this is Themed) applyTheme(t)
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).tintTheme(t)
}

/** Convenience: vertical linear container. */
fun vcol(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(Dp.dp(pad), Dp.dp(pad), Dp.dp(pad), Dp.dp(pad))
}

fun hrow(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    setPadding(Dp.dp(pad), 0, Dp.dp(pad), 0)
}

/** Fixed-height spacer. */
fun spacer(ctx: Context, dp: Int): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(1, Dp.dp(dp))
}

/** A pixel push-button used on screens that need explicit actions. */
class PixelButton(ctx: Context, label: String, val action: String) : View(ctx), Themed {
    override var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }
    var text: String = label
        set(v) { field = v; invalidate() }
    var isDown = false
    var onPress: (() -> Unit)? = null

    init {
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { isDown = true; invalidate(); true }
                MotionEvent.ACTION_UP -> { isDown = false; invalidate(); onPress?.invoke(); true }
                MotionEvent.ACTION_CANCEL -> { isDown = false; invalidate(); true }
                else -> false
            }
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val tw = TYPO.measure(text, Sz.BODY) + Dp.dp(16)
        val th = Sz.BODY.lineH() + Dp.dp(10)
        setMeasuredDimension(resolveSize(tw, w), resolveSize(th, h))
    }

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false }
        p.color = if (isDown) theme.ink else theme.panel
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = theme.ink
        canvas.drawRect(0f, 0f, width.toFloat(), 1f, p)
        canvas.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), p)
        canvas.drawRect(0f, 0f, 1f, height.toFloat(), p)
        canvas.drawRect(width - 1f, 0f, width.toFloat(), height.toFloat(), p)
        TYPO.drawCenter(canvas, text, width / 2, (height - Sz.BODY.lineH()) / 2, Sz.BODY,
            if (isDown) theme.bg else theme.ink)
    }
}
