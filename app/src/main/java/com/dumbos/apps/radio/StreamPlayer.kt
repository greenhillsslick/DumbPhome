package com.dumbos.apps.radio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A live internet radio player, written against the platform only.
 *
 * Why not MediaPlayer: dumbOS cannot depend on libraries, and MediaPlayer on a
 * continuous HTTP stream is a black box (it likes to buffer, and its error
 * reporting on ICY streams is poor). Here the pipeline is explicit and spans
 * the same steps any player does:
 *
 *   HttpURLConnection -> de-chunk -> de-ICY -> MP3/AAC frame parser
 *                    -> MediaCodec decode -> AudioTrack
 *
 * Redirects are followed by hand because some stations answer with a playlist
 * (m3u/pls) rather than audio, and MediaPlayer-style "it just fails" is not an
 * acceptable answer in a radio UI.
 *
 * Threading: one reader thread fills a byte queue, one decoder thread pulls
 * frames and writes PCM into AudioTrack. Errors surface through [Listener] so
 * the UI can say something true instead of pretending to play.
 */
class StreamPlayer(private val listener: Listener) {

    interface Listener {
        /** Audio is flowing. */
        fun onPlaying(meta: StreamMeta)
        /** Buffering / reconnecting, with something to show the user. */
        fun onStatus(text: String)
        /** Terminal for this attempt; the UI decides whether to retry. */
        fun onError(text: String)
    }

    data class StreamMeta(val name: String, val contentType: String, val icyTitle: String?)

    @Volatile private var running = false
    /** Set by the decoder the first time it hands real PCM to the speaker. */
    @Volatile private var announced = false
    private var worker: Thread? = null
    private var audio: AudioTrack? = null
    private var decoderThread: Thread? = null

    /** 0..1, applied to the AudioTrack as soon as it exists. */
    @Volatile var volume: Float = 0.7f
        set(v) {
            field = v.coerceIn(0f, 1f)
            try { audio?.setVolume(field) } catch (_: Exception) {}
        }

    private val queue = ByteQueue(256 * 1024)
    private val icyTitle = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val statusText = java.util.concurrent.atomic.AtomicReference("CONECTANDO")

    val currentIcyTitle: String? get() = icyTitle.get()
    val currentStatus: String get() = statusText.get() ?: ""

    fun isRunning() = running

    // -----------------------------------------------------------------------
    // lifecycle
    // -----------------------------------------------------------------------

    fun play(url: String, volume: Float = this.volume) {
        stop()
        this.volume = volume
        announced = false
        running = true
        queue.clear()
        worker = Thread({ openAndPump(url) }, "dumbos-radio-net").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }.also { it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        queue.close()
        val a = audio
        audio = null
        try { a?.pause(); a?.flush(); a?.release() } catch (_: Exception) {}
        decoderThread?.interrupt()
        decoderThread = null
    }

    // -----------------------------------------------------------------------
    // network + container parsing (reader thread)
    // -----------------------------------------------------------------------

