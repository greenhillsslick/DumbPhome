package com.dumbos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel-perfect density helpers. All layout is in whole device pixels. */
object Dp {
    @Volatile private var density = 3f

    /**
     * A uniform multiplier applied to every measurement, derived from how wide
     * the screen is in dp.
     *
     * dumbOS is drawn in dp, never in sp, so it ignores the system font scale
     * (the layout would break if a user's 1.3x text setting stretched a 5x7
     * glyph grid). Without this factor the same 12dp text is physically smaller
     * on a 384dp-wide phone than on a 411dp one, which is the closest thing
     * this UI has to "the text is too small".
     *
     * Clamped so tiny phones stay readable and tablets don't get cartoonish.
     */
    @Volatile private var uiScale = 1f

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        density = dm.density
        val widthDp = if (dm.widthPixels > 0) dm.widthPixels / dm.density else 392f
        // 392dp (Pixel 4a-ish) is the reference width.
        uiScale = (widthDp / 392f).coerceIn(0.92f, 1.18f)
    }

    /**
     * Zero must stay zero: a 0dp gap or a 0-thickness rule is meaningful, and
     * clamping it to 1 would silently shift every layout that uses it.
     */
    fun dp(v: Int): Int = if (v == 0) 0 else max(1, (v * density * uiScale).roundToInt())
    fun dpf(v: Float): Float = v * density * uiScale
    fun density(): Float = density
    /** The width-derived multiplier, exposed for text-fitting decisions. */
    fun scale(): Float = uiScale
}

/**
 * The four text sizes of dumbOS. A "unit" is the size of one font pixel in dp;
 * glyphs are 5x7 (body) or 4x6 (small) *unit* pixels, so sizes stay crisp at
 * every density because units are converted to whole device pixels.
 */
enum class Sz(val unitDp: Int, val small: Boolean) {
    SMALL(2, true),
    BODY(2, false),
    TITLE(3, false),
    HUGE(4, false);

    val glyphW: Int get() = if (small) 4 else 5
    val glyphH: Int get() = if (small) 6 else 7
    val adv: Int get() = if (small) 5 else 6

    /** Device pixels per font pixel. */
    fun unitPx(): Int = Dp.dp(unitDp)
    fun lineH(): Int = glyphH * unitPx()
}

val PALETTES: List<Theme> = listOf(
    Theme("PHOSPHOR", "FOSFORO VERDE", 0xFF0B1408.toInt(), 0xFF9BFF6B.toInt(),
        0xFF4E8A38.toInt(), 0xFF12220B.toInt()),
    Theme("AMBER", "AMBAR NOCTURNO", 0xFF100C02.toInt(), 0xFFFFC145.toInt(),
        0xFF8A6521.toInt(), 0xFF1C1403.toInt()),
    Theme("ICE", "AZUL HIELO", 0xFF07101A.toInt(), 0xFF8FD8FF.toInt(),
        0xFF3C6F8E.toInt(), 0xFF0C1A26.toInt()),
    Theme("PAPER", "PAPEL VERDE", 0xFFC7DDB0.toInt(), 0xFF16220F.toInt(),
        0xFF5E7549.toInt(), 0xFFB4CF9C.toInt()),
    Theme("MONO", "MONOCROMO", 0xFF000000.toInt(), 0xFFD8F0D0.toInt(),
        0xFF5A6B55.toInt(), 0xFF0A0F0A.toInt()),
)

/**
 * The whole visual identity in five colours. Selection is ink-on-ink inverted,
 * exactly like a 3310 menu.
 */
data class Theme(
    val id: String,
    val name: String,
    val bg: Int,
    val ink: Int,
    val dim: Int,
    val panel: Int,
) {
    val selBg: Int get() = ink
    val selInk: Int get() = bg
}

object Px {
    val paint = Paint().apply { isAntiAlias = false; typeface = Typeface.MONOSPACE }
    private val glyphs = HashMap<String, Bitmap>()

