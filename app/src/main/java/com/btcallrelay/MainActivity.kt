package com.btcallrelay

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** First launch: pick what this phone is. Afterwards it just forwards to the right screen. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        when (prefs.mode) {
            Prefs.MODE_HOST -> return go(HostActivity::class.java)
            Prefs.MODE_CONTROLLER -> return go(ControllerActivity::class.java)
        }
        val col = Ui.page(this)
        col.addView(Ui.title(this, "BT Call Relay"))
        col.addView(Ui.label(this, "Install this app on both phones. Choose what THIS phone is:"))

        col.addView(Ui.section(this, "Phone with the SIM card"))
        col.addView(Ui.label(this, "e.g. Redmi. It places and receives the real calls."))
        col.addView(Ui.button(this, "This phone has the SIM (SIM host)", Ui.GREEN) {
            prefs.mode = Prefs.MODE_HOST
            go(HostActivity::class.java)
        })

        col.addView(Ui.section(this, "Phone without SIM"))
        col.addView(Ui.label(this, "e.g. Samsung. You dial from here and the SIM phone makes the call."))
        col.addView(Ui.button(this, "This phone is the controller", Ui.BLUE) {
            prefs.mode = Prefs.MODE_CONTROLLER
            go(ControllerActivity::class.java)
        })
    }

    private fun go(cls: Class<*>) {
        startActivity(Intent(this, cls))
        finish()
    }
}
