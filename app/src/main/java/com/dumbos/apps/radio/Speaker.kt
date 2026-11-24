package com.dumbos.apps.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Where the radio comes out.
 *
 * A 3310 has one speaker, and that is the model here: the radio is meant to be
 * heard by the room, not whispered into an ear. Android's media stream already
 * defaults to the loudspeaker, but a phone that was last in a call, or one with
 * a headset plugged in, can route elsewhere. [Speaker] pins the radio to the
 * built-in speaker when asked, and gives the phone's own speaker back after.
 *
 * Two paths, because the API changed:
 *   - API 31+: AudioManager.setCommunicationDevice(...)
 *   - older:   AudioManager.setSpeakerphoneOn(true)
 * An internet stream needs nothing at all: its AudioTrack is already a media
 * stream, which the system sends to the speaker.
 */
object Speaker {

    /** True when the phone's loudspeaker is the current output. */
    fun isOn(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }

    /**
     * Routes audio to the loudspeaker. Returns false when the platform refused,
     * so the UI can say so instead of showing a switch that does nothing.
     */
    fun on(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: return false
                am.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Hands routing back to the system (headset, earpiece, whatever it was). */
    fun off(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
    }

    /**
     * Builds a track that asks for the loudspeaker as its preferred device.
     * This is the part that belongs to the stream itself rather than to the
     * call routing, and it is what makes internet radio come out of the phone
     * speaker even when a headset is connected.
     */
    fun applyPreferredDevice(ctx: Context, track: AudioTrack): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            val out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val builtin = out.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    (Build.VERSION.SDK_INT >= 28 && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
            } ?: return false
            track.preferredDevice = builtin
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Attributes that mark the audio as music, i.e. speaker-first. */
    fun musicAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
}
