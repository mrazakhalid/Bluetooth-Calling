package com.btcallrelay

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

data class Sim(val id: Int, val label: String)

/** One ongoing call on the SIM phone, as reported by its call control. */
data class LiveCall(val number: String, val state: String, val conference: Boolean, val parts: Int, val sim: Int, val connectTime: Long,
                    val canHold: Boolean, val canMerge: Boolean)

/** type: CallLog.Calls.TYPE (1 incoming, 2 outgoing, 3 missed, 5 rejected, 6 blocked). */
data class CallEntry(val number: String, val type: Int, val date: Long, val durationSec: Long, val name: String)

class PendingConfirm(val code: String, val peer: String) {
    val answer = CompletableFuture<Boolean>()
}

/** In-process state shared between the services (background threads) and the activities (UI). */
object RelayState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var serviceRunning = false
    @Volatile var connected = false
    @Volatile var status = "Stopped"
    @Volatile var peerName: String? = null

    /** "idle", "ringing" or "offhook" (dialing or in a call). */
    @Volatile var callState = "idle"
    @Volatile var callNumber: String? = null
    @Volatile var callSince = 0L
    /** Controller: the ringing call was silenced here (it keeps ringing on the SIM phone). */
    @Volatile var silenced = false

    @Volatile var sims: List<Sim> = emptyList()

    /** SIM phone's call log (newest first) and in-call audio state. */
    @Volatile var callLog: List<CallEntry> = emptyList()
    @Volatile var muted = false
    @Volatile var speaker = false
    @Volatile var bt = false
    /** SIM phone has call control (companion InCallService) bound: keypad tones and proper audio routing. */
    @Volatile var companion = false
    @Volatile var liveCalls: List<LiveCall> = emptyList()
    /** When the other person answered (0 = not yet / unknown). Only known with call control on the SIM phone. */
    @Volatile var answeredAt = 0L

    /** SIM phone battery (%, -1 = unknown), charging, and per-SIM signal (slot, bars 0-4, carrier). */
    @Volatile var battery = -1
    @Volatile var charging = false
    @Volatile var signals: List<Triple<Int, Int, String>> = emptyList()

    /** Auto-reply settings as confirmed by the SIM phone. */
    @Volatile var autoReply: org.json.JSONObject? = null

    /** SIM phone's Wi-Fi hotspot; null = unknown (not connected). */
    @Volatile var hotspotOn: Boolean? = null
    @Volatile var hotspotAllowed = false

    /** Host: accept a new controller pairing. Controller: user asked to pair. */
    @Volatile var pairingMode = false
    @Volatile var pendingConfirm: PendingConfirm? = null

    private val logLines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun changed() { main.post { listeners.forEach { it() } } }

    fun log(msg: String) {
        synchronized(logLines) {
            logLines.addFirst("${fmt.format(Date())}  $msg")
            while (logLines.size > 60) logLines.removeLast()
        }
        changed()
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun setCall(state: String, number: String?) {
        if (state != callState) callSince = System.currentTimeMillis()
        if (state == "idle") answeredAt = 0L
        if (state != "ringing") silenced = false
        callState = state
        if (number != null || state == "idle") callNumber = number
        changed()
    }

    private val pairingOff = Runnable { pairingMode = false; changed() }

    fun enablePairing(ms: Long = 120_000) {
        pairingMode = true
        main.removeCallbacks(pairingOff)
        main.postDelayed(pairingOff, ms)
        changed()
    }

    fun disablePairing() {
        main.removeCallbacks(pairingOff)
        pairingMode = false
        changed()
    }

    /** Blocks a background thread until the user taps Match / Cancel on screen (or 90 s pass). */
    fun askConfirm(code: String, peer: String): Boolean {
        val p = PendingConfirm(code, peer)
        pendingConfirm = p
        changed()
        return try {
            p.answer.get(90, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        } finally {
            pendingConfirm = null
            changed()
        }
    }
}
