package com.dumbos.apps.radio

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper

/**
 * Real FM, via the tuner the phone already has.
 *
 * Android gives third-party apps no way to open the FM chip: there is no
 * `android.hardware.fm` package in the SDK and the vendor hooks are blocked by
 * the non-SDK restrictions. What *is* public is [MediaSessionManager]: the
 * phone's own radio app (Snapdragon FM Radio, Qualcomm's `QtiFmRadio`, the
 * Samsung/LG/Xiaomi tuner apps, and every vendor equivalent) publishes what it
 * is tuned to as a media session.
 *
 * So dumbOS can drive the hardware the honest way: find that session, press
 * play/pause on it, read back where it is tuned and what station it says it is.
 * The audio goes through the phone's real audio path because it is the
 * firmware's own player; dumbOS is the remote control, not a replacement.
 *
 * Every method reports what actually happened. If there is no tuner session,
 * [available] is false and the radio UI offers internet radio instead — it
 * never pretends the aerial is on.
 */
class FmTuner private constructor(
    private val ctx: Context,
    private val manager: MediaSessionManager,
    private val component: ComponentName,
) {

    data class State(
        val playing: Boolean,
        val title: String,
        val subtitle: String,
        val frequency: Float?,
    )

    /** One tuning step: the phone's own radio app, and what it is playing. */
    data class Session(val app: String, val controller: MediaController)

    val label: String get() = appLabel(component.packageName)

    private val listener = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            onChange?.invoke()
        }
    }

    /** Called on the main thread whenever a session appears or disappears. */
    var onChange: (() -> Unit)? = null

    /** Why discovery failed, so the UI can say something actionable. */
    enum class Blocked { NONE, NO_PERMISSION, NO_API }

    // -----------------------------------------------------------------------

    fun current(): Session? {
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (_: SecurityException) {
            return null
        } catch (_: Exception) {
            return null
        }
        val fm = sessions
            .mapNotNull { c -> c.takeIf { isFm(it.packageName) } }
            .firstOrNull() ?: return null
        return Session(appLabel(fm.packageName), fm)
    }

    fun state(): State? {
        val s = current() ?: return null
        val md: MediaMetadata? = s.controller.metadata
        val description = md?.description
        val title = description?.title?.toString().orEmpty()
        val subtitle = description?.subtitle?.toString().orEmpty()
        // MediaMetadata.getExtras() is hidden; the description bundle is public.
        val extras = description?.extras
        // Different vendor apps expose the frequency under different keys; the
        // ones below cover the tuner apps in the wild. All are optional.
        val freqKeys = listOf(
            "android.media.metadata.RADIO_FREQUENCY",
            "RADIO_FREQUENCY",
            "frequency",
            "FREQUENCY",
        )
        var freq: Float? = null
        if (extras != null) {
            for (k in freqKeys) {
                if (extras.containsKey(k)) {
                    val v = extras.get(k)
                    freq = when (v) {
                        is Number -> v.toFloat()
                        is String -> v.toFloatOrNull()
                        else -> null
                    }
                    if (freq != null) break
                }
            }
        }
        // Some apps put "98.8 MHz" in the title instead of an extra.
        if (freq == null) freq = FREQ_IN_TEXT.find(title)?.value?.toFloatOrNull()
            ?: FREQ_IN_TEXT.find(subtitle)?.value?.toFloatOrNull()
        val st = s.controller.playbackState
        val playing = st != null &&
            st.state == android.media.session.PlaybackState.STATE_PLAYING
        return State(playing.coerceAtLeast(st?.state == android.media.session.PlaybackState.STATE_BUFFERING), title, subtitle, freq)
    }

    /** Asks the firmware's own radio to start; true if a session took the call. */
    fun play(): Boolean {
        val s = current() ?: return false
        return try {
            s.controller.transportControls.play()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pause(): Boolean {
        val s = current() ?: return false
        return try {
            s.controller.transportControls.pause()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Frequencies the session advertises it can tune to, if it advertises any. */
    fun steps(): List<Float> {
        val s = current() ?: return emptyList()
        // A tuning queue is optional; MediaController.queue is @hide, so read it
        // through the public playback info only.
        val items: List<MediaSession.QueueItem> =
            try { s.controller.queue } catch (_: Exception) { null } ?: return emptyList()
        return items.mapNotNull { it.description.title?.toString()?.let { t -> FREQ_IN_TEXT.find(t)?.value?.toFloatOrNull() } }
            .filter { it in 76f..108f }
    }

    /** Tune by absolute frequency if the session exposes a queue of stations. */
    fun tuneTo(freq: Float): Boolean {
        val s = current() ?: return false
        val items: List<MediaSession.QueueItem> =
            try { s.controller.queue } catch (_: Exception) { null } ?: return false
        val match = items.firstOrNull { item ->
            val t = item.description.title?.toString().orEmpty()
            FREQ_IN_TEXT.find(t)?.value?.toFloatOrNull()?.let { kotlin.math.abs(it - freq) < 0.05f } == true
        } ?: return false
        return try {
            s.controller.transportControls.skipToQueueItem(match.queueId)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun startWatching() {
        try {
            manager.addOnActiveSessionsChangedListener(listener, component, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
    }

    fun stopWatching() {
        try { manager.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
    }

    private fun appLabel(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().uppercase()
    } catch (_: Exception) {
        pkg.uppercase()
    }

    companion object {
        /**
         * Packages that are an FM tuner. Matched on substrings because every
         * vendor renames its radio app; none of these are internet stations.
         */
        private val FM_PACKAGES = listOf(
            "com.android.fmradio",          // AOSP / Qualcomm reference
            "com.qualcomm.qti.fmradio",
            "com.caf.fmradio",
            "com.huawei.android.FMRadio",
            "com.huawei.fmradio",
            "com.sec.android.app.fm",       // Samsung
            "com.lge.fmradio",
            "com.miui.fm",                  // Xiaomi
            "com.miui.fmradio",
            "com.oppo.fmradio",
            "com.coloros.fmradio",
            "com.oneplus.fm",
            "com.vivo.fm",
            "com.mediatek.fmradio",
            "com.android.fm",
        )

        private val FREQ_IN_TEXT = Regex("""(\d{2,3}[.,]\d)\s*(?:MHz|MHZ)?""")

        private fun isFm(pkg: String): Boolean {
            val p = pkg.lowercase()
            return FM_PACKAGES.any { p.contains(it.lowercase()) } ||
                p.contains("fmradio") ||
                p.endsWith(".fm") ||
                // "radio" alone matches streaming apps, so require fm nearby.
                (p.contains("radio") && p.contains("fm"))
        }

        /**
         * Null when the phone has no tuner session dumbOS may observe. The
         * caller must then say so, not fall back to a fake dial.
         */
        /** The permission the phone's tuner can only be reached through. */
        fun listenerEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            return flat.contains(ctx.packageName)
        }

        /** Opens the system page where that permission is granted. */
        fun requestListener(ctx: Context) {
            try {
                ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }

        /** The OEM radio app's launcher intent, if one is installed. */
        fun radioAppIntent(ctx: Context): Intent? {
            val pm = ctx.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installed = pm.queryIntentActivities(main, 0)
            for (ri in installed) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!isFm(pkg)) continue
                return pm.getLaunchIntentForPackage(pkg)
                    ?: Intent().setClassName(pkg, ri.activityInfo.name)
            }
            return null
        }

        /** Opens it so it publishes a session dumbOS can then drive. */
        fun openRadioApp(ctx: Context): Boolean {
            val i = radioAppIntent(ctx) ?: return false
            return try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) {
                false
            }
        }

        /** True when this phone has an FM app at all (session or not). */
        fun hasRadioApp(ctx: Context): Boolean = radioAppIntent(ctx) != null

        fun discover(ctx: Context): FmTuner? {
            val manager = try {
                ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            } catch (_: Exception) {
                null
            } ?: return null
            val component = try {
                ComponentName(ctx, com.dumbos.core.DumbNotificationListener::class.java)
            } catch (_: Exception) {
                return null
            }
            // getActiveSessions() only works for a granted NotificationListener.
            // Without it the tuner is invisible, so treat the grant as the gate
            // instead of silently reporting "this phone has no FM".
            if (!listenerEnabled(ctx)) return null
            val granted = try {
                manager.getActiveSessions(component)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: Exception) {
                false
            }
            if (!granted) return null
            val tuner = FmTuner(ctx, manager, component)
            return tuner
        }
    }
}
