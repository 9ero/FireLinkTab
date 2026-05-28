package dev.firecast.castv2

import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.Signature
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

// Implements the Cast v2 protocol (CASTV2) over TLS on port 8009.
//
// Protocol flow:
//   1. Chrome connects via TLS
//   2. Chrome sends AUTH_CHALLENGE (binary proto in deviceauth namespace)
//   3. We respond with AUTH_RESPONSE (signed with our device certificate)
//   4. Chrome validates our certificate against Google's Cast CA  ← THE WALL
//      Without a Google-signed device certificate, Chrome drops the connection here.
//   5. If auth passes: heartbeat + connection + receiver namespaces are active
//
// Status: Steps 1–3 are fully implemented. Step 4 requires a Google-issued cert.
class CastV2Server(private val port: Int = 9009) {

    private var running = false
    var onStatusUpdate: ((String) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null

    fun start() {
        running = true
        thread(name = "CastV2-accept") {
            try {
                val serverSocket = CertUtils.createServerSocket(port)
                onStatusUpdate?.invoke("TLS escuchando en :$port")
                Log.i(TAG, "Cast v2 server listening on port $port")

                while (running) {
                    try {
                        val socket = serverSocket.accept() as SSLSocket
                        Log.i(TAG, "Client connected: ${socket.inetAddress.hostAddress}")
                        onClientConnected?.invoke()
                        thread(name = "CastV2-client") { handleClient(socket) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
                serverSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
                onStatusUpdate?.invoke("Error: ${e.message}")
            }
        }
    }

    fun stop() { running = false }

    private fun handleClient(socket: SSLSocket) {
        try {
            socket.startHandshake()
            Log.i(TAG, "TLS handshake OK")
            onStatusUpdate?.invoke("Chrome conectado — autenticando…")

            val input  = socket.inputStream
            val output = socket.outputStream

            while (!socket.isClosed) {
                val msg = readFrame(input) ?: break
                Log.d(TAG, "← ns=${msg.namespace} src=${msg.sourceId} dst=${msg.destinationId} type=${msg.payloadType}")
                dispatch(msg, output)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client disconnected: ${e.message}")
        } finally {
            runCatching { socket.close() }
            onStatusUpdate?.invoke("TLS escuchando en :$port")
        }
    }

    private fun dispatch(msg: CastMessage, out: OutputStream) {
        when (msg.namespace) {
            NS_AUTH       -> handleAuth(msg, out)
            NS_HEARTBEAT  -> handleHeartbeat(msg, out)
            NS_CONNECTION -> handleConnection(msg, out)
            NS_RECEIVER   -> handleReceiver(msg, out)
            else          -> Log.d(TAG, "Unknown namespace: ${msg.namespace}")
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    private fun handleAuth(msg: CastMessage, out: OutputStream) {
        val authMsg = DeviceAuthMessage.decode(msg.payloadBinary ?: return)
        val challenge = authMsg.challenge ?: return
        Log.i(TAG, "AUTH_CHALLENGE received (senderNonce=${challenge.senderNonce?.size ?: 0} bytes)")

        // Build AuthResponse using our self-signed certificate.
        // Chrome will validate clientAuthCertificate against Google's Cast CA and
        // reject it — but we implement the full response so we can observe the exact
        // error and explore alternatives (e.g., extracting a real device cert).
        try {
            val certDer    = CertUtils.certificate.encoded
            val privateKey = CertUtils.privateKey

            // Sign: if sender_nonce present use it, else sign the cert itself
            val dataToSign = challenge.senderNonce ?: certDer
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey); update(dataToSign); sign()
            }

            val authResponse = AuthResponse(
                signature              = sig,
                clientAuthCertificate  = certDer,
                signatureAlgorithm     = 2,    // RSASSA_PKCS1v15_SHA256
                senderNonce            = challenge.senderNonce,
                hashAlgorithm          = 1,    // SHA256
            )

            val responseMsg = DeviceAuthMessage(response = authResponse)
            sendMessage(out, CastMessage(
                sourceId      = "receiver-0",
                destinationId = msg.sourceId,
                namespace     = NS_AUTH,
                payloadType   = 1,
                payloadBinary = responseMsg.encode(),
            ))
            Log.i(TAG, "AUTH_RESPONSE sent (cert ${certDer.size} bytes, sig ${sig.size} bytes)")
            onStatusUpdate?.invoke("Auth enviada — esperando validación de Google…")

        } catch (e: Exception) {
            Log.e(TAG, "Auth error: ${e.message}")
            // Send AUTH_ERROR so Chrome at least knows we received the challenge
            val errMsg = DeviceAuthMessage(error = AuthError(errorType = 0))
            sendMessage(out, CastMessage(
                sourceId      = "receiver-0",
                destinationId = msg.sourceId,
                namespace     = NS_AUTH,
                payloadType   = 1,
                payloadBinary = errMsg.encode(),
            ))
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private fun handleHeartbeat(msg: CastMessage, out: OutputStream) {
        val payload = msg.payloadUtf8 ?: return
        if (JSONObject(payload).optString("type") == "PING") {
            sendMessage(out, msg.copy(
                sourceId      = msg.destinationId,
                destinationId = msg.sourceId,
                payloadUtf8   = """{"type":"PONG"}""",
            ))
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun handleConnection(msg: CastMessage, out: OutputStream) {
        val type = msg.payloadUtf8?.let { JSONObject(it).optString("type") } ?: return
        if (type == "CONNECT") {
            Log.i(TAG, "CONNECT from ${msg.sourceId}")
            onStatusUpdate?.invoke("Canal abierto con Chrome")
            sendMessage(out, msg.copy(
                sourceId      = msg.destinationId,
                destinationId = msg.sourceId,
                payloadUtf8   = """{"type":"CONNECTED","connectedCount":1}""",
            ))
        }
    }

    // ── Receiver ──────────────────────────────────────────────────────────────

    private fun handleReceiver(msg: CastMessage, out: OutputStream) {
        val json = msg.payloadUtf8?.let { JSONObject(it) } ?: return
        val type = json.optString("type")
        val reqId = json.optInt("requestId", 0)
        Log.i(TAG, "Receiver message type=$type")

        when (type) {
            "GET_STATUS" -> sendMessage(out, msg.copy(
                sourceId      = msg.destinationId,
                destinationId = msg.sourceId,
                payloadUtf8   = buildReceiverStatus(reqId),
            ))
            "LAUNCH"     -> {
                val appId = json.optString("appId")
                Log.i(TAG, "LAUNCH appId=$appId")
                onStatusUpdate?.invoke("Lanzando app: $appId")
                sendMessage(out, msg.copy(
                    sourceId      = msg.destinationId,
                    destinationId = msg.sourceId,
                    payloadUtf8   = buildReceiverStatus(reqId, appId),
                ))
            }
        }
    }

    private fun buildReceiverStatus(requestId: Int, runningAppId: String? = null): String {
        val apps = if (runningAppId != null) """[{
            "appId":"$runningAppId","displayName":"$runningAppId",
            "namespaces":[{"name":"urn:x-cast:com.google.cast.media"}],
            "sessionId":"${java.util.UUID.randomUUID()}","statusText":"","transportId":"transport-0"
        }]""" else "[]"
        return """{"requestId":$requestId,"type":"RECEIVER_STATUS","status":{"applications":$apps,"isActiveInput":true,"isStandBy":false,"volume":{"controlType":"attenuation","level":1.0,"muted":false,"stepInterval":0.05}}}"""
    }

    // ── I/O helpers ───────────────────────────────────────────────────────────

    private fun readFrame(input: InputStream): CastMessage? {
        val lenBuf = ByteArray(4)
        if (input.readFully(lenBuf) < 4) return null
        val len = ByteBuffer.wrap(lenBuf).int
        if (len <= 0 || len > MAX_FRAME) return null
        val msgBuf = ByteArray(len)
        if (input.readFully(msgBuf) < len) return null
        return runCatching { CastMessage.decode(msgBuf) }.getOrNull()
    }

    private fun sendMessage(out: OutputStream, msg: CastMessage) {
        synchronized(out) {
            runCatching { out.write(msg.encode().toFrame()); out.flush() }
        }
    }

    private fun InputStream.readFully(buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = read(buf, read, buf.size - read)
            if (n < 0) return read
            read += n
        }
        return read
    }

    companion object {
        private const val TAG       = "CastV2Server"
        private const val NS_AUTH       = "urn:x-cast:com.google.cast.tp.deviceauth"
        private const val NS_HEARTBEAT  = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_RECEIVER   = "urn:x-cast:com.google.cast.receiver"
        private const val MAX_FRAME = 65536
    }
}