    /**
     * 1-bit glyph mask, cached per glyph id. Tinted at draw time.
     *
     * The bitmap is ALPHA_8, so every pixel is *only* an alpha value. setPixels()
     * takes ARGB ints and keeps the alpha channel, which means the value has to
     * be 0xFF000000 (opaque), not 0xFF: 0x000000FF is fully transparent and every
     * glyph drew as an invisible hole. Rectangles were unaffected, which is why
     * the UI showed selection bars and rules but no text.
     */
    fun glyph(key: String, rows: Array<String>): Bitmap {
        glyphs[key]?.let { return it }
        val w = rows[0].length
        val h = rows.size
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = if (rows[y][x] == '#') OPAQUE else 0
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        glyphs[key] = bmp
        return bmp
    }

    private const val OPAQUE = 0xFF shl 24 // 0xFF000000: fully opaque alpha
}

/** Draws the bitmap font. Stateless; safe to share. */
class Typo {

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (raw in s) {
            val c = ACCENT_FOLD[raw] ?: raw
            when (c) {
                '\u2019', '\u2018', '\u00B4', '`' -> sb.append('\'')
                '\u201C', '\u201D' -> sb.append('"')
                '\u2013', '\u2014' -> sb.append('-')
                '\u2026' -> sb.append("...")
                '\u00B7' -> sb.append('.')
                '\u00BA', '\u00B0' -> sb.append('o')
                '\u20AC' -> sb.append("EUR")
                '\t' -> sb.append("  ")
                '\n' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString().uppercase()
    }

    private fun rowsFor(c: Char, small: Boolean): Array<String>? {
        if (c.code in 1..30) return symbolGlyph(c)
        if (c == 'Ñ') return if (small) SMALL_GLYPHS["Ñ"] else BIG_GLYPHS["N"]
        return (if (small) SMALL_GLYPHS[c.toString()] else BIG_GLYPHS[c.toString()])
    }

    /**
     * Mirrors draw() exactly: a glyph with no bitmap still reserves space, and it
     * must reserve the same amount the renderer advances. When these disagreed,
     * drawRight/ drawCenter computed a start point for a wider string than was
     * actually drawn, so centred and right-aligned text drifted left and could
     * run into its neighbour.
     */
    fun measure(text: String, size: Sz): Int {
        val s = normalize(text)
        if (s.isEmpty()) return 0
        var units = 0
        for (c in s) units += if (c == ' ') 3 else size.adv
        return max(0, (units - 1) * size.unitPx())
    }

    fun draw(canvas: Canvas, text: String, x: Int, y: Int, size: Sz, color: Int): Int {
        val unit = size.unitPx()
        Px.paint.color = color
        Px.paint.alpha = 255
        var cx = x
        val s = normalize(text)
        for (c in s) {
            if (c == ' ') { cx += 3 * unit; continue }
            val rows = rowsFor(c, size.small)
            if (rows == null) {
                // No bitmap for this character: reserve a full advance (as
                // measure() does) and draw a hollow box so a missing glyph is
                // visible in the UI instead of a silent gap.
                Px.paint.color = color
                Px.paint.alpha = 255
                Px.paint.style = Paint.Style.STROKE
                Px.paint.strokeWidth = max(1f, unit.toFloat() / 2f)
                canvas.drawRect(
                    cx.toFloat() + unit / 2f, y.toFloat() + unit / 2f,
                    cx + (size.glyphW - 0.5f) * unit, y + (size.glyphH - 0.5f) * unit,
                    Px.paint)
                Px.paint.style = Paint.Style.FILL
                cx += size.adv * unit
                continue
            }
            val key = (if (size.small) "s" else "b") + c.code
            val bmp = Px.glyph(key, rows)
            canvas.drawBitmap(bmp, null,
                Rect(cx, y, cx + rows[0].length * unit, y + rows.size * unit), Px.paint)
            cx += size.adv * unit
        }
        return cx - x
    }

    fun drawRight(canvas: Canvas, text: String, right: Int, y: Int, size: Sz, color: Int) {
        draw(canvas, text, right - measure(text, size), y, size, color)
    }

    fun drawCenter(canvas: Canvas, text: String, cx: Int, y: Int, size: Sz, color: Int) {
        draw(canvas, text, cx - measure(text, size) / 2, y, size, color)
    }

    /** Draws a one-line string clipped to maxWidth (with a trailing dot-dot-dot). */
    fun drawClipped(canvas: Canvas, text: String, x: Int, y: Int, maxWidth: Int, size: Sz, color: Int): Int {
        if (measure(text, size) <= maxWidth) return draw(canvas, text, x, y, size, color)
        var s = normalize(text)
        while (s.isNotEmpty() && measure(s + "...", size) > maxWidth) s = s.dropLast(1)
        return draw(canvas, s.trimEnd() + "...", x, y, size, color)
    }

    /** Greedy word wrap by pixel width. */
    fun wrap(text: String, maxWidth: Int, size: Sz): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) { out.add(""); continue }
            val words = para.trim().split(' ').filter { it.isNotEmpty() }
            var line = StringBuilder()
            for (w in words) {
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (line.isNotEmpty() && measure(candidate, size) > maxWidth) {
                    out.add(line.toString())
                    line = StringBuilder()
                }
                if (line.isEmpty()) {
                    var rest = w
                    while (measure(rest, size) > maxWidth && rest.length > 1) {
                        var cut = rest.length
                        while (cut > 1 && measure(rest.substring(0, cut), size) > maxWidth) cut--
                        out.add(rest.substring(0, cut))
                        rest = rest.substring(cut)
                    }
                    line = StringBuilder(rest)
                } else {
                    line.append(' ').append(w)
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    fun iconRect(canvas: Canvas, name: String, rows: Array<String>, x: Int, y: Int, px: Int, color: Int) {
        val bmp = Px.glyph("i$name", rows)
        Px.paint.color = color
        Px.paint.alpha = 255
        canvas.drawBitmap(bmp, null, Rect(x, y, x + rows[0].length * px, y + rows.size * px), Px.paint)
    }

    fun icon(ctx: Context, name: String, sizeDp: Int, color: Int): android.graphics.drawable.BitmapDrawable {
        val rows = ICON_GLYPHS[name] ?: error("no icon $name")
        val px = max(1, Dp.dp(sizeDp) / rows.size)
        val bmp = Bitmap.createBitmap(rows[0].length * px, rows.size * px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { isAntiAlias = false }
        val mask = Px.glyph("i$name", rows)
        val paint = Paint().apply { isAntiAlias = false; this.color = color }
        c.drawBitmap(mask, null, Rect(0, 0, bmp.width, bmp.height), paint)
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }
}

val TYPO = Typo()

// ---------------------------------------------------------------------------
// Simple views
// ---------------------------------------------------------------------------

/** A block of wrapped text that draws as many lines as it needs. */
class PixelText(
    ctx: Context,
    private var value: String = "",
    private val size: Sz = Sz.BODY,
    private val align: Int = Gravity.START,
) : View(ctx) {

    var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }

    var text: String
        get() = value
        set(v) { value = v; requestLayout(); invalidate() }

    var colorOverride: Int? = null
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val avail = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) 400 else MeasureSpec.getSize(w)
        val mw = max(1, avail - paddingLeft - paddingRight)
        var lines = 0
        for (l in value.split('\n')) lines += TYPO.wrap(l, mw, size).size
        val hh = lines * size.lineH() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(avail, w), resolveSize(hh, h))
    }

    override fun onDraw(canvas: Canvas) {
        val color = colorOverride ?: theme.ink
        val mw = width - paddingLeft - paddingRight
        var y = paddingTop
        for (rawLine in value.split('\n')) {
            for (line in TYPO.wrap(rawLine, mw, size)) {
                val lw = TYPO.measure(line, size)
                val x = when (align) {
                    Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> paddingLeft + (mw - lw) / 2
                    Gravity.END, Gravity.RIGHT -> width - paddingRight - lw
                    else -> paddingLeft
                }
                TYPO.draw(canvas, line, x, y, size, color)
                y += size.lineH()
            }
        }
    }
}

