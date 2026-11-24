package com.dumbos.apps.radio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.OsActivity
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Radio FM.
 *
 * Android has no public FM tuner API (there is no android.hardware.fm package
 * and no tuner class in the SDK), so no app can honestly promise to receive
 * broadcast radio on a phone without the vendor's private SDK. What this screen
 * does instead is receive the stations over the network and keep the analogue
 * metaphor: a draggable dial where each memorised frequency *is* a real station
 * stream. Same gesture, real audio.
 *
 * Streams are decoded with MediaCodec and played through AudioTrack by
 * [StreamPlayer], with no third-party libraries.
 */
class RadioActivity : OsActivity() {

    private var dial: RadioDial? = null
    private var player: StreamPlayer? = null
    private var playing = false
    private var buffering = false
    private var freq = Stations.ALL.first().freq
    private var volume = 0.7f
    private var stateText = "PULSA OK PARA SONAR"
    private var nowPlaying = ""
    private var errorText = ""
    private val handler = Handler(Looper.getMainLooper())

    /** Repaints the small text strip without rebuilding the view tree. */
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && playing) {
                val icy = p.currentIcyTitle
                if (!icy.isNullOrEmpty() && icy != nowPlaying) {
                    nowPlaying = icy
                    refreshInfo()
                }
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun buildLayout() {
        titleText = "RADIO FM"
        setKeys("ENCENDER", "EMISORAS")
        volume = prefs.let {
            getSharedPreferences("dumbos.radio", MODE_PRIVATE).getFloat("vol", 0.7f)
        }

        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val d = RadioDial(this).also {
            it.theme = theme
            it.freq = freq
            it.onChange = { f -> setFreq(f) }
            it.nearestAt = { f ->
                Stations.nearest(f)?.let { s -> StationLookup(s.freq, s.name, s.desc) }
            }
        }
        dial = d

        // The dial gets most of the height: it is the interface.
        col.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        col.addView(
            InfoPanel(this).also { it.activity = this },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(96)),
        )

        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int): Boolean {
                // Moving left/right on the pad tunes in 0.1 MHz steps and, when a
                // station is reached, switches the stream to it.
                setFreq((freq + delta * 0.1f).coerceIn(87.5f, 108f))
                return true
            }
            override fun activate(): Boolean { toggle(); return true }
            override fun goBack() = false
        }
        refreshInfo()
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {
        handler.post(ticker)
    }

    // ---------------------------------------------------------------------

    /** Single entry point for every tuning change, drag included. */
    private fun setFreq(f: Float) {
        val before = Stations.nearest(freq)?.key
        freq = f
        dial?.freq = f
        dial?.invalidate()
        // A drag crosses many tenths inside one station's window: only touch the
        // network when the station under the pointer actually changes.
        if (Stations.nearest(f)?.key != before || !playing) tune(f)
        refreshInfo()
    }

    private fun tune(f: Float) {
        val station = Stations.nearest(f)
        if (!playing) {
            errorText = ""
            stateText = if (station != null) "OK PARA SONAR" else "ENTRE EMISORAS"
            refreshInfo()
            return
        }
        // Tuning while playing means "switch to whatever is here", which is the
        // behaviour of a real tuner: silence between stations, audio on one.
        if (station == null) {
            stopPlayback("ENTRE EMISORAS")
        } else {
            startPlayback(station)
        }
    }

    private fun toggle() {
        if (playing) {
            stopPlayback("PARADO")
        } else {
            val station = Stations.nearest(freq)
            if (station == null) {
                toast("SINTONIZA UNA EMISORA")
                stateText = "SIN EMISORA"
                refreshInfo()
            } else {
                startPlayback(station)
            }
        }
    }

    private fun startPlayback(station: Station) {
        player?.stop()
        errorText = ""
        nowPlaying = ""
        buffering = true
        playing = true
        stateText = "CONECTANDO"
        refreshInfo()

        val p = StreamPlayer(object : StreamPlayer.Listener {
            override fun onPlaying(meta: StreamPlayer.StreamMeta) {
                handler.post {
                    buffering = false
                    stateText = "SONANDO"
                    nowPlaying = meta.icyTitle ?: ""
                    refreshInfo()
                }
            }

            override fun onStatus(text: String) {
                handler.post {
                    buffering = text == "BUFFER"
                    stateText = text
                    refreshInfo()
                }
            }

            override fun onError(text: String) {
                handler.post {
                    // Do not silently pretend: say what went wrong and stay stopped.
                    playing = false
                    buffering = false
                    errorText = text
                    stateText = "SIN SEÑAL"
                    player?.stop()
                    refreshInfo()
                }
            }
        })
        player = p
        p.play(station.url, volume)
    }

    private fun stopPlayback(reason: String) {
        player?.stop()
        player = null
        playing = false
        buffering = false
        nowPlaying = ""
        stateText = reason
        refreshInfo()
    }

    private fun refreshInfo() {
        dial?.invalidate()
        (content.getChildAt(0)?.let { root ->
            if (root is LinearLayout && root.childCount > 2) (root.getChildAt(2) as? InfoPanel)?.invalidate()
        })
    }

    override fun openMenu() {
        val list = ArrayList<String>()
        Stations.ALL.forEach { list.add(String.format("%.1f", it.freq) + " " + it.name) }
        list.add("VOLUMEN")
        showMenu("EMISORAS", list) { i ->
            when (i) {
                in 0 until Stations.ALL.size -> {
                    val station = Stations.ALL[i]
                    setFreq(station.freq)
                    startPlayback(station)
                }
                Stations.ALL.size -> {
                    showMenu("VOLUMEN", listOf("BAJO", "MEDIO", "ALTO", "SILENCIO")) { v ->
                        volume = listOf(0.3f, 0.6f, 1f, 0f)[v]
                        getSharedPreferences("dumbos.radio", MODE_PRIVATE)
                            .edit().putFloat("vol", volume).apply()
                        // Applies immediately, mid-stream.
                        player?.volume = volume
                        toast("VOLUMEN " + listOf("BAJO", "MEDIO", "ALTO", "SILENCIO")[v])
                    }
                }
                else -> toast("DUMBOS NO GUARDA NADA EN LA NUBE")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen stops the audio: a radio that keeps playing while
        // you are elsewhere in a dumbphone shell is a surprise, not a feature.
        if (playing) stopPlayback("PARADO")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        player?.stop()
        player = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    /** The text strip under the dial: state, station, now-playing. */
    private class InfoPanel(ctx: Context) : View(ctx) {
        lateinit var activity: RadioActivity

        override fun onDraw(canvas: Canvas) {
            val theme: Theme = activity.theme
            val p = Paint().apply { isAntiAlias = false; color = theme.panel }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = theme.dim
            canvas.drawRect(0f, 0f, width.toFloat(), 1f, p)
            TYPO.drawClipped(canvas, activity.statusLine(), Dp.dp(8), Dp.dp(7), width - Dp.dp(16), Sz.BODY, theme.ink)
            TYPO.drawClipped(canvas, activity.subLine(), Dp.dp(8), Dp.dp(24), width - Dp.dp(16), Sz.SMALL, theme.dim)
            TYPO.drawClipped(canvas, "OK SONAR/ PARAR   < > SINTONIZAR", Dp.dp(8), Dp.dp(40), width - Dp.dp(16), Sz.SMALL, theme.dim)
            val station = Stations.nearest(activity.freq)
            if (station != null) {
                TYPO.drawClipped(canvas, station.name, Dp.dp(8), Dp.dp(56), width - Dp.dp(16), Sz.BODY, theme.ink)
            } else {
                TYPO.drawClipped(canvas, "SIN EMISORA EN ESTA FRECUENCIA", Dp.dp(8), Dp.dp(56), width - Dp.dp(16), Sz.SMALL, theme.dim)
            }
            TYPO.drawRight(canvas,
                String.format("%.1f", activity.freq) + " MHZ", width - Dp.dp(8), Dp.dp(8), Sz.SMALL, theme.dim)
        }
    }

    internal fun statusLine(): String {
        val station = Stations.nearest(freq)
        return when {
            errorText.isNotEmpty() -> "SIN SEÑAL: " + errorText
            buffering -> "CONECTANDO..."
            playing && station != null -> "[ " + stateText + " ] " + station.name
            station != null -> stateText + " " + station.desc
            else -> stateText
        }
    }

    internal fun subLine(): String {
        if (nowPlaying.isNotEmpty()) return "AHORA: " + nowPlaying
        val station = Stations.nearest(freq)
        return if (station != null) station.desc else "ARRASTRA EL DIAL"
    }
}

/** Minimal station descriptor shared with the dial view. */
data class StationLookup(val freq: Float, val name: String, val desc: String)

/**
 * The tuning scale. A horizontal band with a fixed pointer: dragging the band
 * moves the frequency, a tap snaps to the nearest tenth. Memorised stations
 * show as ticks with names, so the dial doubles as the station list.
 */
class RadioDial(ctx: Context) : View(ctx) {

    var theme: Theme = com.dumbos.ui.PALETTES[0]
        set(v) { field = v; invalidate() }
    var freq: Float = 87.6f
        set(v) { field = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var nearestAt: (Float) -> StationLookup? = { null }

    private var dragging = false
    private var lastChangeAt = 0L

    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false }
        p.color = theme.bg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        if (width == 0) return

        val centerY = height / 3
        val margin = Dp.dp(14)
        fun xOf(f: Float) = margin + (width - margin * 2) * ((f - 87.5f) / (108f - 87.5f))

        p.color = theme.dim
        canvas.drawRect(margin.toFloat(), (centerY - Dp.dp(22)).toFloat(), (width - margin).toFloat(), (centerY - Dp.dp(22) + 1).toFloat(), p)
        canvas.drawRect(margin.toFloat(), (centerY + Dp.dp(22)).toFloat(), (width - margin).toFloat(), (centerY + Dp.dp(22) + 1).toFloat(), p)

        // ticks every 0.5 MHz, tall every 1 MHz
        var f = 87.5f
        while (f <= 108.01f) {
            val x = xOf(f)
            val whole = (f * 10f).toInt() % 10 == 0
            val h = if (whole) Dp.dp(6) else Dp.dp(3)
            p.color = theme.dim
            canvas.drawRect(x, (centerY - Dp.dp(22)).toFloat(), x + 1f, (centerY - Dp.dp(22) + h).toFloat(), p)
            canvas.drawRect(x, (centerY + Dp.dp(22) - h).toFloat(), x + 1f, (centerY + Dp.dp(22)).toFloat(), p)
            f += 0.5f
        }

        // station markers
        for (s in Stations.ALL) {
            val x = xOf(s.freq)
            p.color = theme.ink
            canvas.drawRect(x, (centerY - Dp.dp(4)).toFloat(), x + Dp.dp(2), (centerY + Dp.dp(4)).toFloat(), p)
        }

        // labels every 3 MHz, plus the station names nearest to the pointer
        var labelF = 87f
        while (labelF <= 108f) {
            val x = xOf(labelF)
            TYPO.drawCenter(canvas, labelF.toInt().toString(), x.toInt(), centerY + Dp.dp(26), Sz.SMALL, theme.dim)
            labelF += 3f
        }

        // pointer
        val px = xOf(freq)
        p.color = theme.ink
        canvas.drawRect(px - Dp.dp(1), (centerY - Dp.dp(30)).toFloat(), px + Dp.dp(1), (centerY + Dp.dp(30)).toFloat(), p)
        canvas.drawRect(px - Dp.dp(4), (centerY - Dp.dp(34)).toFloat(), px + Dp.dp(4), (centerY - Dp.dp(30)).toFloat(), p)

        // big readout
        TYPO.drawCenter(canvas, String.format("%.1f", freq), width / 2, Dp.dp(2), Sz.TITLE, theme.ink)
        val near = nearestAt(freq)
        TYPO.drawCenter(canvas, near?.name ?: "SIN EMISORA", width / 2, Dp.dp(22), Sz.SMALL, theme.dim)

        // small "RADIO" caption at the bottom of the band
        TYPO.drawCenter(canvas, "ARRASTRA EL DIAL", width / 2, height - Dp.dp(12), Sz.SMALL, theme.dim)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val margin = Dp.dp(14)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                val ratio = ((e.x - margin) / max(1, width - margin * 2)).coerceIn(0f, 1f)
                val newFreq = (87.5f + ratio * 20.5f)
                val snapped = Math.round(newFreq * 10f) / 10f
                if (snapped != freq) {
                    freq = snapped
                    onChange?.invoke(snapped)
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                // Snap to the nearest station when one is close: on a phone you
                // cannot stop a drag on 98.3, and that is the whole charm.
                val near = nearestAt(freq)
                if (near != null && abs(near.freq - freq) < 0.9f && near.freq != freq) {
                    freq = near.freq
                    onChange?.invoke(near.freq)
                }
                return true
            }
        }
        return true
    }
}
