package dev.firecast.castv2

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

// Plain WS (no TLS) signaling server for the receiver (WebView on Fire TV).
// The receiver connects here via ws://ip:8081 — allowed by the cleartext network security config.
// Messages are relayed via MainActivity to/from the ControllerServer (WSS on 8443).
class SignalingServer(private val port: Int = 8081) {

    private var running = false

    var onMessage: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var wsOut: OutputStream? = null
    private val lock = Any()

    fun sendToReceiver(message: String) {
        val out = wsOut ?: return
        try { synchronized(lock) { writeFrame(out, message.toByteArray(Charsets.UTF_8)) } }
        catch (e: Exception) { Log.w(TAG, "Send failed: ${e.message}") }
    }

    fun start() {
        running = true
        thread(name = "SignalingServer") {
            try {
                val ss = ServerSocket(port)
                Log.i(TAG, "Signaling server (WS) on :$port")
                while (running) {
                    try { val s = ss.accept(); thread { handleClient(s) } }
                    catch (e: Exception) { if (running) Log.e(TAG, "accept: ${e.message}") }
                }
                ss.close()
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }
    }

    fun stop() { running = false }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.inputStream
            val out   = socket.outputStream
            val lines = mutableListOf<String>()
            val headers = mutableMapOf<String, String>()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                lines.add(line)
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                line = reader.readLine()
            }

            val key = headers["sec-websocket-key"] ?: return
            val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Accept: ${computeAccept(key)}\r\n" +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\n\r\n"
            out.write(response.toByteArray()); out.flush()

            synchronized(lock) { wsOut = out }
            Log.i(TAG, "Receiver WS connected")
            onConnected?.invoke()

            while (!socket.isClosed) {
                val payload = readFrame(input) ?: break
                val text = String(payload, Charsets.UTF_8)
                if (!text.contains("\"hello\"")) onMessage?.invoke(text)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Receiver disconnected: ${e.message}")
        } finally {
            synchronized(lock) { wsOut = null }
            onDisconnected?.invoke()
            runCatching { socket.close() }
        }
    }

    // ── Frame I/O ─────────────────────────────────────────────────────────────

    private fun readFrame(input: InputStream): ByteArray? {
        val b0 = input.read(); if (b0 < 0) return null
        val b1 = input.read(); if (b1 < 0) return null
        if ((b0 and 0x0F) == 8) return null
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(len.toInt())
        readFully(input, payload)
        if (mask != null) payload.indices.forEach { i -> payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        return payload
    }

    private fun writeFrame(out: OutputStream, data: ByteArray) {
        out.write(0x81)
        when {
            data.size < 126   -> out.write(data.size)
            data.size < 65536 -> { out.write(126); out.write(data.size shr 8); out.write(data.size and 0xFF) }
        }
        out.write(data); out.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
    }

    private fun computeAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$key$WS_MAGIC".toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        private const val TAG      = "SignalingServer"
        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