class PixelIcon(ctx: Context, private val name: String, private val sizeDp: Int = 16) : View(ctx) {
    var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }
    var colorOverride: Int? = null
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val s = Dp.dp(sizeDp)
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(canvas: Canvas) {
        val rows = ICON_GLYPHS[name] ?: return
        val px = max(1, min(width / rows[0].length, height / rows.size))
        val w = rows[0].length * px
        val h = rows.size * px
        TYPO.iconRect(canvas, name, rows, (width - w) / 2, (height - h) / 2, px, colorOverride ?: theme.ink)
    }
}

/** A 1px rule; the only "graphic" element dumbOS allows besides icons. */
class HRule(ctx: Context, var color: Int = 0xFF4E8A38.toInt(), var dash: Boolean = false) : View(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(0, w), Dp.dp(3))
    }
    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false; this.color = this@HRule.color }
        val y = height / 2
        if (!dash) {
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 1f, p)
        } else {
            var x = 0
            val step = Dp.dp(2)
            while (x < width) { canvas.drawRect(x.toFloat(), y.toFloat(), (x + step).toFloat(), y + 1f, p); x += step * 2 }
        }
    }
}

// ---------------------------------------------------------------------------
// Scrollable, keyboard-navigable pixel list
// ---------------------------------------------------------------------------

