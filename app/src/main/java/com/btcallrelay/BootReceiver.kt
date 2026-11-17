package com.btcallrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the relay after a reboot or an app update, so it works "all the time". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        if (!prefs.isPaired) return
        runCatching {
            when (prefs.mode) {
                Prefs.MODE_HOST -> HostService.start(ctx)
                Prefs.MODE_CONTROLLER -> ControllerService.start(ctx)
            }
        }
    }
}