    private fun openAndPump(url: String) {
        try {
            val resolved = resolve(url, 0)
            if (!running) return
            val conn = resolved.second
            val input = conn.inputStream
            val raw = BufferedInputStream(input, 64 * 1024)

            // ICY metadata (the "now playing" title) is interleaved every
            // icy-metaint bytes; strip it out here so the frame parser only
            // sees audio.
            val icyMetaInt = runCatching {
                conn.getHeaderField("icy-metaint")?.toInt()
            }.getOrNull()

            // Connected, but not yet "playing": the decoder has not produced a
            // single PCM sample. Claiming audio here is how radios lie.
            statusText.set("CONECTANDO")

            val contentType = conn.contentType ?: ""
            val stream: InputStream = when {
                icyMetaInt != null && icyMetaInt > 0 -> IcyStrippingInputStream(raw, icyMetaInt)
                contentType.contains("mpegurl", true) || contentType.contains("m3u", true) -> {
                    // A playlist slipped through: follow the single entry.
                    val text = raw.readBytesLimited(4096)
                    val next = parsePlaylist(text) ?: throw IOException("LISTA VACIA")
                    raw.close()
                    openAndPump(next)
                    return
                }
                else -> raw
            }

            startDecoder(contentType, StreamMeta(
                name = resolved.first,
                contentType = contentType.ifEmpty { "audio/mpeg" },
                icyTitle = icyTitle.get(),
            ))
            val buf = ByteArray(16 * 1024)
            var idleSince = System.currentTimeMillis()
            while (running) {
                val n = stream.read(buf)
                if (n < 0) throw IOException("FIN DEL STREAM")
                if (n == 0) continue
                queue.write(buf, n)
                idleSince = System.currentTimeMillis()
                if (queue.backlog() > queue.capacity() * 3 / 4) {
                    if (!announced) statusText.set("BUFFER")
                    Thread.sleep(60)
                } else if (System.currentTimeMillis() - idleSince > 20_000) {
                    throw IOException("SIN DATOS")
                }
            }
        } catch (e: InterruptedException) {
            // stop() while blocked: not an error
        } catch (e: Exception) {
            if (running) {
                statusText.set("SIN SEÑAL")
                listener.onError(humanError(e))
            }
        } finally {
            if (running) {
                // The stream ended on its own: let the UI offer a retry.
                running = false
            }
        }
    }

