package com.btcallrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Connection and setup options for the controller (Samsung). */
@SuppressLint("MissingPermission", "SetTextI18n")
class ControllerSettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var phoneStatus: TextView
    private lateinit var hostBtn: Button
    private lateinit var pairBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var autoReplyBtn: Button
    private var dialog: AlertDialog? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = Ui.page(this)
        col.addView(Ui.title(this, "Settings"))
        val card = Ui.card(this)
        status = Ui.label(this, sizeSp = 16f)
        card.addView(status)
        // SIM phone battery and signal per SIM (moved here from the keypad).
        phoneStatus = Ui.label(this, sizeSp = 15f)
        card.addView(phoneStatus)
        col.addView(card)

        col.addView(Ui.section(this, "SIM phone"))
        hostBtn = Ui.button(this, "Choose SIM phone") { chooseHost() }
        col.addView(hostBtn)
        pairBtn = Ui.button(this, "Pair with SIM phone") { startPairing() }
        col.addView(pairBtn)
        col.addView(Ui.button(this, "Reconnect now") { ControllerService.start(this, ControllerService.ACTION_RECONNECT) })
        col.addView(Ui.button(this, "Forget pairing") {
            prefs.forgetPairing()
            ControllerService.start(this, ControllerService.ACTION_RECONNECT)
            render()
        })

        col.addView(Ui.section(this, "Messages"))
        autoReplyBtn = Ui.button(this, "Auto-reply") { AutoReplyUi.show(this) { render() } }
        col.addView(autoReplyBtn)
        col.addView(Ui.button(this, "Import old texts from the SIM phone") {
            ControllerService.start(this, ControllerService.ACTION_IMPORT_SMS)
            Toast.makeText(this, "Importing old texts…", Toast.LENGTH_SHORT).show()
        })

        col.addView(Ui.section(this, "This phone"))
        batteryBtn = Ui.button(this, "Battery: allow running in background") { askBattery() }
        col.addView(batteryBtn)
        col.addView(Ui.button(this, "App permissions") { Ui.openAppSettings(this) })
        col.addView(Ui.button(this, "Stop Dialer on this phone") { ControllerService.stop(this) })
        col.addView(Ui.button(this, "Change phone role") {
            ControllerService.stop(this)
            prefs.mode = null
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        col.addView(Ui.label(this, "\nVoice stays on the SIM phone. Use earbuds connected to both phones to talk hands-free.", 13f))
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

    private fun chooseHost() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        val bonded = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No paired phones")
                .setMessage("First pair the SIM phone in Settings > Connections > Bluetooth, then come back.")
                .setPositiveButton("Open Bluetooth settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val names = bonded.map { "${it.name ?: "Unknown"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Which phone has the SIM?")
            .setItems(names) { _, w ->
                val d = bonded[w]
                if (d.address != prefs.peerAddress) prefs.forgetPairing()
                prefs.peerAddress = d.address
                prefs.peerName = d.name ?: d.address
                ControllerService.start(this, ControllerService.ACTION_RECONNECT)
                render()
            }.show()
    }

    private fun startPairing() {
        if (prefs.peerAddress == null) return chooseHost()
        prefs.forgetPairing()
        RelayState.enablePairing()
        ControllerService.start(this, ControllerService.ACTION_RECONNECT)
        Toast.makeText(this, "Make sure \"Allow new controller\" is on, on the SIM phone", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) Ui.openAppSettings(this)
        else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun render() {
        status.text = (if (RelayState.connected) "🟢 " else "🟡 ") + RelayState.status
        val line = if (RelayState.connected) phoneStatusLine(Pal(this), prefs) else ""
        phoneStatus.text = line
        phoneStatus.visibility = if (line.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        hostBtn.text = "SIM phone: " + (prefs.peerName ?: "not chosen (tap to choose)")
        pairBtn.text = when {
            RelayState.pairingMode -> "Pairing…"
            prefs.isPaired -> "✅ Paired (tap to pair again)"
            else -> "⚠️ Pair with SIM phone"
        }
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryBtn.text = if (ignoring) "✅ Battery: unrestricted" else "⚠️ Battery: allow running in background"
        autoReplyBtn.text = AutoReplyUi.summary(this)

        val p = RelayState.pendingConfirm
        if (p != null && dialog == null) {
            dialog = Ui.confirmDialog(this, p, "controller").also { it.setOnDismissListener { dialog = null } }
        } else if (p == null) {
            dialog?.dismiss()
        }
    }
}
