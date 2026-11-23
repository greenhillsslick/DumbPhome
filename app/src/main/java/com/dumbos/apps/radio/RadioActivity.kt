package com.dumbos.apps.radio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dumbos.core.Prefs
import com.dumbos.ui.Dp
import com.dumbos.ui.HRule
import com.dumbos.ui.OsActivity
import com.dumbos.ui.Sz
import com.dumbos.ui.TYPO
import com.dumbos.ui.Theme
import com.dumbos.ui.vcol
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Radio FM. Android exposes no public FM tuner API, so this is an honest
 * tuning dial: a draggable analogue scale, station memory, and a synthesised
 * "carrier" whose static falls away as you approach a memorised frequency.
 *
 * The dial is the point: a rotary band you drag with a finger, exactly like
 * tuning a 3310's headphone-cable antenna.
 */
class RadioActivity : OsActivity() {

    private val stations = listOf(
        Station(87.6f, "RADIO VALENCIA", "MUSICA Y NOTICIAS"),
        Station(89.2f, "CADENA SOL", "EXITOS DE LOS 80"),
        Station(91.5f, "FM 91", "POP INTERNACIONAL"),
        Station(94.8f, "RADIO CLASICA", "CONCIERTOS Y OPERA"),
        Station(98.3f, "LOS40", "LISTA ACTUAL"),
        Station(101.1f, "ROCK FM", "GUITARRAS"),
        Station(103.7f, "RADIO MAR", "LOCAL, TODO EL DIA"),
        Station(106.4f, "NOCTURNA", "JAZZ DE MADRUGADA"),
    )

    private var freq = 87.6f
    private var dial: RadioDial? = null
    private var playing = false
    private var tone: Carrier? = null
    private var volume = 0.6f
    private var favorites = ArrayList<Float>()

    private typealias Station = StationLookup

    override fun buildLayout() {
        titleText = "RADIO FM"
        setKeys("ENCENDER", "EMISORAS")
        val p = prefs
        favorites = p.let { _ ->
            val sp = getSharedPreferences("dumbos.radio", MODE_PRIVATE)
            val raw = sp.getString("fav", "87.6,98.3,103.7") ?: ""
            ArrayList(raw.split(',').mapNotNull { it.toFloatOrNull() })
        }
        val col = vcol(this, 0)
        content.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = RadioDial(this).also {
            it.theme = theme
            it.freq = freq
            it.onChange = { f -> freq = f; tune(f) }
            it.nearestAt = { f -> nearestTo(f)?.let { s -> StationLookup(s.freq, s.name, s.desc) } }
        }
        dial = d
        col.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(HRule(this, theme.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Dp.dp(3)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Dp.dp(8), Dp.dp(6), Dp.dp(8), Dp.dp(4))
        }
        col.addView(body)
        keyTarget = object : com.dumbos.ui.OsView {
            override fun moveSel(delta: Int): Boolean {
                freq = (freq + delta * 0.1f).coerceIn(87.5f, 108f)
                dial?.freq = freq
                dial?.invalidate()
                tune(freq)
                return true
            }
            override fun activate(): Boolean { toggle(); return true }
            override fun goBack() = false
        }
        volume = getSharedPreferences("dumbos.radio", MODE_PRIVATE).getFloat("vol", 0.6f)
        refreshInfo()
    }

    private fun refreshInfo() {
        dial?.invalidate()
    }

    private fun nearest(): Station? = nearestTo(freq)

    private fun nearestTo(f: Float): Station? = stations.minByOrNull { abs(it.freq - f) }?.takeIf { abs(it.freq - f) < 0.45f }

    private fun tune(f: Float) {
        val st = nearest()
        tone?.retune(f, st != null)
        if (playing) tone?.fade(volume)
        dial?.invalidate()
    }

    private fun toggle() {
        playing = !playing
        if (playing) {
            if (tone == null) tone = Carrier(freq, nearest() != null)
            tone?.fade(volume)
            tone?.start()
        } else {
            tone?.stopWithFade()
        }
        dial?.invalidate()
    }

