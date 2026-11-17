package com.btcallrelay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Auto-reply settings, edited on the controller and run on the SIM phone (so it also works when the
 * controller is out of range). Replies at most once per person every 30 minutes.
 */
object AutoReplyUi {
    private const val DEFAULT_TEXT = "I can't take your call right now. I'll get back to you soon."

    private fun sp(ctx: Context) = ctx.getSharedPreferences("btcallrelay_ar_ctrl", Context.MODE_PRIVATE)

    /** Short status for menus/buttons, from what the SIM phone confirmed (falls back to local settings). */
    fun summary(ctx: Context): String {
        val c = RelayState.autoReply
        val on = c?.optBoolean("on") ?: sp(ctx).getBoolean("on", false)
        if (!on) return "Auto-reply: Off"
        val calls = c?.optBoolean("calls") ?: sp(ctx).getBoolean("calls", true)
        val sms = c?.optBoolean("sms") ?: sp(ctx).getBoolean("sms", false)
        return "Auto-reply: On (" + listOfNotNull("missed calls".takeIf { calls }, "texts".takeIf { sms }).joinToString(" + ") + ")"
    }

    fun show(act: Activity, onSaved: () -> Unit = {}) {
        val s = sp(act)
        val c = RelayState.autoReply
        val pad = Ui.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, Ui.dp(act, 8), pad, 0) }
        val on = CheckBox(act).apply {
            text = "Auto-reply on"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isChecked = c?.optBoolean("on") ?: s.getBoolean("on", false)
        }
        val text = EditText(act).apply {
            hint = "Reply message"
            setText(c?.optString("text")?.takeIf { it.isNotBlank() } ?: s.getString("text", DEFAULT_TEXT))
            minLines = 2
        }
        val calls = CheckBox(act).apply { this.text = "Reply to missed calls"; isChecked = c?.optBoolean("calls") ?: s.getBoolean("calls", true) }
        val sms = CheckBox(act).apply { this.text = "Reply to incoming texts"; isChecked = c?.optBoolean("sms") ?: s.getBoolean("sms", false) }
        box.addView(on)
        box.addView(text)
        box.addView(calls)
        box.addView(sms)
        box.addView(TextView(act).apply {
            this.text = "Sent from the SIM phone (texts SIM), at most once per person every 30 minutes. Not sent to short codes or names."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, Ui.dp(act, 8), 0, 0)
        })
        AlertDialog.Builder(act).setTitle("Auto-reply").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val msg = text.text.toString().trim().ifEmpty { DEFAULT_TEXT }
                s.edit().putBoolean("on", on.isChecked).putString("text", msg)
                    .putBoolean("calls", calls.isChecked).putBoolean("sms", sms.isChecked).apply()
                if (!RelayState.connected) {
                    Toast.makeText(act, "Saved. It'll be sent to the SIM phone when connected.", Toast.LENGTH_LONG).show()
                } else {
                    val json = JSONObject().put("on", on.isChecked).put("text", msg).put("calls", calls.isChecked)
                        .put("sms", sms.isChecked).put("sim", Prefs(act).smsSimChoice)
                    ControllerService.start(act, ControllerService.ACTION_AUTOREPLY) { putExtra(ControllerService.EXTRA_JSON, json.toString()) }
                    Toast.makeText(act, if (on.isChecked) "Auto-reply on" else "Auto-reply off", Toast.LENGTH_SHORT).show()
                }
                onSaved()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Settings saved while disconnected, to push on the next connection. */
    fun pending(ctx: Context): JSONObject? {
        val s = sp(ctx)
        if (!s.contains("on")) return null
        return JSONObject().put("on", s.getBoolean("on", false)).put("text", s.getString("text", DEFAULT_TEXT))
            .put("calls", s.getBoolean("calls", true)).put("sms", s.getBoolean("sms", false)).put("sim", Prefs(ctx).smsSimChoice)
    }
}