/** Anything that can take D-pad input. Activity routes keys to the top view. */
interface OsView {
    fun moveSel(delta: Int): Boolean
    fun activate(): Boolean
    fun goBack(): Boolean
}

/**
 * A list of fixed-height rows that scroll with a finger and snap to rows.
 * Rows are painted by [paintRow] so every app can style its own entries while
 * sharing the CRT-ish scroll behaviour.
 */
class PixelList(ctx: Context, val count: Int, val rowH: Int) : View(ctx), OsView {

    var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }

    var selected: Int = 0
        private set
    var onChoose: ((Int) -> Unit)? = null
    var onHighlight: ((Int) -> Unit)? = null
    /** Row painter with the list as receiver so rows can read its width. */
    var paintRow: (PixelList.(Canvas, Int, Boolean, Int) -> Unit)? = null

    var drawSelectionBar = true
    var loop = false

    var scrollYPos: Int = 0
        private set

    private val scroller = OverScroller(ctx)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var downTime = 0L

    private fun maxScroll(): Int = max(0, count * rowH - height)

    fun scrollToSelected() {
        val top = selected * rowH
        val target = when {
            top < scrollYPos -> top
            top + rowH > scrollYPos + height -> top + rowH - height
            else -> scrollYPos
        }
        scrollYPos = target.coerceIn(0, maxScroll())
        invalidate()
    }

    fun setSelected(i: Int, scroll: Boolean = true) {
        if (count == 0) return
        selected = i.coerceIn(0, count - 1)
        if (scroll) scrollToSelected()
        onHighlight?.invoke(selected)
        invalidate()
    }

    override fun moveSel(delta: Int): Boolean {
        if (count == 0) return false
        var n = selected + delta
        if (loop) n = ((n % count) + count) % count else n = n.coerceIn(0, count - 1)
        val changed = n != selected
        selected = n
        scrollToSelected()
        onHighlight?.invoke(selected)
        invalidate()
        return changed
    }

    override fun activate(): Boolean {
        if (count == 0) return false
        onChoose?.invoke(selected)
        return true
    }

    override fun goBack(): Boolean = false

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(0, w), resolveSize(0, h))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrollYPos = scrollYPos.coerceIn(0, maxScroll())
    }

    override fun onDraw(canvas: Canvas) {
        val first = (scrollYPos / rowH).coerceAtLeast(0)
        val last = min(count - 1, (scrollYPos + height) / rowH + 1)
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        for (i in first..last) {
            if (i < 0 || i >= count) continue
            val y = i * rowH - scrollYPos
            canvas.save()
            canvas.translate(0f, y.toFloat())
            val sel = i == selected
            if (sel && drawSelectionBar) {
                Px.paint.color = theme.ink
                Px.paint.alpha = 255
                canvas.drawRect(0f, 0f, width.toFloat(), rowH.toFloat(), Px.paint)
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), rowH.toFloat(), 255)
            }
            canvas.save()
            canvas.clipRect(Dp.dpf(3f), 0f, (width - Dp.dp(3)).toFloat(), rowH.toFloat())
            paintRow?.invoke(this, canvas, i, sel, rowH)
            canvas.restore()
            if (sel && drawSelectionBar) canvas.restore()
            canvas.restore()
        }
        canvas.restore()
    }

    private fun settle(target: Int) {
        scroller.startScroll(0, scrollYPos, 0, (target.coerceIn(0, maxScroll())) - scrollYPos, 120)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYPos = scroller.currY
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downY = e.y; lastY = e.y; dragging = false
                downTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastY - e.y
                if (!dragging && abs(e.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    scrollYPos = (scrollYPos + dy.toInt()).coerceIn(0, maxScroll())
                    invalidate()
                }
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val vy = -((e.y - downY) * 8).toInt()
                    var target = (scrollYPos / rowH) * rowH + if (vy < -Dp.dp(30)) rowH else if (vy > Dp.dp(30)) 0 else {
                        if (scrollYPos % rowH > rowH / 2) rowH else 0
                    }
                    if (vy < -Dp.dp(120)) target = ((scrollYPos + rowH) / rowH) * rowH
                    if (vy > Dp.dp(120)) target = (scrollYPos / rowH) * rowH
                    settle(target)
                } else if (System.currentTimeMillis() - downTime < 600) {
                    val idx = (scrollYPos + e.y.toInt()) / rowH
                    if (idx in 0 until count) {
                        selected = idx
                        invalidate()
                        onChoose?.invoke(idx)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

// ---------------------------------------------------------------------------
// 3310-style bottom key bar
// ---------------------------------------------------------------------------

/** A soft key: glyph icon or pixel label, drawn in the bar under the screen. */
class SoftKey(val id: String, val label: String = "", val icon: String? = null, val enabled: Boolean = true)

/**
 * The bar that mirrors the two soft keys + d-pad of a candybar phone. It sits
 * above the system navigation bar and is always tappable.
 */
class KeyBar(ctx: Context) : View(ctx) {
    var theme: Theme = PALETTES[0]
        set(v) { field = v; invalidate() }
    var keys: List<SoftKey> = emptyList()
        set(v) { field = v; invalidate() }
    var onKey: ((SoftKey) -> Unit)? = null

    fun performSoft(id: String) {
        val k = keys.firstOrNull { it.id == id } ?: return
        if (k.enabled) onKey?.invoke(k)
    }

    init { isClickable = true }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(0, w), Dp.dp(KEYBAR_H))
    }

    private fun keyRects(): List<Rect> {
        val n = max(1, keys.size)
        val w = width / n
        return keys.indices.map { Rect(it * w, 0, if (it == n - 1) width else (it + 1) * w, height) }
    }

    override fun onDraw(canvas: Canvas) {
        Px.paint.color = theme.panel
        Px.paint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Px.paint)
        Px.paint.color = theme.dim
        canvas.drawRect(0f, 0f, width.toFloat(), 1f, Px.paint)
        val rects = keyRects()
        for (i in keys.indices) {
            val k = keys[i]
            val r = rects[i]
            val color = if (k.enabled) theme.ink else theme.dim
            if (i > 0) {
                Px.paint.color = theme.dim
                canvas.drawRect(r.left.toFloat(), Dp.dpf(9f), r.left.toFloat() + 1f, height - Dp.dpf(9f), Px.paint)
            }
            val cy = height / 2
            if (k.icon != null) {
                val rows = ICON_GLYPHS[k.icon] ?: SYMBOL_GLYPHS[k.icon]
                if (rows != null) {
                    val px = max(1, Dp.dp(2))
                    val w = rows[0].length * px
                    val h = rows.size * px
                    TYPO.iconRect(canvas, k.icon, rows, r.centerX() - w / 2, cy - h / 2, px, color)
                }
            } else {
                TYPO.drawCenter(canvas, k.label, r.centerX(), cy - Sz.SMALL.lineH() / 2, Sz.SMALL, color)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val rects = keyRects()
            for (i in keys.indices) {
                if (rects[i].contains(e.x.toInt(), e.y.toInt())) {
                    if (keys[i].enabled) { playSoundEffect(android.view.SoundEffectConstants.CLICK); onKey?.invoke(keys[i]) }
                    return true
                }
                }
        }
        return true
    }
}

const val KEYBAR_H = 46

// ---------------------------------------------------------------------------
// Palette aliases so callers can say Colors.accent
// ---------------------------------------------------------------------------
object Colors