    override fun openMenu() {
        val list = ArrayList<String>()
        stations.forEach { list.add(String.format("%.1f", it.freq) + " " + it.name) }
        list.add("GUARDAR FAVORITA")
        list.add("VOLUMEN")
        showMenu("EMISORAS", list) { i ->
            if (i < stations.size) {
                freq = stations[i].freq
                dial?.freq = freq
                dial?.invalidate()
                tune(freq)
                if (!playing) toggle()
            } else if (i == stations.size) {
                if (!favorites.contains(freq)) favorites.add(freq)
                val sp = getSharedPreferences("dumbos.radio", MODE_PRIVATE)
                sp.edit().putString("fav", favorites.joinToString(",")).apply()
                toast("GUARDADA " + String.format("%.1f", freq))
            } else {
                showMenu("VOLUMEN", listOf("BAJO", "MEDIO", "ALTO", "SILENCIO")) { v ->
                    volume = listOf(0.25f, 0.55f, 0.9f, 0f)[v]
                    getSharedPreferences("dumbos.radio", MODE_PRIVATE).edit().putFloat("vol", volume).apply()
                    tone?.fade(volume)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (playing) { playing = false; tone?.stopWithFade(); tone = null }
    }

    override fun onCreateOs(savedInstanceState: Bundle?) {}
}

/** Minimal station descriptor shared with the dial view. */
data class StationLookup(val freq: Float, val name: String, val desc: String)

/**
 * The tuning scale. A horizontal band with a fixed pointer: dragging the band
 * moves the frequency, a tap snaps to the nearest tenth.
 */
class RadioDial(ctx: Context) : View(ctx) {

    var theme: Theme = com.dumbos.ui.PALETTES[0]
        set(v) { field = v; invalidate() }
    var freq: Float = 87.6f
        set(v) { field = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var nearestAt: (Float) -> StationLookup? = { null }

    /** Starred frequency shown under the readout, if any. */
    var stationLabel: String = ""

    private var dragging = false


    override fun onDraw(canvas: Canvas) {
        val p = Paint().apply { isAntiAlias = false }
        p.color = theme.bg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

        val centerY = height / 3
        // scale: 87.5 .. 108.0 mapped to the width with margins
        val margin = Dp.dp(14)
        fun xOf(f: Float) = margin + (width - margin * 2) * ((f - 87.5f) / (108f - 87.5f))
        fun fOf(x: Float) = 87.5f + (x - margin) / (width - margin * 2).toFloat() * (108f - 87.5f)

        p.color = theme.dim
        canvas.drawRect(margin.toFloat(), (centerY - Dp.dp(22)).toFloat(), (width - margin).toFloat(), (centerY - Dp.dp(22) + 1).toFloat(), p)
        canvas.drawRect(margin.toFloat(), (centerY + Dp.dp(22)).toFloat(), (width - margin).toFloat(), (centerY + Dp.dp(22) + 1).toFloat(), p)

        // ticks every 0.5 MHz, tall every 1 MHz, labels every 2
        var f = 87.5f
        while (f <= 108.01f) {
            val x = xOf(f)
            val whole = (f * 10f).toInt() % 10 == 0
            val h = if (whole) Dp.dp(10) else Dp.dp(5)
            p.color = if (abs(f - freq) < 0.25f) theme.ink else theme.dim
            canvas.drawRect(x, (centerY + Dp.dp(22) - h).toFloat(), x + 1f, (centerY + Dp.dp(22)).toFloat(), p)
            if (whole && f.toInt() % 2 == 0) {
                TYPO.drawCenter(canvas, f.toInt().toString(), x.toInt(), centerY + Dp.dp(26), Sz.SMALL, theme.dim)
            }
            f += 0.5f
        }

        // pointer
        val px = xOf(freq)
        p.color = theme.ink
        canvas.drawRect(px - 1f, (centerY - Dp.dp(30)).toFloat(), px + 1f, (centerY + Dp.dp(30)).toFloat(), p)
        // diamond-ish head
        for (i in 0..6) {
            canvas.drawRect(px - i, (centerY - Dp.dp(34) + i).toFloat(), px + i + 1f, (centerY - Dp.dp(33) + i).toFloat(), p)
        }

        // big frequency readout
        val txt = String.format("%.1f", freq)
        TYPO.drawCenter(canvas, txt, width / 2, Dp.dp(6), Sz.TITLE, theme.ink)
        TYPO.drawCenter(canvas, "MHZ", width / 2 + TYPO.measure(txt, Sz.TITLE) / 2 + Dp.dp(14), Dp.dp(6) + Sz.TITLE.lineH() - Sz.SMALL.lineH(), Sz.SMALL, theme.dim)

        // signal bars: how close the pointer is to a memorised station
        val near = nearestAt(freq)
        val strength = if (near == null) 0f else max(0f, 1f - abs(near.freq - freq) / 0.45f)
        val bx = width - Dp.dp(44)
        for (i in 0 until 5) {
            val hh = Dp.dp(2) * (i + 1)
            p.color = if (strength * 5 > i) theme.ink else theme.dim
            canvas.drawRect((bx + i * Dp.dp(5)).toFloat(), (centerY - Dp.dp(10) - hh).toFloat(),
                (bx + i * Dp.dp(5) + Dp.dp(2)).toFloat(), (centerY - Dp.dp(10)).toFloat(), p)
        }
        // static line under the scale: noise amplitude shows detune
        val noise = (1f - strength)
        if (noise > 0.02f) {
            val rnd = Random(freq.toInt() * 31)
            var x = margin
            p.color = theme.dim
            while (x < width - margin) {
                val h = (rnd.nextFloat() * noise * Dp.dp(10)).toInt()
                canvas.drawRect(x.toFloat(), (centerY + Dp.dp(38) - h).toFloat(), (x + Dp.dp(2)).toFloat(), (centerY + Dp.dp(38)).toFloat(), p)
                x += Dp.dp(3)
            }
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val margin = Dp.dp(14)
        fun fOf(x: Float) = 87.5f + (x - margin) / (width - margin * 2).toFloat() * (108f - 87.5f)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                freq = ((fOf(e.x) * 10).toInt() / 10f).coerceIn(87.5f, 108f)
                onChange?.invoke(freq)
                return true
            }
        }
        return true
    }
}

/**
 * The "carrier": a low-volume synthesised tone plus filtered noise, generated
 * on a background thread and mixed through AudioTrack. It is deliberately
 * quiet, and it stops when the app is not in front.
 */
class Carrier(private var freq: Float, private var locked: Boolean) {

    private val sampleRate = 22050
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var targetGain = 0f
    @Volatile private var gain = 0f
    @Volatile private var noiseLevel = 0.5f

    fun retune(f: Float, hasStation: Boolean) {
        freq = f
        locked = hasStation
    }

    fun fade(volume: Float) {
        targetGain = if (locked) volume * 0.05f else volume * 0.12f
        noiseLevel = if (locked) 0.06f else 0.5f
    }

    fun start() {
        if (running) return
        running = true
        fade(0.6f)
        thread = Thread {
            val bufSize = 1024
            val buf = ShortArray(bufSize)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            try {
                t.play()
                var phase = 0.0
                while (running) {
                    for (i in 0 until bufSize) {
                        val carrier = sin(phase).toFloat() * (if (locked) 0.6f else 0.15f)
                        phase += 2 * PI * (if (locked) 220.0 + (freq - 87.5f) * 3 else 180.0) / sampleRate
                        if (phase > 2 * PI) phase -= 2 * PI
                        val n = (Random.nextFloat() * 2 - 1) * noiseLevel
                        gain += (targetGain - gain) * 0.0008f
                        val sample = (carrier + n.toFloat()) * gain
                        buf[i] = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                    t.write(buf, 0, bufSize)
                }
            } catch (_: Exception) {
            } finally {
                try { t.stop(); t.release() } catch (_: Exception) {}
                track = null
            }
        }.apply { isDaemon = true }
        thread?.start()
    }

    fun stopWithFade() {
        targetGain = 0f
        Thread {
            Thread.sleep(180)
            running = false
        }.apply { isDaemon = true }.start()
    }
}
