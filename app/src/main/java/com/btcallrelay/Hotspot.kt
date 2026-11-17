package com.btcallrelay

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Wi-Fi hotspot on/off for the SIM phone. There is no public API for this, so it calls Android's own
 * TetheringManager (hidden API). Android lets a normal app use it when the user has granted
 * "Modify system settings" (WRITE_SETTINGS) and the system/carrier doesn't demand more.
 */
@SuppressLint("PrivateApi", "WrongConstant")
object Hotspot {
    private const val TETHERING_WIFI = 0
    private const val WIFI_AP_STATE_ENABLED = 13
    const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    const val EXTRA_AP_STATE = "wifi_state"

    init {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/net/", "Landroid/net/wifi/")
    }

    fun allowed(ctx: Context) = Settings.System.canWrite(ctx)

    fun isOn(ctx: Context): Boolean = runCatching {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int == WIFI_AP_STATE_ENABLED
    }.getOrDefault(false)

    fun isOnState(apState: Int) = apState == WIFI_AP_STATE_ENABLED

    /** Calls [done] on [executor] with (success, message). */
    fun start(ctx: Context, executor: Executor, done: (Boolean, String) -> Unit) {
        if (!allowed(ctx)) return done(false, "Hotspot control not allowed on the SIM phone (Modify system settings)")
        try {
            val tm = ctx.applicationContext.getSystemService("tethering")
                ?: return done(false, "Tethering service not available")
            val reqCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val builder = builderCls.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            val request = builderCls.getMethod("build").invoke(builder)
            val callback = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { proxy, m, args ->
                when (m.name) {
                    "onTetheringStarted" -> { done(true, "Hotspot on"); null }
                    "onTetheringFailed" -> { done(false, "Hotspot failed: ${errorText(args?.getOrNull(0) as? Int ?: -1)}"); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "HotspotCallback"
                    else -> null
                }
            }
            tm.javaClass.getMethod("startTethering", reqCls, Executor::class.java, cbCls)
                .invoke(tm, request, executor, callback)
        } catch (e: Throwable) {
            done(false, "Hotspot not supported here: ${(e.cause ?: e).javaClass.simpleName} ${(e.cause ?: e).message ?: ""}".trim())
        }
    }

    fun stop(ctx: Context): Pair<Boolean, String> {
        if (!allowed(ctx)) return false to "Hotspot control not allowed on the SIM phone (Modify system settings)"
        return try {
            val tm = ctx.applicationContext.getSystemService("tethering")
                ?: return false to "Tethering service not available"
            tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
            true to "Hotspot off"
        } catch (e: Throwable) {
            false to "Could not turn hotspot off: ${(e.cause ?: e).javaClass.simpleName} ${(e.cause ?: e).message ?: ""}".trim()
        }
    }

    private fun errorText(code: Int) = when (code) {
        11 -> "the carrier's tethering check failed"
        14 -> "no permission (code 14)"
        3 -> "Wi-Fi hotspot is unavailable"
        5, 6, 8 -> "the system could not start it (code $code)"
        else -> "code $code"
    }
}
