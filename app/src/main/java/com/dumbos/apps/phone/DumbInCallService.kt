package com.dumbos.apps.phone

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile

/**
 * dumbOS as the phone app, not a shell around someone else's.
 *
 * A normal app can start a call (TelecomManager.placeCall) but cannot draw the
 * in-call UI: the system does that itself. The way to own it is to be the
 * default dialer, and a default dialer must provide an [InCallService]. This is
 * that service.
 *
 * It does three things and nothing else:
 *   - keeps the call list the UI needs
 *   - answers and hangs up
 *   - reports the current call so PhoneActivity can show it
 *
 * The call audio itself is handled by the telephony stack, which is correct:
 * a third-party app must never be in the path of a real phone call.
 */
class DumbInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifyChanged()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            notifyChanged()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            notifyChanged()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        call.registerCallback(callback)
        callList = callList + call
        notifyChanged()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        runCatching { call.unregisterCallback(callback) }
        callList = callList.filter { it != call }
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it(callList) } }
    }

    /** Human-readable state for the dumbOS in-call screen. */
    fun describe(call: Call): String = when (call.state) {
        Call.STATE_RINGING -> "ENTRANTE"
        Call.STATE_DIALING -> "MARCANDO"
        Call.STATE_ACTIVE -> "EN LLAMADA"
        Call.STATE_HOLDING -> "EN ESPERA"
        Call.STATE_DISCONNECTED -> "COLGADA"
        else -> "LLAMADA"
    }

    companion object {
        /** Non-null while the system is bound to us, i.e. we are the dialer. */
        @Volatile
        var instance: DumbInCallService? = null
            private set

        @Volatile
        private var callList: List<Call> = emptyList()
        val calls: List<Call> get() = callList

        private val listeners = java.util.Collections.synchronizedList(
            ArrayList<(List<Call>) -> Unit>()
        )

        /** True when dumbOS owns the call UI on this phone. */
        fun isDefaultDialer(): Boolean = instance != null

        fun addListener(l: (List<Call>) -> Unit) { listeners.add(l); l(callList) }
        fun removeListener(l: (List<Call>) -> Unit) { listeners.remove(l) }

        /** Current (first non-disconnected) call, if any. */
        fun activeCall(): Call? =
            callList.firstOrNull { it.state != Call.STATE_DISCONNECTED }

        fun answer() {
            activeCall()?.let { c ->
                if (c.state == Call.STATE_RINGING) c.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }

        fun hangup() {
            activeCall()?.let { c ->
                when (c.state) {
                    Call.STATE_RINGING -> c.reject(false, null)
                    else -> c.disconnect()
                }
            }
        }
    }
}
