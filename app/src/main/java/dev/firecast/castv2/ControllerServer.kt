package dev.firecast.castv2

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

// Combined HTTPS + WSS server on port 8443.
// HTTP requests → serve controller.html (getDisplayMedia works because HTTPS = secure context).
// WebSocket upgrade → signaling relay with the receiver (which connects via WS on 8081).
//
// Using the same port for HTTPS and WSS means Chrome's one-time cert exception
// (clicking "Proceed anyway") covers both the page load and the WebSocket connection.
class ControllerServer(
    private val port: Int = 8443,
    private val certFile: java.io.File? = null,
    private val keyFile: java.io.File? = null,
) {

    private var running = false

    // Called when a signaling message arrives from the controller (Chrome).
    var onMessage: ((String) -> Unit)? = null
    // Called when controller WS connects/disconnects (for "ready" orchestration).
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var wsOut: OutputStream? = null
    private val lock = Any()

    fun sendToController(message: String) {
        val out = wsOut ?: return
        try { synchronized(lock) { writeFrame(out, message.toByteArray(Charsets.UTF_8)) } }
        catch (e: Exception) { Log.w(TAG, "Send failed: ${e.message}") }
    }

    fun start() {
        running = true
        thread(name = "ControllerServer") {
            try {
                val ss = CertUtils.createHttpsServerSocket(port, certFile, keyFile)
                Log.i(TAG, "Protocols: ${ss.enabledProtocols.joinToString()}")
                Log.i(TAG, "Controller server (HTTPS+WSS) on :$port")
                while (running) {
                    try {
                        val socket = ss.accept() as SSLSocket
                        Log.i(TAG, "TCP accepted from ${socket.inetAddress.hostAddress}")
                        thread {
                            try {
                                socket.startHandshake()
                                Log.i(TAG, "TLS handshake OK")
                            } catch (e: Exception) {
                                Log.e(TAG, "TLS handshake FAILED: ${e.message}")
                                socket.close(); return@thread
                            }
                            handleConnection(socket)
                        }
                    } catch (e: Exception) { if (running) Log.e(TAG, "accept: ${e.message}") }
                }
                ss.close()
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }
    }

    fun stop() { running = false }

    private fun handleConnection(socket: SSLSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val firstLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                line = reader.readLine()
            }

            if (headers["upgrade"]?.lowercase() == "websocket") {
                handleWebSocket(socket, headers)
            } else {
                handleHttp(socket, firstLine)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun handleHttp(socket: SSLSocket, requestLine: String) {
        val path = requestLine.split(" ").getOrElse(1) { "/" }
        Log.d(TAG, "HTTPS $path")
        val body = CONTROLLER_HTML.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.outputStream.write(header.toByteArray())
        socket.outputStream.write(body)
        socket.outputStream.flush()
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun handleWebSocket(socket: SSLSocket, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        val out = socket.outputStream
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${computeAccept(key)}\r\n\r\n"
        out.write(response.toByteArray()); out.flush()

        synchronized(lock) { wsOut = out }
        Log.i(TAG, "Controller WS connected")
        onConnected?.invoke()

        try {
            val input = socket.inputStream
            while (!socket.isClosed) {
                val payload = readFrame(input) ?: break
                val text = String(payload, Charsets.UTF_8)
                if (!text.contains("\"hello\"")) onMessage?.invoke(text)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Controller WS disconnected: ${e.message}")
        } finally {
            synchronized(lock) { wsOut = null }
            onDisconnected?.invoke()
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
        private const val TAG      = "ControllerServer"
        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        val CONTROLLER_HTML = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FireLinkTab</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0a0a0a;color:#f1f1f1;
     font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;
     display:flex;flex-direction:column;align-items:center;justify-content:center;
     min-height:100vh;gap:0;padding:24px}
h1{font-size:clamp(2.4rem,6vw,3.8rem);font-weight:800;letter-spacing:-.5px;
   background:linear-gradient(90deg,#ff2d2d 0%,#ff6b00 50%,#ff9500 100%);
   -webkit-background-clip:text;-webkit-text-fill-color:transparent;
   background-clip:text;line-height:1.1}
.tagline{font-size:.9rem;color:#444;letter-spacing:.12em;text-transform:uppercase;
         margin-top:6px;margin-bottom:40px;font-weight:400}
.card{background:#161616;border:1px solid #2a2a2a;border-radius:14px;
      padding:28px 32px;max-width:440px;width:100%;text-align:center}
.warn{border-radius:8px;padding:14px 16px;font-size:.85rem;
      margin-bottom:18px;text-align:left;line-height:1.65}
.warn-cert{background:#1e1400;border:1px solid #6b4a00;color:#cc8800}
.warn-mobile{background:#150d1f;border:1px solid #6a1fb0;color:#a76dd6}
.warn code{font-size:.8rem;opacity:.85}
button{background:linear-gradient(135deg,#e53935,#d84315);color:#fff;border:none;
       padding:14px 28px;border-radius:8px;font-size:1rem;font-weight:600;
       cursor:pointer;width:100%;transition:.15s;letter-spacing:.01em}
button:hover:not(:disabled){filter:brightness(1.12)}
button:disabled{background:#222;color:#555;cursor:not-allowed}
#status{margin-top:14px;font-size:.85rem;color:#555;min-height:18px}
.dot{display:inline-block;width:7px;height:7px;border-radius:50%;margin-right:5px;vertical-align:middle}
.green{background:#4caf50}.red{background:#f44336}.yellow{background:#ff9800}
</style>
</head>
<body>
<h1>FireLinkTab</h1>
<p class="tagline">Tab casting para Fire TV</p>
<div class="card">
  <div class="warn warn-cert" id="warn-cert" style="display:none">
    <b>&#9888; Certificado no aceptado</b><br><br>
    Haz clic en <b>Avanzado &rarr; Acceder al sitio</b> en el aviso de seguridad
    del navegador y luego recarga esta página.
  </div>
  <div class="warn warn-mobile" id="warn-mobile" style="display:none">
    <b>&#128245; Navegador móvil detectado</b><br><br>
    <code>getDisplayMedia</code> no está disponible en móviles.<br>
    Abre esta URL desde <b>Chrome, Brave, Edge, Firefox o Safari en tu computadora</b>.
  </div>
  <button id="btn" onclick="startCast()">&#9654; Compartir pantalla</button>
  <p id="status"></p>
</div>

<script>
const isMobile = /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
if (isMobile) {
  document.getElementById('warn-mobile').style.display = 'block';
  document.getElementById('btn').disabled = true;
  document.getElementById('status').textContent = 'Usa Chrome o Brave en tu computadora.';
} else if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
  document.getElementById('warn-cert').style.display = 'block';
  document.getElementById('btn').disabled = true;
  document.getElementById('status').textContent = 'Acepta el certificado y recarga la página.';
}

let pc, ws, stream;

async function startCast() {
  const btn = document.getElementById('btn');
  const status = document.getElementById('status');
  btn.disabled = true;

  try {
    status.innerHTML = '<span class="dot yellow"></span>Seleccionando pantalla…';
    stream = await navigator.mediaDevices.getDisplayMedia({video:true, audio:true});

    status.innerHTML = '<span class="dot yellow"></span>Conectando con Fire TV…';
    ws = new WebSocket('wss://' + location.host);
    pc = new RTCPeerConnection();

    stream.getTracks().forEach(t => pc.addTrack(t, stream));

    pc.onicecandidate = e => {
      if (e.candidate && ws.readyState === 1)
        ws.send(JSON.stringify({type:'ice', from:'controller', candidate:e.candidate}));
    };

    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'connected') {
        status.innerHTML = '<span class="dot green"></span>Transmitiendo';
        btn.textContent = '⏹ Detener';
        btn.disabled = false;
        btn.onclick = stopCast;
      } else if (['failed','disconnected','closed'].includes(pc.connectionState)) {
        status.innerHTML = '<span class="dot red"></span>Desconectado (' + pc.connectionState + ')';
        resetBtn();
      }
    };

    ws.onopen = () => {
      status.innerHTML = '<span class="dot yellow"></span>Esperando receiver…';
    };

    ws.onmessage = async e => {
      const msg = JSON.parse(e.data);
      if (msg.type === 'ready') {
        status.innerHTML = '<span class="dot yellow"></span>Negociando…';
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        ws.send(JSON.stringify({type:'offer', from:'controller', sdp:pc.localDescription}));
      } else if (msg.type === 'answer') {
        await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
      } else if (msg.type === 'ice' && msg.from === 'receiver') {
        await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
      }
    };

    ws.onerror = () => { status.innerHTML = '<span class="dot red"></span>Error WebSocket'; resetBtn(); };
    stream.getTracks()[0].onended = stopCast;

  } catch(e) {
    status.innerHTML = '<span class="dot red"></span>' + e.message;
    resetBtn();
  }
}

function stopCast() {
  stream?.getTracks().forEach(t => t.stop());
  pc?.close(); ws?.close();
  resetBtn();
  document.getElementById('status').innerHTML = '<span class="dot red"></span>Transmisión terminada';
}

function resetBtn() {
  const btn = document.getElementById('btn');
  btn.textContent = '▶ Compartir pantalla';
  btn.onclick = startCast;
  btn.disabled = false;
}
</script>
</body>
</html>"""
    }
}
