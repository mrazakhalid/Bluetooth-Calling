package com.btcallrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/** Battery, signal bars and carrier of each SIM on the SIM phone. */
@SuppressLint("MissingPermission")
object PhoneStatus {
    fun build(ctx: Context): JSONObject {
        val o = JSONObject().put("t", "phone_status")
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            o.put("battery", if (level >= 0) level * 100 / scale else -1)
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
        val sims = JSONArray()
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            runCatching {
                sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }.forEach { info ->
                    val sub = tm.createForSubscriptionId(info.subscriptionId)
                    sims.put(JSONObject()
                        .put("slot", info.simSlotIndex)
                        .put("carrier", info.carrierName?.toString() ?: info.displayName?.toString() ?: "")
                        .put("bars", runCatching { sub.signalStrength?.level ?: 0 }.getOrDefault(0)))
                }
            }
        }
        return o.put("sims", sims)
    }
}

/** Old texts from the SIM phone's inbox and sent folder, newest first. */
@SuppressLint("MissingPermission")
object SmsHistory {
    fun read(ctx: Context, limit: Int): List<JSONObject> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = ArrayList<JSONObject>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})",
                null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val addr = c.getString(1) ?: continue
                    out += JSONObject().put("id", "h_" + c.getLong(0)).put("number", addr)
                        .put("body", c.getString(2) ?: "").put("time", c.getLong(3))
                        .put("in", c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
            }
        }
        return out
    }
}

/**
 * Auto-reply on the SIM phone: texts back missed callers and/or people who text, using a message set on
 * the controller. At most one reply per number every 30 minutes, and never to short codes or names.
 */
@SuppressLint("MissingPermission")
object AutoReply {
    private const val GAP_MS = 30 * 60 * 1000L

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("btcallrelay_autoreply", Context.MODE_PRIVATE)

    fun save(ctx: Context, m: JSONObject) {
        sp(ctx).edit().putBoolean("on", m.optBoolean("on")).putString("text", m.optString("text"))
            .putBoolean("calls", m.optBoolean("calls", true)).putBoolean("sms", m.optBoolean("sms", false))
            .putInt("sim", m.optInt("sim", -1)).apply()
    }

    fun config(ctx: Context): JSONObject = sp(ctx).let {
        JSONObject().put("on", it.getBoolean("on", false)).put("text", it.getString("text", "") ?: "")
            .put("calls", it.getBoolean("calls", true)).put("sms", it.getBoolean("sms", false)).put("sim", it.getInt("sim", -1))
    }

    /** Sends the auto-reply if it's on for [reason] ("call" or "sms"). Returns the text sent, or null. */
    fun maybeReply(ctx: Context, number: String?, reason: String): String? {
        val s = sp(ctx)
        if (!s.getBoolean("on", false) || number == null) return null
        if (reason == "call" && !s.getBoolean("calls", true)) return null
        if (reason == "sms" && !s.getBoolean("sms", false)) return null
        val text = s.getString("text", "")?.trim().orEmpty()
        val digits = number.filter { it.isDigit() }
        if (text.isEmpty() || digits.length < 7 || number.any { it.isLetter() }) return null
        val key = "last_" + threadKey(number)
        val now = System.currentTimeMillis()
        if (now - s.getLong(key, 0) < GAP_MS) return null
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            smsManager(ctx, s.getInt("sim", -1)).let { sms -> sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null) }
            s.edit().putLong(key, now).apply()
            RelayState.log("Auto-reply sent to $number")
            text
        }.getOrNull()
    }

    /** SmsManager for the SIM in [slotIndex] of the phone's call-capable accounts (same numbering the controller uses). */
    fun smsManager(ctx: Context, slotIndex: Int): SmsManager {
        val base = ctx.getSystemService(SmsManager::class.java)
        val handle = runCatching { ctx.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.getOrNull(slotIndex) }.getOrNull()
        val subId = handle?.let { runCatching { ctx.getSystemService(TelephonyManager::class.java).getSubscriptionId(it) }.getOrNull() }
            ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
    }
}