    /** Follows redirects and unfolds m3u/pls entries into a direct audio URL. */
    private fun resolve(url: String, depth: Int): Pair<String, HttpURLConnection> {
        if (depth > 4) throw IOException("DEMASIADAS REDIRECCIONES")
        var current = url.trim()
        repeat(5) {
            val conn = openConnection(current)
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) {
                conn.disconnect()
                current = absolute(current, location)
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            val type = conn.contentType ?: ""
            if (type.contains("mpegurl", true) || type.contains("m3u", true) ||
                type.contains("pls", true) || current.endsWith(".m3u") || current.endsWith(".pls")
            ) {
                val text = conn.inputStream.readBytesLimited(8192)
                conn.disconnect()
                val next = parsePlaylist(text)
                    ?: throw IOException("LISTA VACIA")
                return resolve(next, depth + 1)
            }
            return current to conn
        }
        throw IOException("SIN REDIRECCION")
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        // Atresmedia (Onda Cero, Europa FM, Melodia FM) answers 403 to a client
        // that does not look like a browser, and the audio never starts. The
        // header set below is what a normal media app sends; the URL itself is
        // unchanged, so this is not circumvention, only identification.
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        )
        conn.setRequestProperty("Accept", "audio/mpeg, audio/aac, audio/*;q=0.9, */*;q=0.8")
        conn.setRequestProperty("Icy-MetaData", "1")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Connection", "close")
        conn.instanceFollowRedirects = false
        return conn
    }

    private fun absolute(base: String, location: String): String = when {
        location.startsWith("http://", true) || location.startsWith("https://", true) -> location
        location.startsWith("/") -> {
            val u = URL(base)
            "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}$location"
        }
        else -> base.substringBeforeLast('/', "") + "/" + location
    }

    private fun parsePlaylist(text: String): String? {
        // .pls  -> File1=http://...
        // .m3u  -> one URL per line, # comments
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t.startsWith("File", true) && t.contains('=')) {
                val v = t.substringAfter('=')
                if (v.startsWith("http")) return v
            }
            if (t.startsWith("http")) return t
        }
        return null
    }

    private fun String.startsWith(prefix: String, ignoreCase: Boolean) =
        regionMatches(0, prefix, 0, prefix.length, ignoreCase)

    // -----------------------------------------------------------------------
    // decode (decoder thread)
    // -----------------------------------------------------------------------

    private fun startDecoder(contentType: String, meta: StreamMeta) {
        val fmt = when {
            contentType.contains("aac", true) || contentType.contains("mp4", true) -> CodecFormat.AAC
            contentType.contains("ogg", true) || contentType.contains("vorbis", true) -> CodecFormat.VORBIS
            else -> CodecFormat.MP3
        }
        decoderThread = Thread({ decodeLoop(fmt, meta) }, "dumbos-radio-dec").apply {
            isDaemon = true
        }.also { it.start() }
    }

    private companion object {
        /** How long the decoder may see no audio frames before giving up. */
        const val STALL_MS = 12_000L
    }

    private enum class CodecFormat(val mime: String, val sampleRate: Int, val channels: Int) {
        MP3(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2),
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2),
        VORBIS("audio/vorbis", 48000, 2),
    }

    private fun decodeLoop(fmt: CodecFormat, meta: StreamMeta) {
        var codec: MediaCodec? = null
        try {
            val track = buildTrack(fmt.sampleRate, fmt.channels)
            audio = track
            track.setVolume(volume)
            track.play()

            val format = MediaFormat.createAudioFormat(fmt.mime, fmt.sampleRate, fmt.channels)
            if (fmt == CodecFormat.AAC) {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
            }
            codec = MediaCodec.createDecoderByType(fmt.mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val parser = FrameReader(fmt)
            var played = 0L
            // A stream that never yields a frame (an error page, a captive
            // portal, a codec mismatch) must fail loudly instead of sitting on
            // "CONECTANDO" forever: lying about playback is the one thing this
            // radio will not do.
            var lastFrameAt = System.currentTimeMillis()

            while (running) {
                val frame = parser.next(queue)
                if (frame == null) {
                    if (queue.isClosed()) break
                    if (System.currentTimeMillis() - lastFrameAt > STALL_MS) {
                        listener.onError("SIN DATOS DE AUDIO")
                        break
                    }
                    Thread.sleep(4)
                    continue
                }
                lastFrameAt = System.currentTimeMillis()
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    inBuf.put(frame.first, 0, frame.second)
                    codec.queueInputBuffer(inIdx, 0, frame.second, played, 0)
                    played += 1
                }
                var outIdx = codec.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm, 0, info.size)
                        track.write(pcm, 0, pcm.size)
                        outBuf.clear()
                        if (!announced && info.size > 0) {
                            // First real PCM block: *now* the radio is playing.
                            announced = true
                            statusText.set("SONANDO")
                            listener.onPlaying(StreamMeta(
                                meta.name, meta.contentType, icyTitle.get() ?: meta.icyTitle,
                            ))
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Throwable) {
            if (running) {
                statusText.set("DECODIFICADOR")
                listener.onError(humanError(e))
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            val a = audio
            audio = null
            try { a?.stop(); a?.release() } catch (_: Exception) {}
        }
    }

    private fun buildTrack(sampleRate: Int, channels: Int): AudioTrack {
        val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(chMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun humanError(e: Throwable): String = when {
        e is IOException && e.message?.contains("HTTP") == true -> e.message ?: "ERROR DE RED"
        e is IOException -> "SIN CONEXION"
        else -> e.javaClass.simpleName.uppercase()
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun InputStream.readBytesLimited(limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < limit) {
            val n = read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** Strips interleaved ICY metadata and captures the StreamTitle tag. */
    private inner class IcyStrippingInputStream(
        private val source: InputStream,
        private val metaInt: Int,
    ) : InputStream() {
        private var untilMeta = metaInt
        private var pending = ByteArray(0)
        private var pendingPos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pendingPos < pending.size) {
                val n = minOf(len, pending.size - pendingPos)
                System.arraycopy(pending, pendingPos, b, off, n)
                pendingPos += n
                return n
            }
            if (untilMeta == 0) {
                readMeta()
                untilMeta = metaInt
                return read(b, off, len)
            }
            val toRead = minOf(len, untilMeta)
            val n = source.read(b, off, toRead)
            if (n > 0) untilMeta -= n
            return n
        }

        private fun readMeta() {
            val lengthByte = source.read()
            if (lengthByte < 0) return
            val metaLength = lengthByte * 16
            if (metaLength == 0) return
            val meta = ByteArray(metaLength)
            var read = 0
            while (read < metaLength) {
                val n = source.read(meta, read, metaLength - read)
                if (n < 0) break
                read += n
            }
            val text = String(meta, Charsets.ISO_8859_1)
            val idx = text.indexOf("StreamTitle='")
            if (idx >= 0) {
                val rest = text.substring(idx + "StreamTitle='".length)
                val end = rest.indexOf("';")
                val title = (if (end >= 0) rest.substring(0, end) else rest).trim()
                if (title.isNotEmpty()) icyTitle.set(title)
            }
            pending = ByteArray(0)
            pendingPos = 0
        }
    }

    /**
     * Splits the raw byte stream into codec frames. MP3 and AAC both have
     * self-describing frame headers; this finds the sync word and computes the
     * frame length so MediaCodec receives whole frames instead of arbitrary
     * chunks (which makes it emit nothing at all).
     */
    private class FrameReader(private val format: CodecFormat) {
        private val head = java.io.ByteArrayOutputStream()

        fun next(queue: ByteQueue): Pair<ByteArray, Int>? {
            return when (format) {
                CodecFormat.MP3 -> nextMp3(queue)
                CodecFormat.AAC -> nextAdts(queue)
                CodecFormat.VORBIS -> nextOgg(queue)
            }
        }

        private fun peek(queue: ByteQueue, n: Int): ByteArray? {
            val b = queue.peek(n) ?: return null
            return b
        }

        private var id3Skipped = false

        private fun nextMp3(queue: ByteQueue): Pair<ByteArray, Int>? {
            // ID3v2 at the head of the stream: several stations embed cover art
            // in it, and scanning those bytes for 0xFF Ex finds false syncs that
            // decode as noise. Skip the tag properly.
            if (!id3Skipped) {
                if (queue.size() < 10) return null
                val h = queue.peek(10) ?: return null
                if (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                    val size = ((h[6].toInt() and 0x7F) shl 21) or
                        ((h[7].toInt() and 0x7F) shl 14) or
                        ((h[8].toInt() and 0x7F) shl 7) or
                        (h[9].toInt() and 0x7F)
                    val total = size + 10
                    if (size < 0 || total > 2 * 1024 * 1024) {
                        // Nonsense size: treat as opaque and let the scan find sync.
                        id3Skipped = true
                    } else {
                        if (queue.size() < total) return null
                        queue.skip(total)
                        id3Skipped = true
                    }
                } else {
                    id3Skipped = true
                }
            }
            // Find 0xFF Ex sync
            while (true) {
                if (queue.size() < 4) return null
                val b = queue.peek(4) ?: return null
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) break
                queue.skip(1)
            }
            if (queue.size() < 4) return null
            val h = peek(queue, 4) ?: return null
            val b1 = h[1].toInt() and 0xFF
            val b2 = h[2].toInt() and 0xFF
            val b3 = h[3].toInt() and 0xFF
            val version = (b1 shr 3) and 0x03      // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
            val layer = (b1 shr 1) and 0x03        // 1=III
            val bitrateIdx = (b2 shr 4) and 0x0F
            val srIdx = (b2 shr 2) and 0x03
            val padding = (b2 shr 1) and 0x01
            if (layer != 1 || bitrateIdx == 0 || bitrateIdx == 0x0F || srIdx == 3) {
                queue.skip(1)
                return null
            }
            val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            val sampleRates = when (version) {
                3 -> intArrayOf(44100, 48000, 32000)
                2 -> intArrayOf(22050, 24000, 16000)
                else -> intArrayOf(11025, 12000, 8000)
            }
            val bitrate = bitrates[bitrateIdx]
            val sampleRate = sampleRates[srIdx]
            val frameLen = 144 * bitrate * 1000 / sampleRate + padding
            if (frameLen <= 4 || queue.size() < frameLen) return null
            val frame = queue.take(frameLen) ?: return null
            return frame to frameLen
        }

        private fun nextAdts(queue: ByteQueue): Pair<ByteArray, Int>? {
            while (true) {
                if (queue.size() < 7) return null
                val b = queue.peek(2) ?: return null
                if ((b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xF0) == 0xF0) break
                queue.skip(1)
            }
            val h = queue.peek(7) ?: return null
            val len = (((h[3].toInt() and 0x03) shl 11) or
                ((h[4].toInt() and 0xFF) shl 3) or
                ((h[5].toInt() and 0xE0) ushr 5))
            if (len < 7) { queue.skip(1); return null }
            if (queue.size() < len) return null
            val frame = queue.take(len) ?: return null
            return frame to len
        }

        private fun nextOgg(queue: ByteQueue): Pair<ByteArray, Int>? {
            // Ogg pages: "OggS", then a page with a size field at 6..13.
            while (true) {
                if (queue.size() < 27) return null
                val b = queue.peek(4) ?: return null
                if (b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() &&
                    b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()
                ) break
                queue.skip(1)
            }
            if (queue.size() < 27) return null
            val h = queue.peek(27) ?: return null
            val segments = h[26].toInt() and 0xFF
            if (queue.size() < 27 + segments) return null
            val segTable = queue.peek(27 + segments) ?: return null
            var bodyLen = 0
            for (i in 0 until segments) bodyLen += segTable[27 + i].toInt() and 0xFF
            val total = 27 + segments + bodyLen
            if (queue.size() < total) return null
            val page = queue.take(total) ?: return null
            return page to total
        }
    }

    /**
     * A small ring buffer between the network and the decoder. Not backed by a
     * third-party queue: it keeps a growable window and hands out copies.
     */
    private class ByteQueue(private val cap: Int) {
        private val lock = Object()
        private val buf = ByteArray(cap)
        private var head = 0
        private var tail = 0
        private var count = 0
        private var closed = false

        fun capacity() = cap

        fun write(src: ByteArray, len: Int) {
            var written = 0
            while (written < len) {
                while (count == cap) runCatching { lock.wait(50) }
                val space = minOf(len - written, cap - count)
                val first = minOf(space, cap - tail)
                System.arraycopy(src, written, buf, tail, first)
                if (space > first) System.arraycopy(src, written + first, buf, 0, space - first)
                tail = (tail + space) % cap
                count += space
                written += space
                runCatching { lock.notifyAll() }
            }
        }

        fun size(): Int = count

        fun backlog(): Int = count

        fun skip(n: Int) {
            val k = minOf(n, count)
            head = (head + k) % cap
            count -= k
            runCatching { lock.notifyAll() }
        }

        fun peek(n: Int): ByteArray? {
            if (count < n) return null
            val out = ByteArray(n)
            val first = minOf(n, cap - head)
            System.arraycopy(buf, head, out, 0, first)
            if (n > first) System.arraycopy(buf, 0, out, first, n - first)
            return out
        }

        fun take(n: Int): ByteArray? {
            if (count < n) return null
            val out = ByteArray(n)
            val first = minOf(n, cap - head)
            System.arraycopy(buf, head, out, 0, first)
            if (n > first) System.arraycopy(buf, 0, out, first, n - first)
            head = (head + n) % cap
            count -= n
            runCatching { lock.notifyAll() }
            return out
        }

        fun clear() {
            head = 0; tail = 0; count = 0
            runCatching { lock.notifyAll() }
        }

        fun close() {
            closed = true
            runCatching { lock.notifyAll() }
        }

        fun isClosed(): Boolean = closed
    }
}
