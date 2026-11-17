package com.btcallrelay

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("btcallrelay", Context.MODE_PRIVATE)

    /** MODE_HOST on the phone with the SIM, MODE_CONTROLLER on the phone without. */
    var mode: String?
        get() = sp.getString("mode", null)
        set(v) = sp.edit().putString("mode", v).apply()

    /** Host: the one controller allowed in. Controller: the host to connect to. */
    var peerAddress: String?
        get() = sp.getString("peer_address", null)
        set(v) = sp.edit().putString("peer_address", v).apply()

    var peerName: String?
        get() = sp.getString("peer_name", null)
        set(v) = sp.edit().putString("peer_name", v).apply()

    /** Shared secret from pairing, stored encrypted by the Keystore. */
    var sharedKey: ByteArray?
        get() = sp.getString("key", null)?.let { runCatching { KeyStoreBox.open(Crypto.unb64(it)) }.getOrNull() }
        set(v) = sp.edit().putString("key", v?.let { Crypto.b64(KeyStoreBox.seal(it)) }).apply()

    val isPaired: Boolean get() = sp.contains("key") && sp.getString("key", null) != null

    var simChoice: Int
        get() = sp.getInt("sim_choice", -1)
        set(v) = sp.edit().putInt("sim_choice", v).apply()

    /** SIM for texts, separate from calls. Defaults to SIM 2 (index 1); falls back to default if there's no SIM 2. */
    var smsSimChoice: Int
        get() = sp.getInt("sms_sim_choice", 1)
        set(v) = sp.edit().putInt("sms_sim_choice", v).apply()

    /** Names shown for the SIM phone's cards, by slot. */
    fun simName(slot: Int): String = sp.getString("sim_name_$slot", null) ?: when (slot) { 0 -> "Jazz"; 1 -> "Onic"; else -> "SIM ${slot + 1}" }

    /** Number to use without asking, for a contact with several numbers (keyed by contact name). */
    fun defaultNumber(contact: String): String? = sp.getString("default_num_" + contact.lowercase(), null)

    fun setDefaultNumber(contact: String, number: String?) {
        val key = "default_num_" + contact.lowercase()
        sp.edit().apply { if (number == null) remove(key) else putString(key, number) }.apply()
    }

    var smsHistoryImported: Boolean
        get() = sp.getBoolean("sms_history_imported", false)
        set(v) = sp.edit().putBoolean("sms_history_imported", v).apply()

    var recent: List<String>
        get() = sp.getString("recent", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("recent", v.take(25).joinToString("\n")).apply()

    fun addRecent(number: String) {
        recent = listOf(number) + recent.filter { it != number }
    }

    fun forgetPairing() {
        sp.edit().remove("key").apply()
    }

    companion object {
        const val MODE_HOST = "host"
        const val MODE_CONTROLLER = "controller"
    }
}
