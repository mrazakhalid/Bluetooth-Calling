package com.btcallrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Msg(
    val id: String,
    val number: String,
    val body: String,
    val time: Long,
    val incoming: Boolean,
    var status: String = "", // outgoing: sending / sent / failed
    var read: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("number", number).put("body", body).put("time", time)
        .put("in", incoming).put("status", status).put("read", read)

    companion object {
        fun from(o: JSONObject) = Msg(o.getString("id"), o.getString("number"), o.getString("body"), o.getLong("time"),
            o.getBoolean("in"), o.optString("status"), o.optBoolean("read", true))
    }
}

/** Same person whether the number is written +92300…, 0300… or with spaces. */
fun threadKey(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length >= 10) digits.takeLast(10) else number.lowercase()
}

/** Small JSON-file list, private to the app. */
private class JsonList(ctx: Context, name: String, private val max: Int) {
    private val file = File(ctx.applicationContext.filesDir, name)

    fun load(): MutableList<Msg> = runCatching {
        val arr = JSONArray(file.readText())
        MutableList(arr.length()) { Msg.from(arr.getJSONObject(it)) }
    }.getOrDefault(mutableListOf())

    fun save(list: List<Msg>) {
        val arr = JSONArray()
        list.takeLast(max).forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}

/** Controller (Samsung): all relayed messages, oldest first. */
object MessageStore {
    private val lock = Any()
    private var cache: MutableList<Msg>? = null
    private lateinit var file: JsonList

    private fun list(ctx: Context): MutableList<Msg> {
        cache?.let { return it }
        file = JsonList(ctx, "messages.json", 5000)
        return file.load().also { cache = it }
    }

    fun all(ctx: Context): List<Msg> = synchronized(lock) { list(ctx).toList() }

    fun add(ctx: Context, m: Msg) = synchronized(lock) {
        val l = list(ctx)
        if (l.none { it.id == m.id }) { l += m; file.save(l) }
        RelayState.changed()
    }

    fun setStatus(ctx: Context, id: String, status: String) = synchronized(lock) {
        list(ctx).firstOrNull { it.id == id }?.let { it.status = status; file.save(list(ctx)) }
        RelayState.changed()
    }

    fun markRead(ctx: Context, key: String) = synchronized(lock) {
        val l = list(ctx)
        var changed = false
        l.filter { !it.read && threadKey(it.number) == key }.forEach { it.read = true; changed = true }
        if (changed) { file.save(l); RelayState.changed() }
    }

    /**
     * Adds old texts from the SIM phone. Skips ones already here: same id, or same person + text within
     * 2 minutes (a text that was relayed live and also sits in the phone's inbox). Returns how many were new.
     */
    fun importHistory(ctx: Context, msgs: List<Msg>): Int = synchronized(lock) {
        val l = list(ctx)
        val ids = l.mapTo(HashSet()) { it.id }
        val byKey = l.groupBy { threadKey(it.number) + "|" + it.body }
        var added = 0
        for (m in msgs) {
            if (m.id in ids) continue
            val twins = byKey[threadKey(m.number) + "|" + m.body]
            if (twins != null && twins.any { kotlin.math.abs(it.time - m.time) < 120_000 }) continue
            l += m; ids += m.id; added++
        }
        if (added > 0) {
            l.sortBy { it.time }
            file.save(l)
        }
        RelayState.changed()
        added
    }

    fun deleteMessage(ctx: Context, id: String) = synchronized(lock) {
        val l = list(ctx)
        if (l.removeAll { it.id == id }) file.save(l)
        RelayState.changed()
    }

    fun deleteThread(ctx: Context, key: String) = synchronized(lock) {
        val l = list(ctx)
        l.removeAll { threadKey(it.number) == key }
        file.save(l)
        RelayState.changed()
    }

    fun unread(ctx: Context) = synchronized(lock) { list(ctx).count { !it.read } }
}

/** Host (Redmi): incoming texts waiting to be delivered to the controller (kept across restarts). */
object SmsOutbox {
    private val lock = Any()

    fun add(ctx: Context, m: Msg) = synchronized(lock) {
        val f = JsonList(ctx, "sms_outbox.json", 300)
        f.save(f.load().apply { add(m) })
    }

    fun pending(ctx: Context): List<Msg> = synchronized(lock) { JsonList(ctx, "sms_outbox.json", 300).load() }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val f = JsonList(ctx, "sms_outbox.json", 300)
        f.save(f.load().filter { it.id != id })
    }
}

/** Host (Redmi): catches every incoming SMS and queues it for the controller. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = Prefs(ctx)
        if (prefs.mode != Prefs.MODE_HOST || !prefs.isPaired) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in several parts; join them per sender.
        parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "Unknown" }.forEach { (from, ps) ->
            val body = ps.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            SmsOutbox.add(ctx, Msg(UUID.randomUUID().toString(), from, body, ps.first().timestampMillis, incoming = true, read = false))
            RelayState.log("SMS received from $from, forwarding")
        }
        HostService.instance?.flushSms()
        // Auto-reply to texters (if switched on from the controller), off the main thread.
        val senders = parts.mapNotNull { it.displayOriginatingAddress ?: it.originatingAddress }.distinct()
        val pending = goAsync()
        Thread {
            try {
                senders.forEach { from -> AutoReply.maybeReply(ctx, from, "sms")?.let { HostService.instance?.notifyAutoReply(from, it) } }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
