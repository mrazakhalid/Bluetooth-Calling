package com.btcallrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.IntentSender
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/** Screen on the phone WITH the SIM (Redmi). */
class HostActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var call: TextView
    private lateinit var controller: TextView
    private lateinit var startStop: Button
    private lateinit var pairBtn: Button
    private lateinit var permBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var hotspotPermBtn: Button
    private lateinit var hotspotTestBtn: Button
    private lateinit var callCtlBtn: Button
    private lateinit var log: TextView
    private var dialog: AlertDialog? = null
    private val listener: () -> Unit = { render() }

    private val perms = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = Ui.page(this)
        col.addView(Ui.title(this, "SIM host"))

        val card = Ui.card(this)
        status = Ui.label(this, sizeSp = 18f)
        controller = Ui.label(this)
        call = Ui.label(this)
        card.addView(status); card.addView(controller); card.addView(call)
        col.addView(card)

        startStop = Ui.button(this, "Start relay", Ui.GREEN) {
            if (RelayState.serviceRunning) HostService.stop(this) else startRelay()
        }
        col.addView(startStop)

        col.addView(Ui.section(this, "Pairing"))
        col.addView(Ui.label(this, "Pair the Bluetooth of both phones in Android settings first. Then tap below and, on the controller, tap \"Pair with SIM phone\". Only one controller can be paired."))
        pairBtn = Ui.button(this, "Allow new controller (2 min)") {
            if (!RelayState.serviceRunning) startRelay()
            if (RelayState.pairingMode) RelayState.disablePairing() else RelayState.enablePairing()
        }
        col.addView(pairBtn)
        col.addView(Ui.button(this, "Forget paired controller") {
            AlertDialog.Builder(this).setTitle("Forget controller?")
                .setMessage("The controller will be blocked until you pair again.")
                .setPositiveButton("Forget") { _, _ ->
                    prefs.forgetPairing(); prefs.peerAddress = null; prefs.peerName = null
                    HostService.stop(this)
                    render()
                }.setNegativeButton("Cancel", null).show()
        })

        col.addView(Ui.section(this, "Setup (keeps the relay alive)"))
        permBtn = Ui.button(this, "Grant permissions") { requestPermissions(perms, 1) }
        col.addView(permBtn)
        batteryBtn = Ui.button(this, "Battery: allow running in background") { askBattery() }
        col.addView(batteryBtn)
        col.addView(Ui.button(this, "Xiaomi: turn on Autostart") { openAutostart() })
        hotspotPermBtn = Ui.button(this, "Allow hotspot control") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        col.addView(hotspotPermBtn)
        hotspotTestBtn = Ui.button(this, "Test hotspot on/off") {
            if (!RelayState.serviceRunning) return@button startRelay()
            startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_TEST_HOTSPOT))
        }
        col.addView(hotspotTestBtn)
        callCtlBtn = Ui.button(this, "Enable call control") { enableCallControl() }
        col.addView(callCtlBtn)
        col.addView(Ui.label(this, "On Redmi also: Settings > Apps > BT Call Relay > Battery saver > No restrictions. In Recent apps, pull the app card down to lock it.", 13f))

        col.addView(Ui.section(this, "Activity log"))
        log = Ui.label(this, sizeSp = 12f).apply { setTextIsSelectable(true) }
        col.addView(log)

        col.addView(Ui.section(this, "Other"))
        col.addView(Ui.button(this, "Change phone role") {
            HostService.stop(this)
            prefs.mode = null
            startActivity(Intent(this, MainActivity::class.java)); finish()
        })
    }

    override fun onResume() {
        super.onResume()
        RelayState.addListener(listener)
        render()
    }

    override fun onPause() {
        RelayState.removeListener(listener)
        super.onPause()
    }

    private fun missingPerms() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun startRelay() {
        if (missingPerms().isNotEmpty()) {
            requestPermissions(perms, 2)
            return
        }
        HostService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (missingPerms().isEmpty()) {
            if (requestCode == 2) HostService.start(this)
        } else if (permissions.any { !shouldShowRequestPermissionRationale(it) && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            AlertDialog.Builder(this).setTitle("Permissions needed")
                .setMessage("Some permissions were denied. Open app settings and allow Phone, Call logs, SMS, Nearby devices and Notifications.")
                .setPositiveButton("Open settings") { _, _ -> Ui.openAppSettings(this) }
                .setNegativeButton("Cancel", null).show()
        }
        render()
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) Ui.openAppSettings(this)
        else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    @Suppress("DEPRECATION")
    private fun hasAssociation(): Boolean = runCatching {
        val addr = prefs.peerAddress ?: return false
        getSystemService(CompanionDeviceManager::class.java).associations.any { it.equals(addr, ignoreCase = true) }
    }.getOrDefault(false)

    /** Links the controller as a "watch" companion so Android lets this app send keypad tones and control calls. */
    private fun enableCallControl() {
        val addr = prefs.peerAddress ?: return run {
            AlertDialog.Builder(this).setMessage("Pair the controller first.").setPositiveButton("OK", null).show(); Unit
        }
        if (hasAssociation()) {
            AlertDialog.Builder(this).setTitle("Call control")
                .setMessage(if (RelayInCallService.instance != null) "Enabled and working." else "Enabled. It switches on during the next call.")
                .setPositiveButton("OK", null).show()
            return
        }
        val req = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(addr).build())
            .setSingleDevice(true)
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .build()
        getSystemService(CompanionDeviceManager::class.java).associate(req, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                runCatching { startIntentSenderForResult(intentSender, REQ_ASSOCIATE, null, 0, 0, 0) }
            }
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                RelayState.log("Call control enabled for ${prefs.peerName}")
                render()
            }
            override fun onFailure(error: CharSequence?) {
                RelayState.log("Call control setup failed: ${error ?: "unknown"}")
                render()
            }
        })
    }

    private fun openAutostart() {
        val intent = Intent().setComponent(ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        runCatching { startActivity(intent) }.onFailure { Ui.openAppSettings(this) }
    }

    private fun render() {
        val running = RelayState.serviceRunning
        status.text = when {
            !running -> "⏹  Relay stopped"
            RelayState.connected -> "🟢  ${RelayState.status}"
            else -> "🟡  ${RelayState.status}"
        }
        controller.text = "Paired controller: " + if (prefs.isPaired) (prefs.peerName ?: prefs.peerAddress) else "none"
        call.text = when (RelayState.callState) {
            "ringing" -> "📞 Incoming: ${RelayState.callNumber ?: "unknown"}"
            "offhook" -> "📞 In call: ${RelayState.callNumber ?: ""}"
            else -> "No call"
        }
        startStop.text = if (running) "Stop relay" else "Start relay"
        startStop.background = Ui.rounded(if (running) Ui.RED else Ui.GREEN, Ui.dp(this, 28).toFloat())
        pairBtn.text = if (RelayState.pairingMode) "Waiting for controller… (tap to cancel)" else "Allow new controller (2 min)"
        val missing = missingPerms()
        permBtn.text = if (missing.isEmpty()) "✅ Permissions granted" else "⚠️ Grant permissions (${missing.size} missing)"
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryBtn.text = if (ignoring) "✅ Battery: unrestricted" else "⚠️ Battery: allow running in background"
        hotspotPermBtn.text = if (Settings.System.canWrite(this)) "✅ Hotspot control allowed" else "⚠️ Allow hotspot control (Modify system settings)"
        hotspotTestBtn.text = "Test hotspot (now ${if (RelayState.hotspotOn == true) "ON" else "off"})"
        callCtlBtn.text = if (RelayInCallService.instance != null || hasAssociation()) "✅ Call control enabled (keypad tones, mute, speaker)" else "⚠️ Enable call control (keypad tones, mute, speaker)"
        log.text = RelayState.logText().ifEmpty { "Nothing yet" }

        val p = RelayState.pendingConfirm
        if (p != null && dialog == null) {
            dialog = Ui.confirmDialog(this, p, "SIM phone").also { it.setOnDismissListener { dialog = null } }
        } else if (p == null) {
            dialog?.dismiss()
        }
    }

    companion object {
        private const val REQ_ASSOCIATE = 7
    }
}
