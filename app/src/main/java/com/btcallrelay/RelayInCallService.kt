package com.btcallrelay

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Runs on the SIM phone. Android binds this "non-UI" InCallService when the app is a call companion
 * (MANAGE_ONGOING_CALLS, granted through a Companion Device "watch" association with the controller).
 * It's the only way a non-dialer app can send keypad tones into a call and properly control mute,
 * speaker, Bluetooth and hold. The phone's own call screen keeps working as normal.
 */
class RelayInCallService : InCallService() {
    private val main = Handler(Looper.getMainLooper())

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = HostService.instance?.onCompanionChanged() ?: Unit
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        RelayState.log("Call control connected (keypad tones available)")
        HostService.instance?.onCompanionChanged()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        HostService.instance?.onCompanionChanged()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callCallback)
        HostService.instance?.onCompanionChanged()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callCallback)
        HostService.instance?.onCompanionChanged()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        HostService.instance?.onCompanionChanged()
    }

    /** The call keypad tones should go to: the active one, else any ongoing call. */
    private fun current(): Call? = calls.firstOrNull { it.details.state == Call.STATE_ACTIVE }
        ?: calls.firstOrNull { it.details.state != Call.STATE_DISCONNECTED }

    fun dtmf(c: Char): Boolean {
        val call = current() ?: return false
        call.playDtmfTone(c)
        main.postDelayed({ runCatching { call.stopDtmfTone() } }, 180)
        return true
    }

    fun mute(on: Boolean) = setMuted(on)

    fun route(route: Int) = setAudioRoute(route)

    fun muted() = callAudioState?.isMuted ?: false
    fun currentRoute() = callAudioState?.route ?: CallAudioState.ROUTE_EARPIECE
    fun btAvailable() = (callAudioState?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0

    private fun live() = calls.filter { it.details.state != Call.STATE_DISCONNECTED && it.parent == null }

    private fun stateName(s: Int) = when (s) {
        Call.STATE_ACTIVE -> "active"
        Call.STATE_HOLDING -> "holding"
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> "dialing"
        Call.STATE_RINGING -> "ringing"
        else -> "other"
    }

    /** Ongoing calls (conference counted once), for the controller's call screen. */
    fun callsInfo(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (c in live()) {
            val d = c.details
            // Which SIM the call is on, numbered like the controller's SIM list (call-capable accounts order).
            val slot = runCatching {
                getSystemService(android.telecom.TelecomManager::class.java).callCapablePhoneAccounts.indexOf(d.accountHandle)
            }.getOrDefault(-1)
            arr.put(org.json.JSONObject()
                .put("number", d.handle?.schemeSpecificPart ?: "")
                .put("sim", slot)
                // Wall-clock time the call connected (0 until answered); survives Bluetooth reconnects.
                .put("connectTime", d.connectTimeMillis)
                .put("state", stateName(d.state))
                .put("conference", d.hasProperty(Call.Details.PROPERTY_CONFERENCE))
                .put("parts", c.children.size)
                .put("canHold", d.can(Call.Details.CAPABILITY_HOLD) || d.can(Call.Details.CAPABILITY_SUPPORT_HOLD))
                .put("canMerge", c.conferenceableCalls.isNotEmpty() || d.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)))
        }
        return arr
    }

    /** Hold toggles the active call; if nothing is active, resumes the held one. */
    fun hold(on: Boolean): Boolean {
        val l = live()
        val active = l.firstOrNull { it.details.state == Call.STATE_ACTIVE }
        val held = l.firstOrNull { it.details.state == Call.STATE_HOLDING }
        if (on) active?.hold() ?: return false else held?.unhold() ?: return false
        return true
    }

    /** Joins the active and held calls into a conference. */
    fun merge(): Boolean {
        val l = live()
        val active = l.firstOrNull { it.details.state == Call.STATE_ACTIVE } ?: l.firstOrNull() ?: return false
        val other = active.conferenceableCalls.firstOrNull()
        when {
            other != null -> active.conference(other)
            active.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> active.mergeConference()
            else -> return false
        }
        return true
    }

    /** Puts the active call on hold and resumes the held one. */
    fun swap(): Boolean {
        val l = live()
        val active = l.firstOrNull { it.details.state == Call.STATE_ACTIVE }
        val held = l.firstOrNull { it.details.state == Call.STATE_HOLDING } ?: return false
        if (active != null && active.details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE)) active.swapConference()
        else held.unhold() // Telecom holds the active call automatically
        return true
    }

    companion object {
        @Volatile var instance: RelayInCallService? = null
    }
}
