// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Full-state encrypted UDP to the Windows receiver, independent of Satellite's fixed Xbox report.
 * PIN pairing mints a reconnect token; while the app is visible the link reconnects on its own
 * with that token whenever the receiver restarts or the session drops.
 */
class ReduxLink(context: Context, private val state: ControllerState, private val changed: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("redux", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val control = Executors.newSingleThreadScheduledExecutor()
    private var socket: DatagramSocket? = null
    private var session = ByteArray(0)
    private var secret = ByteArray(0)
    private var target: InetSocketAddress? = null
    private var sequence = 0L
    private var host = prefs.getString("host", "").orEmpty()
    private var token: ByteArray? = prefs.getString("token", null)?.let { runCatching { unhex(it) }.getOrNull() }?.takeIf { it.size == 32 }
    /** SHA-256 of the paired PC's certificate: it identifies that PC at any address. Phones
     *  paired before 0.5 have it only under the paired address. */
    private var pairedCert: String? = prefs.getString("paired_cert", null) ?: prefs.getString("cert:$host", null)
    @Volatile private var lastFingerprint: String? = null
    private var lastAttempt = 0L
    private var failures = 0
    @Volatile var connected = false; private set
    @Volatile var enabled = true
    @Volatile var visible = true
    @Volatile var outputReady = false; private set
    /** The receiver accepts the packet with the mouse section (receiver 0.4 and later). */
    @Volatile var mouseProtocol = false; private set
    /** Windows has the Redux Phone Mouse device. */
    @Volatile var mouseReady = false; private set
    @Volatile var pcName: String = prefs.getString("pc", "").orEmpty(); private set
    @Volatile var wantConnected = token != null; private set
    /** The profile the PC asked the phone to show (null when the PC has no opinion). */
    @Volatile var pcProfile: String? = null; private set
    var onProfile: ((String?) -> Unit)? = null

    val paired: Boolean get() = token != null && host.isNotEmpty()
    val pairedHost: String get() = host

    init {
        // Keep the upgraded phone's certificate under its own key, so a later move to a new
        // address (which has no cert:<address> entry) still knows which PC it paired with.
        if (prefs.getString("paired_cert", null) == null && pairedCert != null) prefs.edit().putString("paired_cert", pairedCert).apply()
        worker.scheduleWithFixedDelay({ runCatching { send() }.onFailure { fail(it) } }, 0, 8, TimeUnit.MILLISECONDS)
        control.scheduleWithFixedDelay({
            if (connected) {
                val expected = session
                runCatching { checkStatus(expected) }.onFailure { error ->
                    if (!worker.isShutdown) worker.execute { if (connected && session === expected) fail(error) }
                }
            } else if (wantConnected && visible && paired && System.currentTimeMillis() - lastAttempt >= retryDelay()) {
                if (!worker.isShutdown) worker.execute { reconnectNow() }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun retryDelay() = if (failures < 3) 3000L else 10000L

    fun discover(found: (List<Pair<String, String>>) -> Unit) = control.execute { found(discoverNow()) }

    /** (PC name, address) for every Redux receiver that answers a broadcast on this network. */
    private fun discoverNow(): List<Pair<String, String>> {
        val hosts = linkedMapOf<String, String>()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 450
                val request = "REDUX_DISCOVER_V1".toByteArray()
                s.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), 19879))
                val end = System.currentTimeMillis() + 1600
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(ByteArray(2048), 2048)
                    try {
                        s.receive(p)
                        val info = JSONObject(String(p.data, 0, p.length))
                        if (info.optString("service") == "redux-v1") hosts[p.address.hostAddress!!] = info.getString("name")
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            }
        }
        return hosts.map { it.value to it.key }
    }

    /** Pair with the PC's current PIN; the reply's token makes later reconnects silent. */
    fun pair(address: String, pin: String) = worker.execute {
        runCatching {
            require(address.matches(Regex("[a-zA-Z0-9.\\-]+"))) { "Enter the PC's IPv4 address or hostname." }
            require(pin.matches(Regex("[0-9]{6}"))) { "Enter the six-digit PIN shown by Redux Receiver." }
            closeSession()
            host = address
            lastAttempt = System.currentTimeMillis()
            changed("Pairing with $host…")
            // Pairing trusts this address's certificate on first use (the PIN proves the PC);
            // from then on that certificate, not the address, identifies the PC.
            val reply = request("/pair", JSONObject().put("pin", pin).put("name", android.os.Build.MODEL).toString(), false, address, expect = null)
            val minted = unhex(reply.optString("token"))
            require(minted.size == 32) { "Receiver returned no reconnect token; update Redux Receiver." }
            token = minted
            pairedCert = lastFingerprint
            prefs.edit().putString("host", address).putString("token", hex(minted)).putString("paired_cert", pairedCert).apply()
            open(reply)
            wantConnected = true
        }.onFailure { fail(it) }
    }

    /** Resume with the saved token, now or on the next retry. */
    fun connect() = worker.execute { wantConnected = true; failures = 0; reconnectNow() }

    private fun reconnectNow() {
        val saved = token ?: return
        if (connected) return
        lastAttempt = System.currentTimeMillis()
        runCatching {
            changed(if (failures == 0) "Connecting to ${pcName.ifEmpty { host }}…" else "Waiting for ${pcName.ifEmpty { host }}… retrying")
            val body = JSONObject().put("token", hex(saved)).put("name", android.os.Build.MODEL).toString()
            val reply = try {
                request("/reconnect", body, false)
            } catch (error: Exception) {
                // The saved address did not answer twice running: the PC may have a new address.
                if (error is PairingRevoked || pairedCert == null || failures < 1) throw error
                relocate(body) ?: throw error
            }
            open(reply)
            failures = 0
        }.onFailure { error ->
            failures++
            if (error is PairingRevoked) {
                wantConnected = false
                changed("The PC no longer knows this phone. Pair again with its PIN.")
            } else changed("Waiting for ${pcName.ifEmpty { host }} · ${error.message ?: "not reachable"}")
        }
    }

    /** Search the network for the paired PC and reconnect to it at its new address, without a PIN. */
    private fun relocate(body: String): JSONObject? {
        changed("Looking for ${pcName.ifEmpty { "the PC" }} on this network…")
        val found = PcLocator.relocate(host, discoverNow().map { it.second }) { address ->
            // A PC whose certificate is not the paired one fails the TLS check before the token is sent.
            try { request("/reconnect", body, false, address) }
            catch (revoked: PairingRevoked) { throw revoked }  // the paired PC answered: it forgot this phone
            catch (_: Exception) { null }
        } ?: return null
        host = found.first
        prefs.edit().putString("host", host).apply()
        return found.second
    }

    private fun open(reply: JSONObject) {
        session = unhex(reply.getString("session")); secret = unhex(reply.getString("key"))
        require(session.size == 16 && secret.size == 32) { "Receiver returned an invalid session." }
        sequence = 0
        target = InetSocketAddress(InetAddress.getByName(host), reply.getInt("udpPort"))
        socket = DatagramSocket()
        pcName = reply.optString("pc", pcName)
        prefs.edit().putString("pc", pcName).apply()
        mouseProtocol = reply.optBoolean("mouseProtocol", false)
        mouseReady = reply.optBoolean("mouseReady", false)
        connected = true
        checkStatus()
    }

    private fun send() {
        if (!connected) return
        // Lost focus or a page transition sends neutral, never the last held state. An older
        // receiver takes the 17-byte report only, so pending mouse motion is dropped for it.
        val report = synchronized(state) {
            if (!mouseProtocol || !enabled) state.clearMouseMotion()
            if (enabled) state.report(mouseProtocol) else ControllerState().report(mouseProtocol)
        }
        val seq = ++sequence
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put(session).putLong(seq).array()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(session, 0, 4).putLong(seq).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        val packet = header + cipher.doFinal(report)
        socket?.send(DatagramPacket(packet, packet.size, target))
    }

    private fun checkStatus(expected: ByteArray = session) {
        val status = request("/status", null, true)
        if (!connected || session !== expected) return
        outputReady = status.optBoolean("outputReady")
        mouseReady = status.optBoolean("mouseReady")
        val profile = status.optString("profile").ifEmpty { null }
        if (profile != pcProfile) { pcProfile = profile; onProfile?.invoke(profile) }
        changed(if (outputReady) "Connected to ${pcName.ifEmpty { host }} · one Redux gamepad${if (mouseReady) " + mouse" else ""} · ${status.optLong("packets")} frames" else "Connected · ${status.optString("error", "controller output unavailable")}")
    }

    private class PairingRevoked(message: String) : Exception(message)

    // Adapted from Dish's TofuTrustManager: pin the first LAN certificate and reject changes.
    @Suppress("CustomX509TrustManager")
    // With [expect] (the paired PC's fingerprint) only that certificate is accepted, at any address;
    // without it the address's first certificate is pinned, as for a new pairing.
    private fun request(path: String, body: String?, authenticated: Boolean, address: String = host, expect: String? = pairedCert): JSONObject {
        var accepted: ByteArray? = null
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) { throw CertificateException("Client certificate refused") }
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, auth: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("Missing receiver certificate")
                cert.checkValidity()
                val fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
                if (expect != null) {
                    if (fingerprint != expect) throw CertificateException(if (address == host) "PC certificate changed. Verify the PC before resetting trust." else "Not the paired PC")
                } else {
                    val key = "cert:$address"
                    val previous = prefs.getString(key, null)
                    if (previous != null && previous != fingerprint) throw CertificateException("PC certificate changed. Verify the PC before resetting trust.")
                    if (previous == null) prefs.edit().putString(key, fingerprint).commit()
                }
                lastFingerprint = fingerprint
                accepted = cert.encoded
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val c = URL("https://$address:19443$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = tls.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, ssl ->
            val actual = ssl.peerCertificates.firstOrNull()?.encoded
            accepted != null && actual != null && MessageDigest.isEqual(accepted, actual)
        }
        c.connectTimeout = 1500; c.readTimeout = 2500
        c.requestMethod = if (body == null) "GET" else "POST"
        c.setRequestProperty("Content-Type", "application/json")
        if (authenticated) { c.setRequestProperty("X-Redux-Session", hex(session)); c.setRequestProperty("Authorization", "Bearer ${hex(secret)}") }
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code == 403 && path == "/reconnect") throw PairingRevoked(json.optString("error", "Pairing revoked"))
            check(code in 200..299) { json.optString("error", "Receiver returned $code") }
            return json
        } finally { c.disconnect() }
    }

    private fun fail(error: Throwable) { connected = false; outputReady = false; mouseReady = false; mouseProtocol = false; socket?.close(); socket = null; changed(error.message ?: "Connection failed") }
    /** Explicit disconnect: release everything and stop reconnecting until Connect or a new pairing. */
    fun disconnect() = worker.execute { wantConnected = false; closeSession(); changed(if (paired) "Disconnected · tap Connect to resume" else "Disconnected") }
    private fun closeSession() {
        synchronized(state) { state.release() }
        if (connected) runCatching { request("/disconnect", "{}", true) }
        connected = false; outputReady = false; mouseReady = false; mouseProtocol = false; socket?.close(); socket = null; pcProfile = null
    }
    /** Forget the PC's certificate and the reconnect token; the next connection needs a PIN. */
    fun forgetTrust(address: String) {
        prefs.edit().remove("cert:$address").remove("token").remove("pc").remove("paired_cert").apply()
        token = null; wantConnected = false; pcName = ""; pairedCert = null
    }
    fun close() { control.shutdownNow(); worker.execute { closeSession() }; worker.shutdown() }
    companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun unhex(value: String): ByteArray = if (value.length % 2 != 0 || !value.matches(Regex("[0-9a-fA-F]*"))) ByteArray(0) else value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
