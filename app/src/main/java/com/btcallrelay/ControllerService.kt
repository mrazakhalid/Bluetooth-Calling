package com.btcallrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs on the phone WITHOUT a SIM. Keeps a link to the host and sends it call commands. */
@SuppressLint("MissingPermission") // permissions are checked in ControllerActivity before the service starts
class ControllerService : Service() {

    private lateinit var prefs: Prefs
    @Volatile private var running = false
    @Volatile private var channel: SecureChannel? = null
    private val wake = Object()
    private val sender = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_DIAL -> intent.getStringExtra(EXTRA_NUMBER)?.let { n ->
                send(JSONObject().put("t", "dial").put("number", n).put("sim", intent.getIntExtra(EXTRA_SIM, -1))
                    .put("add", intent.getBooleanExtra(EXTRA_ADD, false)))
            }
            ACTION_END -> send(JSONObject().put("t", "end"))
            ACTION_ANSWER -> send(JSONObject().put("t", "answer"))
            ACTION_SILENCE -> if (RelayState.callState == "ringing" && !RelayState.silenced) {
                // Cancelling stops the ringtone; the call is shown again on the quiet channel.
                RelayState.silenced = true
                cancelIncoming()
                showIncoming(RelayState.callNumber)
                RelayState.changed()
            }
            ACTION_RECONNECT -> synchronized(wake) { wake.notifyAll() }
            ACTION_REFRESH_CALLS -> if (channel != null) send(JSONObject().put("t", "calls_req"))
            ACTION_HOTSPOT -> send(JSONObject().put("t", "hotspot").put("on", intent.getBooleanExtra(EXTRA_ON, true)))
            ACTION_MUTE -> send(JSONObject().put("t", "mute").put("on", intent.getBooleanExtra(EXTRA_ON, true)))
            ACTION_SPEAKER -> send(JSONObject().put("t", "speaker").put("on", intent.getBooleanExtra(EXTRA_ON, true)))
            ACTION_BT -> send(JSONObject().put("t", "bt").put("on", intent.getBooleanExtra(EXTRA_ON, true)))
            ACTION_DTMF -> intent.getStringExtra(EXTRA_NUMBER)?.let { send(JSONObject().put("t", "dtmf").put("digit", it)) }
            ACTION_HOLD -> send(JSONObject().put("t", "hold").put("on", intent.getBooleanExtra(EXTRA_ON, true)))
            ACTION_MERGE -> send(JSONObject().put("t", "merge"))
            ACTION_SWAP -> send(JSONObject().put("t", "swap"))
            ACTION_IMPORT_SMS -> { importing = true; send(JSONObject().put("t", "sms_history_req")) }
            ACTION_AUTOREPLY -> intent.getStringExtra(EXTRA_JSON)?.let { send(JSONObject(it).put("t", "autoreply")) }
            ACTION_SMS -> {
                val id = intent.getStringExtra(EXTRA_ID)
                val to = intent.getStringExtra(EXTRA_NUMBER)
                val body = intent.getStringExtra(EXTRA_BODY)
                if (id != null && to != null && body != null) {
                    if (channel == null) MessageStore.setStatus(this, id, "failed")
                    send(JSONObject().put("t", "sms_send").put("id", id).put("to", to).put("body", body)
                        .put("sim", intent.getIntExtra(EXTRA_SIM, -1)))
                }
            }
        }
        startForeground(NOTIF_ID, buildStatusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (!running) {
            running = true
            RelayState.serviceRunning = true
            thread(name = "bt-connect") { connectLoop() }
            timer.scheduleWithFixedDelay({
                val ch = channel ?: return@scheduleWithFixedDelay
                if (System.currentTimeMillis() - ch.lastRx > Protocol.DEAD_MS) ch.close()
                else send(JSONObject().put("t", "ping"))
            }, Protocol.PING_MS, Protocol.PING_MS, TimeUnit.MILLISECONDS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        channel?.close()
        synchronized(wake) { wake.notifyAll() }
        timer.shutdownNow()
        sender.shutdownNow()
        cancelIncoming()
        RelayState.serviceRunning = false
        RelayState.connected = false
        RelayState.status = "Stopped"
        RelayState.setCall("idle", null)
        super.onDestroy()
    }

    private fun setStatus(s: String) {
        RelayState.status = s
        RelayState.changed()
        updateStatusNotification()
    }

    private fun connectLoop() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        var backoff = 3000L
        while (running) {
            val addr = prefs.peerAddress
            val pairing = RelayState.pairingMode
            when {
                adapter == null || !adapter.isEnabled -> setStatus("Bluetooth is off")
                addr == null -> setStatus("Choose your SIM phone in Settings")
                !prefs.isPaired && !pairing -> setStatus("Not paired. Tap \"Pair with SIM phone\"")
                else -> {
                    val dev = adapter.getRemoteDevice(addr)
                    val name = prefs.peerName ?: addr
                    setStatus(if (pairing) "Pairing with $name…" else "Connecting to $name…")
                    var ch: SecureChannel? = null
                    try {
                        val sock = dev.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
                        sock.connect()
                        ch = SecureChannel(sock)
                        Handshake.controller(ch, prefs, Build.MODEL, allowPairing = pairing,
                            confirmPairing = { code -> RelayState.askConfirm(code, name) })
                        if (pairing) RelayState.disablePairing()
                        channel = ch
                        backoff = 3000L
                        RelayState.connected = true
                        RelayState.peerName = name
                        setStatus("Connected to $name")
                        if (!prefs.smsHistoryImported) send(JSONObject().put("t", "sms_history_req"))
                        while (running) handleEvent(ch.receive())
                    } catch (e: HandshakeError) {
                        if (pairing) RelayState.disablePairing()
                        when (e.reason) {
                            "not_paired", "auth_failed" -> {
                                prefs.forgetPairing()
                                toast("The SIM phone doesn't recognise this phone any more. Pair again.")
                            }
                            "pairing_off" -> toast("On the SIM phone, tap \"Allow new controller\" first")
                            "pair_rejected", "cancelled" -> toast("Pairing cancelled")
                            "host_auth_failed" -> toast("Warning: the SIM phone failed the security check")
                            else -> toast("Connection refused: ${e.reason}")
                        }
                    } catch (e: SecurityException) {
                        toast("Dropped a tampered message")
                    } catch (e: Exception) {
                        if (pairing) {
                            RelayState.disablePairing()
                            toast("Could not reach the SIM phone. Is the relay running on it?")
                        }
                    } finally {
                        ch?.close()
                        if (channel === ch) channel = null
                        if (RelayState.connected) {
                            RelayState.connected = false
                            RelayState.setCall("idle", null)
                            RelayState.sims = emptyList()
                            RelayState.hotspotOn = null
                            RelayState.muted = false
                            RelayState.speaker = false
                            RelayState.liveCalls = emptyList()
                            cancelIncoming()
                        }
                    }
                    if (running) setStatus("SIM phone not reachable. Retrying…")
                }
            }
            synchronized(wake) { if (running) wake.wait(backoff) }
            backoff = (backoff * 2).coerceAtMost(20_000L)
        }
    }

    private fun handleEvent(m: JSONObject) {
        when (m.optString("t")) {
            "call_state" -> {
                val state = m.optString("state", "idle")
                val number = if (m.isNull("number")) null else m.optString("number")
                RelayState.setCall(state, number)
                // Use the SIM phone's own start time so a Bluetooth reconnect mid-call doesn't restart the timer.
                val since = m.optLong("since", 0L)
                if (since > 0L && since <= System.currentTimeMillis() + 5_000) { RelayState.callSince = since; RelayState.changed() }
                if (state == "ringing") showIncoming(number) else cancelIncoming()
                updateStatusNotification()
            }
            "sims" -> {
                val arr = m.optJSONArray("list")
                RelayState.sims = (0 until (arr?.length() ?: 0)).map {
                    val o = arr!!.getJSONObject(it)
                    o.getInt("id").let { id -> Sim(id, "${prefs.simName(id)} (SIM ${id + 1})") }
                }
                RelayState.changed()
            }
            "sms_in" -> {
                val id = m.getString("id")
                val from = m.optString("from", "Unknown")
                val msg = Msg(id, from, m.optString("body"), m.optLong("time", System.currentTimeMillis()),
                    incoming = true, read = false)
                val isNew = MessageStore.all(this).none { it.id == id }
                MessageStore.add(this, msg)
                send(JSONObject().put("t", "sms_ack").put("id", id))
                if (isNew && MessagesActivity.openThread != threadKey(from)) showSms(msg)
            }
            "sms_status" -> MessageStore.setStatus(this, m.getString("id"), m.optString("status"))
            "calls" -> {
                val arr = m.optJSONArray("list")
                RelayState.callLog = (0 until (arr?.length() ?: 0)).map {
                    val o = arr!!.getJSONObject(it)
                    CallEntry(o.optString("number"), o.optInt("type"), o.optLong("date"), o.optLong("dur"), o.optString("name"))
                }
                RelayState.changed()
            }
            "missed" -> showMissed(if (m.isNull("number")) null else m.optString("number"))
            "phone_status" -> onPhoneStatus(m)
            "sms_history" -> onSmsHistory(m)
            "sms_auto" -> MessageStore.add(this, Msg(UUID.randomUUID().toString(), m.optString("number"), m.optString("body"),
                m.optLong("time", System.currentTimeMillis()), incoming = false, status = "sent"))
            "autoreply_state" -> {
                // The controller is the source of truth: push settings changed while out of range.
                val local = AutoReplyUi.pending(this)
                if (local != null && listOf("on", "text", "calls", "sms").any { k -> local.opt(k)?.toString() != m.opt(k)?.toString() })
                    send(JSONObject(local.toString()).put("t", "autoreply"))
                else { RelayState.autoReply = m; RelayState.changed() }
            }
            "audio_state" -> {
                RelayState.muted = m.optBoolean("muted")
                RelayState.speaker = m.optBoolean("speaker")
                RelayState.bt = m.optBoolean("bt")
                RelayState.companion = m.optBoolean("companion")
                val ca = m.optJSONArray("calls")
                RelayState.liveCalls = (0 until (ca?.length() ?: 0)).map {
                    val o = ca!!.getJSONObject(it)
                    LiveCall(o.optString("number"), o.optString("state"), o.optBoolean("conference"), o.optInt("parts"), o.optInt("sim", -1), o.optLong("connectTime", 0L),
                        o.optBoolean("canHold"), o.optBoolean("canMerge"))
                }
                // Answer time from the SIM phone (connectTime) so reconnects and late joins show the true duration.
                val connected = RelayState.liveCalls.filter { it.connectTime > 0L }.minOfOrNull { it.connectTime }
                if (connected != null && connected <= System.currentTimeMillis() + 5_000) RelayState.answeredAt = connected
                else if (RelayState.answeredAt == 0L && RelayState.liveCalls.any { it.state == "active" }) RelayState.answeredAt = System.currentTimeMillis()
                RelayState.changed()
            }
            "hotspot_state" -> {
                RelayState.hotspotOn = m.optBoolean("on")
                RelayState.hotspotAllowed = m.optBoolean("allowed")
                RelayState.changed()
            }
            "result" -> toast(m.optString("msg"))
        }
    }

    private fun send(o: JSONObject) {
        val ch = channel
        if (ch == null) {
            toast("Not connected to the SIM phone")
            return
        }
        runCatching { sender.execute { runCatching { ch.send(o) }.onFailure { ch.close() } } }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    // ---- notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SMS, "Text messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING_QUIET, "Silenced incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun servicePending(action: String, req: Int) =
        PendingIntent.getService(this, req, Intent(this, ControllerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun buildStatusNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ControllerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = when (RelayState.callState) {
            "offhook" -> "In call ${RelayState.callNumber ?: ""}"
            "ringing" -> "Incoming call ${RelayState.callNumber ?: ""}"
            else -> RelayState.status
        }
        val b = Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Call Relay")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (RelayState.callState == "offhook") {
            b.addAction(Notification.Action.Builder(null, "End call", servicePending(ACTION_END, 2)).build())
        }
        return b.build()
    }

    private fun updateStatusNotification() {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatusNotification())
    }

    private fun showIncoming(number: String?) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, ControllerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        // Call style: the system always shows Decline and Answer as buttons, also in the pop-up.
        val caller = Person.Builder().setName(number?.let { Contacts.name(this, it) ?: it } ?: "Unknown number").build()
        val silenced = RelayState.silenced
        val n = Notification.Builder(this, if (silenced) CH_INCOMING_QUIET else CH_INCOMING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentText("Incoming call on SIM phone")
            .setStyle(Notification.CallStyle.forIncomingCall(caller, servicePending(ACTION_END, 3), servicePending(ACTION_ANSWER, 4)))
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Message", PendingIntent.getActivity(this, 5,
                Intent(this, ControllerActivity::class.java).putExtra(ControllerActivity.EXTRA_QUICK_REPLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build()
        if (!silenced) n.flags = n.flags or Notification.FLAG_INSISTENT
        getSystemService(NotificationManager::class.java).notify(INCOMING_ID, n)
    }

    private fun showSms(m: Msg) {
        val key = threadKey(m.number)
        val open = PendingIntent.getActivity(this, key.hashCode(),
            Intent(this, MessagesActivity::class.java).putExtra(MessagesActivity.EXTRA_THREAD, m.number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notifId = SMS_ID_BASE + (key.hashCode() and 0xffff)
        val b = Notification.Builder(this, CH_SMS)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(Contacts.name(this, m.number) ?: m.number)
            .setContentText(m.body)
            .setStyle(Notification.BigTextStyle().bigText(m.body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
        // One-tap "Copy code" for verification texts.
        Otp.find(m.body)?.let { code ->
            val copy = PendingIntent.getBroadcast(this, notifId,
                Intent(this, OtpCopyReceiver::class.java).putExtra(OtpCopyReceiver.EXTRA_CODE, code)
                    .putExtra(OtpCopyReceiver.EXTRA_NOTIF, notifId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "Copy $code", copy).build())
        }
        getSystemService(NotificationManager::class.java).notify(notifId, b.build())
    }

    private var lowBatteryWarned = false
    private var historyCount = 0
    @Volatile private var importing = false

    private fun onPhoneStatus(m: JSONObject) {
        RelayState.battery = m.optInt("battery", -1)
        RelayState.charging = m.optBoolean("charging")
        val arr = m.optJSONArray("sims")
        RelayState.signals = (0 until (arr?.length() ?: 0)).map {
            val o = arr!!.getJSONObject(it)
            Triple(o.optInt("slot"), o.optInt("bars"), o.optString("carrier"))
        }
        RelayState.changed()
        // Warn once when the SIM phone drops to 15% (it's what keeps calls and texts working).
        val low = RelayState.battery in 0..15 && !RelayState.charging
        if (low && !lowBatteryWarned) {
            lowBatteryWarned = true
            getSystemService(NotificationManager::class.java).notify(LOW_BATTERY_ID, Notification.Builder(this, CH_MISSED)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentTitle("SIM phone battery low: ${RelayState.battery}%")
                .setContentText("Charge ${RelayState.peerName ?: "the SIM phone"} to keep calls and texts working.")
                .setAutoCancel(true)
                .build())
        } else if (!low) {
            lowBatteryWarned = false
            getSystemService(NotificationManager::class.java).cancel(LOW_BATTERY_ID)
        }
    }

    private fun onSmsHistory(m: JSONObject) {
        val arr = m.optJSONArray("list")
        val msgs = (0 until (arr?.length() ?: 0)).map {
            val o = arr!!.getJSONObject(it)
            Msg(o.getString("id"), o.optString("number"), o.optString("body"), o.optLong("time"), o.optBoolean("in"), status = "sent", read = true)
        }
        historyCount += MessageStore.importHistory(this, msgs)
        if (m.optBoolean("done")) {
            prefs.smsHistoryImported = true
            if (importing || historyCount > 0) toast("Imported $historyCount old texts from the SIM phone")
            historyCount = 0
            importing = false
        }
    }

    private fun showMissed(number: String?) {
        val who = number?.let { Contacts.name(this, it) ?: it } ?: "Unknown number"
        val id = MISSED_ID_BASE + ((number ?: "").hashCode() and 0xffff)
        val b = Notification.Builder(this, CH_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText("$who · on SIM phone")
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, id, Intent(this, ControllerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        if (number != null) {
            b.addAction(Notification.Action.Builder(null, "Call back", PendingIntent.getActivity(this, id + 1,
                Intent(this, ControllerActivity::class.java).setAction(Intent.ACTION_DIAL)
                    .setData(android.net.Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            b.addAction(Notification.Action.Builder(null, "Message", PendingIntent.getActivity(this, id + 2,
                Intent(this, MessagesActivity::class.java).putExtra(MessagesActivity.EXTRA_THREAD, number)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    private fun cancelIncoming() = getSystemService(NotificationManager::class.java).cancel(INCOMING_ID)

    companion object {
        private const val CH_STATUS = "ctrl_status"
        private const val CH_INCOMING = "ctrl_incoming"
        private const val CH_INCOMING_QUIET = "ctrl_incoming_quiet"
        private const val NOTIF_ID = 2
        private const val INCOMING_ID = 3
        private const val CH_SMS = "ctrl_sms"
        private const val CH_MISSED = "ctrl_missed"
        private const val LOW_BATTERY_ID = 4
        private const val MISSED_ID_BASE = 200_000
        const val ACTION_MUTE = "mute"
        const val ACTION_BT = "bt"
        const val ACTION_DTMF = "dtmf"
        const val ACTION_HOLD = "hold"
        const val ACTION_MERGE = "merge"
        const val ACTION_SWAP = "swap"
        const val ACTION_IMPORT_SMS = "import_sms"
        const val ACTION_AUTOREPLY = "autoreply"
        const val EXTRA_JSON = "json"
        const val EXTRA_ADD = "add"
        const val ACTION_REFRESH_CALLS = "refresh_calls"
        const val ACTION_SPEAKER = "speaker"
        private const val SMS_ID_BASE = 1000
        const val ACTION_SMS = "sms"
        const val EXTRA_ID = "id"
        const val EXTRA_BODY = "body"
        const val ACTION_STOP = "stop"
        const val ACTION_DIAL = "dial"
        const val ACTION_END = "end"
        const val ACTION_ANSWER = "answer"
        const val ACTION_SILENCE = "silence"
        const val ACTION_RECONNECT = "reconnect"
        const val ACTION_HOTSPOT = "hotspot"
        const val EXTRA_ON = "on"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_SIM = "sim"

        fun start(ctx: Context, action: String? = null, configure: Intent.() -> Unit = {}) {
            ContextCompat.startForegroundService(ctx,
                Intent(ctx, ControllerService::class.java).setAction(action).apply(configure))
        }

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, ControllerService::class.java).setAction(ACTION_STOP))
    }
}
