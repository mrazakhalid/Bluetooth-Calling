package com.btcallrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.CallLog
import android.os.Bundle
import android.os.IBinder
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs on the phone WITH the SIM. Listens for the controller and carries out its call commands. */
@SuppressLint("MissingPermission") // permissions are checked in HostActivity before the service starts
class HostService : Service() {

    private lateinit var prefs: Prefs
    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var channel: SecureChannel? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var simHandles: List<PhoneAccountHandle> = emptyList()
    private var lastDialed: String? = null
    @Volatile private var ringingNumber: String? = null
    @Volatile private var answered = false
    @Volatile private var declined = false
    @Volatile private var weMuted = false

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
                TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
                else -> "idle"
            }
            @Suppress("DEPRECATION")
            val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val number = when {
                incoming != null -> incoming
                state == "offhook" && RelayState.callState == "idle" -> lastDialed
                else -> null
            }
            if (state == "idle") lastDialed = null
            if (state == RelayState.callState && number == null) return
            val prev = RelayState.callState
            when (state) {
                "ringing" -> if (prev == "idle") { ringingNumber = number; answered = false; declined = false }
                    else if (number != null) ringingNumber = number
                "offhook" -> {
                    answered = true
                    if (prev == "idle") clearStuckMicMute("new outgoing call")
                    else if (prev == "ringing") clearStuckMicMute("call answered")
                }
                "idle" -> {
                    val missed = prev == "ringing" && !answered && !declined
                    if (missed) {
                        RelayState.log("Missed call from ${ringingNumber ?: "unknown"}")
                        send(JSONObject().put("t", "missed").put("number", ringingNumber ?: JSONObject.NULL)
                            .put("time", System.currentTimeMillis()))
                        autoReplyMissed(ringingNumber)
                    }
                    ringingNumber = null
                    resetAudio()
                    // The system writes the call log entry a moment after the call ends.
                    watchdog.schedule({ sendCallLog() }, 2, TimeUnit.SECONDS)
                }
            }
            RelayState.setCall(state, number)
            sendCallState()
            updateNotification()
        }
    }

    /** Fires once per SMS part when the network accepts (or rejects) it. */
    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_SMS_ID) ?: return
            val ok = resultCode == Activity.RESULT_OK
            val prev = smsParts[id] ?: return
            if (!ok) {
                smsParts.remove(id)
                RelayState.log("SMS to ${prev.second} failed (code $resultCode)")
                send(JSONObject().put("t", "sms_status").put("id", id).put("status", "failed"))
            } else if (prev.first <= 1) {
                smsParts.remove(id)
                RelayState.log("SMS sent to ${prev.second}")
                send(JSONObject().put("t", "sms_status").put("id", id).put("status", "sent"))
            } else {
                smsParts[id] = (prev.first - 1) to prev.second
            }
        }
    }

    /** SMS id -> (parts still waiting for a result, recipient). */
    private val smsParts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()

    private val apReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val on = Hotspot.isOnState(intent.getIntExtra(Hotspot.EXTRA_AP_STATE, 0))
            if (on == RelayState.hotspotOn) return
            RelayState.hotspotOn = on
            RelayState.changed()
            sendHotspotState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST_HOTSPOT) {
            if (running) setHotspot(RelayState.hotspotOn != true, "this phone (test)")
            return START_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (!running) {
            running = true
            RelayState.serviceRunning = true
            RelayState.status = "Waiting for controller"
            RelayState.changed()
            ContextCompat.registerReceiver(this, phoneReceiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(this, smsSentReceiver, IntentFilter(ACTION_SMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, apReceiver, IntentFilter(Hotspot.ACTION_AP_STATE), ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            RelayState.hotspotOn = Hotspot.isOn(this)
            instance = this
            loadSims()
            thread(name = "bt-accept") { acceptLoop() }
            watchdog.scheduleWithFixedDelay({
                val ch = channel
                if (ch != null && System.currentTimeMillis() - ch.lastRx > Protocol.DEAD_MS) {
                    RelayState.log("Controller silent, dropping link")
                    ch.close()
                }
            }, 10, 10, TimeUnit.SECONDS)
            watchdog.scheduleWithFixedDelay({ sendPhoneStatus(force = true) }, 60, 60, TimeUnit.SECONDS)
            RelayState.log("Relay started")
            if (RelayState.callState == "idle") clearStuckMicMute("relay start")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        runCatching { unregisterReceiver(phoneReceiver) }
        runCatching { unregisterReceiver(smsSentReceiver) }
        runCatching { unregisterReceiver(apReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { server?.close() }
        channel?.close()
        watchdog.shutdownNow()
        sender.shutdownNow()
        RelayState.serviceRunning = false
        RelayState.connected = false
        RelayState.status = "Stopped"
        RelayState.log("Relay stopped")
        super.onDestroy()
    }

    private fun acceptLoop() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled) {
                    RelayState.status = "Bluetooth is off"
                    RelayState.changed()
                    Thread.sleep(5000)
                    continue
                }
                val ss = server ?: adapter.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
                    .also { server = it }
                if (channel == null) {
                    RelayState.status = "Waiting for controller"
                    RelayState.changed()
                }
                val sock = ss.accept()
                thread(name = "bt-conn") { handleConnection(sock) }
            } catch (e: Exception) {
                runCatching { server?.close() }
                server = null
                if (running) Thread.sleep(3000)
            }
        }
    }

    private fun handleConnection(sock: BluetoothSocket) {
        val addr = sock.remoteDevice.address
        val name = runCatching { sock.remoteDevice.name }.getOrNull() ?: addr
        val known = prefs.peerAddress
        if (!RelayState.pairingMode && known != null && addr != known) {
            RelayState.log("Blocked connection from $name (not the paired controller)")
            runCatching { sock.close() }
            return
        }
        val ch = SecureChannel(sock)
        // Close half-open or stalled handshakes so they can't hold the relay hostage.
        val handshakeTimeout = watchdog.schedule({ ch.close() }, 120, TimeUnit.SECONDS)
        try {
            Handshake.host(ch, prefs,
                pairingAllowed = { RelayState.pairingMode },
                confirmPairing = { code ->
                    RelayState.log("Pairing request from $name")
                    RelayState.askConfirm(code, name).also { ok ->
                        RelayState.log(if (ok) "Paired with $name" else "Pairing with $name declined")
                        if (ok) RelayState.disablePairing()
                    }
                })
            handshakeTimeout.cancel(false)
            channel?.close()
            channel = ch
            RelayState.connected = true
            RelayState.peerName = prefs.peerName ?: name
            RelayState.status = "Controller connected"
            RelayState.log("Controller connected: $name")
            updateNotification()
            sendSims()
            sendCallState()
            flushSms()
            sendHotspotState()
            sendCallLog()
            sendAudioState()
            sendPhoneStatus(force = true)
            sendAutoReplyAck()
            while (running) handleCommand(ch.receive())
        } catch (e: HandshakeError) {
            RelayState.log("Refused $name: ${e.reason}")
        } catch (e: SecurityException) {
            RelayState.log("Dropped $name: ${e.message}")
        } catch (e: Exception) {
            if (channel === ch) RelayState.log("Controller disconnected")
        } finally {
            handshakeTimeout.cancel(false)
            ch.close()
            if (channel === ch) {
                channel = null
                RelayState.connected = false
                RelayState.status = if (running) "Waiting for controller" else "Stopped"
                RelayState.changed()
                updateNotification()
            }
        }
    }

    private fun handleCommand(m: JSONObject) {
        val telecom = getSystemService(TelecomManager::class.java)
        when (m.optString("t")) {
            "ping" -> send(JSONObject().put("t", "pong"))
            "status" -> { sendSims(); sendCallState() }
            "sms_send" -> sendSms(m)
            "sms_ack" -> SmsOutbox.remove(this, m.optString("id"))
            "hotspot" -> setHotspot(m.optBoolean("on"), RelayState.peerName ?: "controller")
            "dial" -> {
                val number = m.optString("number")
                if (!NUMBER_RE.matches(number)) return reply(false, "Invalid number")
                if (RelayState.callState != "idle" && !m.optBoolean("add")) return reply(false, "Redmi is already in a call")
                if (!granted(Manifest.permission.CALL_PHONE)) return reply(false, "Redmi: call permission missing")
                val extras = Bundle()
                simHandles.getOrNull(m.optInt("sim", -1))?.let {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
                }
                lastDialed = number
                try {
                    telecom.placeCall(Uri.fromParts("tel", number, null), extras)
                    RelayState.log("Dialing $number (requested by ${RelayState.peerName})")
                    reply(true, "Calling $number…")
                } catch (e: Exception) {
                    lastDialed = null
                    reply(false, "Redmi could not dial: ${e.message}")
                }
            }
            "calls_req" -> sendCallLog()
            "sms_history_req" -> sender.execute { sendSmsHistory(m.optInt("limit", 1500).coerceIn(1, 3000)) }
            "status_req" -> sendPhoneStatus(force = true)
            "autoreply" -> {
                AutoReply.save(this, m)
                RelayState.log("Auto-reply ${if (m.optBoolean("on")) "on" else "off"} (set from ${RelayState.peerName})")
                sendAutoReplyAck()
            }
            "mute" -> setMute(m.optBoolean("on"))
            "speaker" -> setSpeaker(m.optBoolean("on"))
            "bt" -> setBluetooth(m.optBoolean("on"))
            "dtmf" -> {
                val c = m.optString("digit").firstOrNull()
                if (c == null || c !in "0123456789*#") return
                val svc = RelayInCallService.instance
                    ?: return reply(false, "Keypad tones need \"Enable call control\" on the SIM phone")
                if (!svc.dtmf(c)) reply(false, "No active call")
            }
            "hold" -> {
                val svc = RelayInCallService.instance ?: return reply(false, "Hold needs \"Enable call control\" on the SIM phone")
                val on = m.optBoolean("on")
                val ok = svc.hold(on)
                RelayState.log(if (on) "Hold requested" else "Resume requested")
                reply(ok, if (!ok) "Nothing to ${if (on) "hold" else "resume"}" else if (on) "On hold" else "Resumed")
            }
            "merge" -> {
                val svc = RelayInCallService.instance ?: return reply(false, "Merge needs \"Enable call control\" on the SIM phone")
                val ok = svc.merge()
                RelayState.log("Merge requested")
                reply(ok, if (ok) "Merging calls…" else "These calls can't be merged")
            }
            "swap" -> {
                val svc = RelayInCallService.instance ?: return reply(false, "Swap needs \"Enable call control\" on the SIM phone")
                val ok = svc.swap()
                reply(ok, if (ok) "Swapped" else "No call on hold")
            }
            "end" -> {
                if (RelayState.callState == "ringing") declined = true
                @Suppress("DEPRECATION")
                val ok = runCatching { telecom.endCall() }.getOrDefault(false)
                RelayState.log("End call requested")
                reply(ok, if (ok) "Call ended" else "No call to end")
            }
            "answer" -> {
                @Suppress("DEPRECATION")
                val ok = runCatching { telecom.acceptRingingCall(); true }.getOrDefault(false)
                RelayState.log("Answer requested")
                reply(ok, if (ok) "Answered" else "Could not answer")
            }
        }
    }

    /** Last 60 calls from the Redmi's call log. */
    private fun sendCallLog() {
        if (channel == null || !granted(Manifest.permission.READ_CALL_LOG)) return
        val arr = JSONArray()
        runCatching {
            contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME),
                null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && arr.length() < 60) {
                    arr.put(JSONObject().put("number", c.getString(0) ?: "").put("type", c.getInt(1))
                        .put("date", c.getLong(2)).put("dur", c.getLong(3)).put("name", c.getString(4) ?: ""))
                }
            }
        }
        send(JSONObject().put("t", "calls").put("list", arr))
    }

    private val audio get() = getSystemService(AudioManager::class.java)

    private fun speakerOn() = runCatching { audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.getOrDefault(false)

    private var lastStatusKey = ""

    /** Battery changes fire often; only forward when the % or charging state actually changes. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = sendPhoneStatus(force = false)
    }

    private fun sendPhoneStatus(force: Boolean) {
        if (channel == null) return
        val o = PhoneStatus.build(this)
        val key = "${o.optInt("battery")}|${o.optBoolean("charging")}"
        if (!force && key == lastStatusKey) return
        lastStatusKey = key
        send(o)
    }

    /** Tells the controller the auto-reply settings the SIM phone is actually using. */
    private fun sendAutoReplyAck() = send(AutoReply.config(this).put("t", "autoreply_state"))

    /** Sends old texts in chunks small enough for one frame each (~24 KB). */
    private fun sendSmsHistory(limit: Int) {
        val all = SmsHistory.read(this, limit)
        if (all.isEmpty() && !granted(Manifest.permission.READ_SMS)) {
            return reply(false, "SIM phone: allow \"SMS\" permission in Dialer to import old texts")
        }
        var chunk = JSONArray()
        var size = 0
        for (m in all) {
            val s = m.toString().length
            if (size + s > 24_000 && chunk.length() > 0) {
                send(JSONObject().put("t", "sms_history").put("list", chunk).put("done", false))
                chunk = JSONArray(); size = 0
            }
            chunk.put(m); size += s
        }
        send(JSONObject().put("t", "sms_history").put("list", chunk).put("done", true).put("total", all.size))
        RelayState.log("Sent ${all.size} old texts to the controller")
    }

    /** Auto-reply after a missed call, and show the sent text on the controller. */
    private fun autoReplyMissed(number: String?) {
        sender.execute {
            val text = AutoReply.maybeReply(this, number, "call") ?: return@execute
            send(JSONObject().put("t", "sms_auto").put("number", number).put("body", text).put("time", System.currentTimeMillis()))
        }
    }

    /** Called by SmsReceiver after an auto-reply to a text. */
    fun notifyAutoReply(number: String, text: String) =
        send(JSONObject().put("t", "sms_auto").put("number", number).put("body", text).put("time", System.currentTimeMillis()))

    /** Called by RelayInCallService when calls, audio route or its own availability change. */
    fun onCompanionChanged() = sendAudioState()

    private fun sendAudioState() {
        val svc = RelayInCallService.instance
        val o = JSONObject().put("t", "audio_state").put("companion", svc != null)
        if (svc != null) {
            o.put("calls", svc.callsInfo())
            o.put("muted", svc.muted()).put("speaker", svc.currentRoute() == CallAudioState.ROUTE_SPEAKER)
                .put("bt", svc.currentRoute() == CallAudioState.ROUTE_BLUETOOTH).put("btAvailable", svc.btAvailable())
        } else {
            o.put("muted", runCatching { audio.isMicrophoneMute }.getOrDefault(false)).put("speaker", speakerOn()).put("bt", btOn())
        }
        send(o)
    }

    /** With call control enabled, route call audio the proper way (like the phone's own call screen does). */
    private fun companionRoute(route: Int, label: String): Boolean {
        val svc = RelayInCallService.instance ?: return false
        svc.route(route)
        RelayState.log(label)
        reply(true, label)
        return true
    }

    private fun isBt(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun btOn() = runCatching { audio.communicationDevice?.type?.let { isBt(it) } ?: false }.getOrDefault(false)

    /** Sends call audio to (or away from) a Bluetooth headset connected to the Redmi. */
    private fun setBluetooth(on: Boolean) {
        if (RelayState.callState != "offhook") return reply(false, "No active call")
        RelayInCallService.instance?.let { svc ->
            if (on && !svc.btAvailable()) return reply(false, "No Bluetooth headset connected to the SIM phone")
            companionRoute(if (on) CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_EARPIECE,
                if (on) "Bluetooth on" else "Bluetooth off")
            return
        }
        val dev = runCatching { audio.availableCommunicationDevices.firstOrNull { isBt(it.type) } }.getOrNull()
        if (on && dev == null) return reply(false, "No Bluetooth headset connected to the SIM phone")
        runCatching { if (on) audio.setCommunicationDevice(dev!!) else audio.clearCommunicationDevice() }
        val ok = btOn() == on
        RelayState.log(if (ok) "Bluetooth audio ${if (on) "on" else "off"}" else "Bluetooth audio change refused by the phone")
        reply(ok, if (ok) "Bluetooth ${if (on) "on" else "off"}" else "The SIM phone didn't allow the Bluetooth change")
        sendAudioState()
    }

    private fun setMute(on: Boolean) {
        if (RelayState.callState != "offhook") return reply(false, "No active call")
        RelayInCallService.instance?.let { svc ->
            svc.mute(on)
            RelayState.log(if (on) "Muted" else "Unmuted")
            reply(true, if (on) "Muted" else "Unmuted")
            return
        }
        // No phone-wide mic mute any more: if the relay got killed mid-call it stayed muted for every later call.
        reply(false, "Mute needs \"Enable call control\" on the SIM phone")
    }

    /**
     * Clears a phone-wide microphone mute left behind by older versions (or a crash). Only done when a new
     * call starts or no call is active, so a mute set on the phone's own call screen isn't undone mid-call.
     */
    private fun clearStuckMicMute(reason: String) {
        if (runCatching { audio.isMicrophoneMute }.getOrDefault(false)) {
            runCatching { audio.isMicrophoneMute = false }
            RelayState.log("Cleared a stuck microphone mute ($reason)")
        }
        weMuted = false
    }

    private fun setSpeaker(on: Boolean) {
        if (RelayState.callState != "offhook") return reply(false, "No active call")
        if (companionRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE,
                if (on) "Speaker on" else "Speaker off")) return
        runCatching {
            if (on) audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
            else audio.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        runCatching { audio.isSpeakerphoneOn = on }
        val ok = speakerOn() == on
        RelayState.log(if (ok) "Speaker ${if (on) "on" else "off"}" else "Speaker change refused by the phone")
        reply(ok, if (ok) "Speaker ${if (on) "on" else "off"}" else "The SIM phone didn't allow the speaker change")
        sendAudioState()
    }

    /** Never leave the mic muted or the speaker forced once the call is over. */
    private fun resetAudio() {
        if (weMuted) runCatching { audio.isMicrophoneMute = false }
        weMuted = false
        runCatching { audio.clearCommunicationDevice() }
        sendAudioState()
    }

    private fun setHotspot(on: Boolean, by: String) {
        RelayState.log("Hotspot ${if (on) "on" else "off"} requested by $by")
        if (on) {
            Hotspot.start(this, sender) { ok, msg ->
                RelayState.log(msg)
                reply(ok, msg)
                if (ok) { RelayState.hotspotOn = true; RelayState.changed() }
                sendHotspotState()
            }
        } else {
            val (ok, msg) = Hotspot.stop(this)
            RelayState.log(msg)
            reply(ok, msg)
        }
    }

    private fun sendHotspotState() {
        RelayState.hotspotAllowed = Hotspot.allowed(this)
        send(JSONObject().put("t", "hotspot_state").put("on", RelayState.hotspotOn == true)
            .put("allowed", RelayState.hotspotAllowed))
    }

    private fun sendSms(m: JSONObject) {
        val id = m.optString("id")
        val to = m.optString("to")
        val body = m.optString("body")
        fun fail(msg: String) {
            send(JSONObject().put("t", "sms_status").put("id", id).put("status", "failed"))
            reply(false, msg)
        }
        if (id.isEmpty() || id.length > 64) return
        if (!NUMBER_RE.matches(to)) return fail("Invalid number")
        if (body.isEmpty() || body.length > 2000) return fail("Message is empty or too long")
        if (!granted(Manifest.permission.SEND_SMS)) return fail("Redmi: SMS permission missing")
        try {
            val handle = simHandles.getOrNull(m.optInt("sim", -1))
            val tm = getSystemService(TelephonyManager::class.java)
            val subId = handle?.let { runCatching { tm.getSubscriptionId(it) }.getOrNull() }
                ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            val sms = getSystemService(SmsManager::class.java).let {
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(subId) else it
            }
            val parts = sms.divideMessage(body)
            smsParts[id] = parts.size to to
            val sent = ArrayList((0 until parts.size).map { i ->
                PendingIntent.getBroadcast(this, (id.hashCode() * 31) + i,
                    Intent(ACTION_SMS_SENT).setPackage(packageName).putExtra(EXTRA_SMS_ID, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            })
            sms.sendMultipartTextMessage(to, null, parts, sent, null)
            RelayState.log("Sending SMS to $to (requested by ${RelayState.peerName})")
        } catch (e: Exception) {
            smsParts.remove(id)
            fail("Redmi could not send SMS: ${e.message}")
        }
    }

    /** Deliver queued incoming SMS; each stays queued until the controller confirms it. */
    fun flushSms() {
        if (channel == null) return
        SmsOutbox.pending(this).forEach { msg ->
            send(JSONObject().put("t", "sms_in").put("id", msg.id).put("from", msg.number)
                .put("body", msg.body).put("time", msg.time))
        }
    }

    private fun loadSims() {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return
        val telecom = getSystemService(TelecomManager::class.java)
        simHandles = runCatching { telecom.callCapablePhoneAccounts }.getOrDefault(emptyList())
        RelayState.sims = simHandles.mapIndexed { i, h ->
            val label = runCatching { telecom.getPhoneAccount(h)?.label?.toString() }.getOrNull()
            Sim(i, "SIM ${i + 1}" + (label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
        }
    }

    private fun sendSims() {
        val arr = JSONArray()
        RelayState.sims.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
        send(JSONObject().put("t", "sims").put("list", arr))
    }

    private fun sendCallState() {
        // "since" = when this phone entered the current state, so the controller's timer survives reconnects.
        send(JSONObject().put("t", "call_state").put("state", RelayState.callState)
            .put("number", RelayState.callNumber ?: JSONObject.NULL).put("since", RelayState.callSince))
    }

    private fun reply(ok: Boolean, msg: String) = send(JSONObject().put("t", "result").put("ok", ok).put("msg", msg))

    private fun send(o: JSONObject) {
        val ch = channel ?: return
        runCatching { sender.execute { runCatching { ch.send(o) }.onFailure { ch.close() } } }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Relay status", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, HostActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            RelayState.callState == "ringing" -> "Incoming call ${RelayState.callNumber ?: ""}"
            RelayState.callState == "offhook" -> "In call ${RelayState.callNumber ?: ""}"
            RelayState.connected -> "Connected to ${RelayState.peerName}"
            else -> "Waiting for controller"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SIM relay active")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL = "host_status"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "stop"
        const val ACTION_TEST_HOTSPOT = "test_hotspot"
        private const val ACTION_SMS_SENT = "com.btcallrelay.SMS_SENT"
        private const val EXTRA_SMS_ID = "sms_id"

        /** Lets SmsReceiver hand new texts straight to the running relay. */
        @Volatile var instance: HostService? = null
        private val NUMBER_RE = Regex("^\\+?[0-9*#]{1,32}$")

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, HostService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, HostService::class.java).setAction(ACTION_STOP))
    }
}
