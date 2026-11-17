package com.btcallrelay

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.widget.Toast

/** Finds one-time codes in verification texts ("Your OTP is 482913", "code: 1234", "G-583920 is your…"). */
object Otp {
    private val keyword = Regex("(?i)(otp|code|verif|pin\\b|password|passcode|one[- ]time|کوڈ|token)")
    private val number = Regex("(?<![\\d+])(\\d{4,8})(?![\\d])")
    private val dashed = Regex("(?<![\\d])(\\d{3})[- ](\\d{3})(?![\\d])")

    fun find(body: String): String? {
        if (!keyword.containsMatchIn(body)) return null
        // Prefer the number closest to the keyword; skip amounts like "Rs 1500" and years.
        val k = keyword.find(body)?.range?.first ?: 0
        val candidates = number.findAll(body).map { it.groupValues[1] to it.range.first }
            .filter { (v, at) ->
                val before = body.substring(maxOf(0, at - 4), at).lowercase()
                !before.contains("rs") && !before.contains("pkr") && !before.contains("$") && !(v.length == 4 && v.startsWith("20") && v.toInt() in 2000..2099)
            }.toList()
        candidates.minByOrNull { kotlin.math.abs(it.second - k) }?.let { return it.first }
        return dashed.find(body)?.let { it.groupValues[1] + it.groupValues[2] }
    }

    /** Copies quietly (marked sensitive so the keyboard doesn't preview it). */
    fun copy(ctx: Context, code: String) {
        val clip = ClipData.newPlainText("OTP", code)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}

/** Handles the notification's "Copy 123456" button. */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        Otp.copy(ctx, code)
        Toast.makeText(ctx, "Code $code copied", Toast.LENGTH_SHORT).show()
        val id = intent.getIntExtra(EXTRA_NOTIF, -1)
        if (id >= 0) ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NOTIF = "notif"
    }
}
