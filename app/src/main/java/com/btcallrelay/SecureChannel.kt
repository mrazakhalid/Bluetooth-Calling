package com.btcallrelay

import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("7f3c9a52-4e1b-4d8a-9c6e-2b5f8d1a0e47")
    const val SERVICE_NAME = "BTCallRelay"
    const val MAX_FRAME = 64 * 1024
    const val PING_MS = 15_000L
    const val DEAD_MS = 45_000L
}

class HandshakeError(val reason: String) : IOException(reason)

/**
 * Length-prefixed JSON frames over an encrypted (secure RFCOMM) Bluetooth socket.
 * After the handshake every frame carries a sequence number and an HMAC with the session key,
 * so frames cannot be forged, altered or replayed by anything that doesn't know the pairing key.
 */
class SecureChannel(private val socket: BluetoothSocket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
    private var sessionKey: ByteArray? = null
    private var myDir = byteArrayOf()
    private var peerDir = byteArrayOf()
    private var sendSeq = 0L
    private var recvSeq = 0L

    @Volatile var lastRx = System.currentTimeMillis()
        private set

    val remoteAddress: String get() = socket.remoteDevice.address

    fun sendRaw(o: JSONObject) {
        val b = o.toString().toByteArray()
        synchronized(output) {
            output.writeInt(b.size)
            output.write(b)
            output.flush()
        }
    }

    fun readRaw(): JSONObject {
        val n = input.readInt()
        if (n !in 1..Protocol.MAX_FRAME) throw IOException("bad frame size $n")
        val b = ByteArray(n)
        input.readFully(b)
        lastRx = System.currentTimeMillis()
        return JSONObject(String(b))
    }

    fun startSession(key: ByteArray, isHost: Boolean) {
        sessionKey = key
        myDir = (if (isHost) "H" else "C").toByteArray()
        peerDir = (if (isHost) "C" else "H").toByteArray()
    }

    fun send(body: JSONObject) {
        val k = sessionKey ?: throw IOException("no session")
        val s = body.toString()
        synchronized(output) {
            val seq = ++sendSeq
            val mac = Crypto.hmac(k, myDir, seq.toString().toByteArray(), s.toByteArray())
            sendRaw(JSONObject().put("seq", seq).put("body", s).put("mac", Crypto.b64(mac)))
        }
    }

    fun receive(): JSONObject {
        val k = sessionKey ?: throw IOException("no session")
        val o = readRaw()
        val seq = o.getLong("seq")
        val s = o.getString("body")
        val mac = Crypto.unb64(o.getString("mac"))
        if (seq <= recvSeq) throw SecurityException("replayed frame")
        if (!Crypto.same(mac, Crypto.hmac(k, peerDir, seq.toString().toByteArray(), s.toByteArray())))
            throw SecurityException("bad signature")
        recvSeq = seq
        return JSONObject(s)
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Pairing (first time): ECDH key exchange, both screens show a 6-digit code, the user confirms on BOTH
 * phones, and both store the derived key. Later connections: mutual HMAC challenge-response with fresh
 * nonces from each side, then a per-connection session key.
 */
object Handshake {
    private fun t(o: JSONObject) = o.optString("t")

    private fun reject(ch: SecureChannel, reason: String): Nothing {
        runCatching { ch.sendRaw(JSONObject().put("t", "error").put("reason", reason)) }
        throw HandshakeError(reason)
    }

    fun host(
        ch: SecureChannel,
        prefs: Prefs,
        pairingAllowed: () -> Boolean,
        confirmPairing: (code: String) -> Boolean,
    ) {
        val hn = Crypto.random(16)
        ch.sendRaw(JSONObject().put("t", "hello").put("v", 1).put("nonce", Crypto.b64(hn)))
        var m = ch.readRaw()

        if (t(m) == "pair_req") {
            if (!pairingAllowed()) reject(ch, "pairing_off")
            val kp = Crypto.newEcKeyPair()
            val hostPub = kp.public.encoded
            val ctrlPub = Crypto.unb64(m.getString("pub"))
            val secret = Crypto.ecdh(kp.private, ctrlPub)
            ch.sendRaw(JSONObject().put("t", "pair_resp").put("pub", Crypto.b64(hostPub)))
            if (!confirmPairing(Crypto.pairingCode(secret, hostPub, ctrlPub))) reject(ch, "pair_rejected")
            prefs.sharedKey = Crypto.pairingKey(secret, hostPub, ctrlPub)
            prefs.peerAddress = ch.remoteAddress
            prefs.peerName = m.optString("name", "Controller")
            ch.sendRaw(JSONObject().put("t", "pair_ok"))
            m = ch.readRaw()
        }

        if (t(m) != "auth") reject(ch, "unexpected")
        if (ch.remoteAddress != prefs.peerAddress) reject(ch, "not_paired")
        val key = prefs.sharedKey ?: reject(ch, "not_paired")
        val cn = Crypto.unb64(m.getString("nonce"))
        val mac = Crypto.unb64(m.getString("mac"))
        if (cn.size != 16 || !Crypto.same(mac, Crypto.hmac(key, "C".toByteArray(), hn, cn))) reject(ch, "auth_failed")
        ch.sendRaw(JSONObject().put("t", "auth_ok").put("mac", Crypto.b64(Crypto.hmac(key, "H".toByteArray(), hn, cn))))
        ch.startSession(Crypto.hmac(key, "S".toByteArray(), hn, cn), isHost = true)
    }

    fun controller(
        ch: SecureChannel,
        prefs: Prefs,
        deviceName: String,
        allowPairing: Boolean,
        confirmPairing: (code: String) -> Boolean,
    ) {
        val hello = ch.readRaw()
        if (t(hello) != "hello") throw HandshakeError("not_a_host")
        val hn = Crypto.unb64(hello.getString("nonce"))
        if (hn.size != 16) throw HandshakeError("not_a_host")

        var key = prefs.sharedKey
        if (key == null) {
            if (!allowPairing) throw HandshakeError("not_paired_local")
            val kp = Crypto.newEcKeyPair()
            val ctrlPub = kp.public.encoded
            ch.sendRaw(JSONObject().put("t", "pair_req").put("pub", Crypto.b64(ctrlPub)).put("name", deviceName))
            val resp = ch.readRaw()
            if (t(resp) == "error") throw HandshakeError(resp.optString("reason"))
            val hostPub = Crypto.unb64(resp.getString("pub"))
            val secret = Crypto.ecdh(kp.private, hostPub)
            if (!confirmPairing(Crypto.pairingCode(secret, hostPub, ctrlPub))) throw HandshakeError("cancelled")
            val ok = ch.readRaw()
            if (t(ok) != "pair_ok") throw HandshakeError(ok.optString("reason", "pair_failed"))
            key = Crypto.pairingKey(secret, hostPub, ctrlPub)
            prefs.sharedKey = key
        }

        val cn = Crypto.random(16)
        ch.sendRaw(JSONObject().put("t", "auth").put("nonce", Crypto.b64(cn))
            .put("mac", Crypto.b64(Crypto.hmac(key, "C".toByteArray(), hn, cn))))
        val r = ch.readRaw()
        if (t(r) == "error") throw HandshakeError(r.optString("reason"))
        if (t(r) != "auth_ok" || !Crypto.same(Crypto.unb64(r.getString("mac")), Crypto.hmac(key, "H".toByteArray(), hn, cn)))
            throw HandshakeError("host_auth_failed")
        ch.startSession(Crypto.hmac(key, "S".toByteArray(), hn, cn), isHost = false)
    }
}
