package dev.firecast.castv2

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import android.util.Base64
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
    private val friendlyName: String = "Fire TV",
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
        val out = socket.outputStream

        // Script downloads served from HTTPS to avoid mixed-content blocking
        if (path.startsWith("/script/")) {
            val ip = CertUtils.localIp()
            val os = path.removePrefix("/script/")
            val (script, fname, mime) = when (os) {
                "win"   -> Triple(WebServer.winScript(ip), "instalar-firelink-ca.ps1", "text/plain")
                "linux" -> Triple(WebServer.shScript(ip),  "instalar-firelink-ca.sh",  "text/x-sh")
                "mac"   -> Triple(WebServer.macScript(ip), "instalar-firelink-ca.sh",  "text/x-sh")
                else    -> { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return }
            }
            val bytes = script.toByteArray(Charsets.UTF_8)
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\n" +
                "Content-Disposition: attachment; filename=\"$fname\"\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes); out.flush()
            return
        }

        val body = CONTROLLER_HTML.replace("{{DEVICE_NAME}}", friendlyName).toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
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
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    companion object {
        private const val TAG      = "ControllerServer"
        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        val CONTROLLER_HTML = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FireLink — {{DEVICE_NAME}}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0a0a0a;color:#f1f1f1;
     font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;
     display:flex;flex-direction:column;align-items:center;
     min-height:100vh;padding:32px 24px;gap:0}
h1{font-size:clamp(2rem,6vw,3.2rem);font-weight:800;letter-spacing:-.5px;
   background:linear-gradient(90deg,#ff2d2d 0%,#ff6b00 50%,#ff9500 100%);
   -webkit-background-clip:text;-webkit-text-fill-color:transparent;
   background-clip:text;line-height:1.1;margin-top:12px}
.tagline{font-size:.85rem;color:#444;letter-spacing:.12em;text-transform:uppercase;
         margin-top:6px;margin-bottom:8px}
.device-tag{font-size:1rem;color:#ff8c00;font-weight:600;letter-spacing:.04em;
            margin-bottom:28px}
.card{background:#161616;border:1px solid #252525;border-radius:14px;
      padding:24px 28px;max-width:480px;width:100%;text-align:center;margin-bottom:16px}
/* ── Cast card ── */
.cast-btn{background:linear-gradient(135deg,#e53935,#d84315);color:#fff;border:none;
       padding:14px 28px;border-radius:8px;font-size:1rem;font-weight:600;
       cursor:pointer;width:100%;transition:.15s}
.cast-btn:hover:not(:disabled){filter:brightness(1.12)}
.cast-btn:disabled{background:#222;color:#555;cursor:not-allowed}
#status{margin-top:12px;font-size:.85rem;color:#555;min-height:18px}
.dot{display:inline-block;width:7px;height:7px;border-radius:50%;margin-right:5px;vertical-align:middle}
.green{background:#4caf50}.red{background:#f44336}.yellow{background:#ff9800}
/* ── Setup card ── */
.setup-title{font-size:.8rem;font-weight:600;letter-spacing:.1em;text-transform:uppercase;
             color:#555;margin-bottom:16px;text-align:left}
.step{display:flex;gap:14px;align-items:flex-start;margin-bottom:18px;text-align:left}
.step-num{background:#ff4500;color:#fff;border-radius:50%;width:24px;height:24px;
          display:flex;align-items:center;justify-content:center;
          font-size:.75rem;font-weight:700;flex-shrink:0;margin-top:1px}
.step-body{flex:1;font-size:.88rem;line-height:1.6;color:#aaa}
.step-body b{color:#ddd}
.dl-btn{display:inline-block;margin-top:8px;padding:8px 16px;border-radius:6px;
        background:#1e1e1e;border:1px solid #333;color:#ff8c00;font-size:.82rem;
        font-weight:600;text-decoration:none;cursor:pointer}
.dl-btn:hover{background:#2a2a2a}
.os-row{display:flex;gap:8px;margin-top:8px;flex-wrap:wrap}
.os-btn{flex:1;min-width:90px;padding:9px 8px;border-radius:6px;border:1px solid #333;
        background:#1a1a1a;color:#ccc;font-size:.82rem;font-weight:600;
        cursor:pointer;transition:.15s}
.os-btn:hover{background:#252525;border-color:#555;color:#fff}
/* ── Mobile / collapsible ── */
.warn-mobile{background:#150d1f;border:1px solid #6a1fb0;color:#a76dd6;
             border-radius:8px;padding:14px;font-size:.85rem;line-height:1.65;
             margin-bottom:0;text-align:left}
details{width:100%;max-width:480px}
summary{font-size:.8rem;color:#333;cursor:pointer;padding:6px 0;list-style:none;
        text-align:center}
summary::-webkit-details-marker{display:none}
summary:hover{color:#555}
</style>
</head>
<body>
<h1>FireLink</h1>
<p class="tagline">Transmite cualquier app al Fire TV</p>
<p class="device-tag">&#128250;&ensp;{{DEVICE_NAME}}</p>

<!-- ── Mobile warning ── -->
<div class="warn-mobile" id="warn-mobile" style="display:none;max-width:480px;width:100%;margin-bottom:16px">
  <b>&#128245; Navegador móvil no compatible</b><br><br>
  <code>getDisplayMedia</code> no está disponible en móviles.<br>
  Abre esta URL desde <b>Chrome, Brave, Edge, Firefox o Safari en tu computadora</b>.
</div>

<!-- ── Cast card ── -->
<div class="card" id="cast-card" style="display:none">
  <button class="cast-btn" id="btn" onclick="startCast()">&#9654; Compartir pantalla</button>
  <button class="cast-btn" id="audio-btn" onclick="addAudio()"
    style="display:none;margin-top:8px;background:linear-gradient(135deg,#1565c0,#0d47a1);font-size:.88rem">
    &#127925; Agregar audio del sistema
  </button>
  <button class="cast-btn" id="mic-btn" onclick="toggleMic()"
    style="display:none;margin-top:8px;background:#1a1a1a;border:1px solid #444;color:#888;font-size:.88rem">
    &#127908; Micrófono: OFF
  </button>
  <p id="status"></p>
  <div style="margin-top:14px;background:#241a00;border:1px solid #7a5200;border-radius:8px;
              padding:12px 14px;text-align:left">
    <p style="font-size:.82rem;color:#ffb300;font-weight:700;margin-bottom:10px;letter-spacing:.02em">&#128161; Modo recomendado</p>
    <p style="font-size:.82rem;color:#e8a820;margin-bottom:6px">
      &#128438;&nbsp; Elige <b style="color:#ffc840">Una ventana</b> en el selector — funciona con cualquier app del ordenador
    </p>
    <p style="font-size:.82rem;color:#e8a820;margin-bottom:6px">
      &#128266;&nbsp; Activa <b style="color:#ffc840">Compartir audio del sistema</b> en el mismo selector
    </p>
    <p style="font-size:.78rem;color:#8a6820;margin-top:2px">
      &#10006;&nbsp; Evita &ldquo;Una pestaña&rdquo; — más lag y peor calidad
    </p>
  </div>
  <!-- Windows audio guide — shown only when no loopback device is detected -->
  <div id="win-audio-guide" style="display:none;margin-top:12px;background:#0d1520;border:1px solid #1a3a5c;border-radius:8px;padding:14px;text-align:left">
    <p style="font-size:.82rem;font-weight:600;color:#5b8fc9;margin-bottom:10px">&#128266; Cómo agregar audio en Windows</p>
    <div style="background:#081810;border:1px solid #1a4a2c;border-radius:6px;padding:10px 12px;margin-bottom:10px">
      <p style="font-size:.8rem;color:#4a9a6a;font-weight:600;margin-bottom:6px">&#9650; Opción más sencilla</p>
      <p style="font-size:.78rem;color:#3a7a54;line-height:1.6;margin-bottom:8px">Reinicia la transmisión y en el selector del navegador activa el checkbox <b style="color:#5ab87a">Compartir audio del sistema</b> antes de confirmar.</p>
      <button onclick="stopCast()" style="background:#1a4a2c;border:1px solid #2a6a3c;color:#5ab87a;border-radius:6px;padding:7px 14px;font-size:.82rem;font-weight:600;cursor:pointer;width:100%">&#8635; Reiniciar transmisión</button>
    </div>
    <details style="margin-top:8px">
      <summary style="font-size:.79rem;color:#446688;cursor:pointer;padding:4px 0;list-style:none">&#9654; Sin interrumpir — detectar Stereo Mix o cable virtual</summary>
      <div style="margin-top:8px">
        <button id="detect-btn" onclick="detectWinAudio()" style="background:#1a3a5c;border:1px solid #2a5a8c;color:#7ab4e8;border-radius:6px;padding:8px 14px;font-size:.82rem;font-weight:600;cursor:pointer;width:100%">&#128269; Detectar Stereo Mix / cable virtual</button>
        <p style="font-size:.75rem;color:#334455;margin-top:6px">Necesita permiso de micrófono para detectar Stereo Mix u otros dispositivos de loopback.</p>
        <details style="margin-top:10px">
          <summary style="font-size:.79rem;color:#446688;cursor:pointer;padding:4px 0;list-style:none">&#9654; Activar Stereo Mix en Windows</summary>
          <div style="font-size:.78rem;color:#556677;line-height:1.85;margin-top:6px;padding:8px;background:#090e18;border-radius:6px">
            1. Clic derecho en &#128266; (barra de tareas) &#8594; <b style="color:#7aabcc">Sonidos</b><br>
            2. Pestaña <b style="color:#7aabcc">Grabar</b> &#8594; clic derecho en espacio vacío &#8594; <b style="color:#7aabcc">Mostrar dispositivos deshabilitados</b><br>
            3. Clic derecho en <b style="color:#7aabcc">Mezcla estéreo</b> &#8594; <b style="color:#7aabcc">Habilitar</b><br>
            4. Haz clic en <b style="color:#7aabcc">Detectar</b> arriba
          </div>
        </details>
        <details style="margin-top:6px">
          <summary style="font-size:.79rem;color:#446688;cursor:pointer;padding:4px 0;list-style:none">&#9654; VB-Audio Virtual Cable (alternativa gratuita)</summary>
          <div style="font-size:.78rem;color:#556677;line-height:1.85;margin-top:6px;padding:8px;background:#090e18;border-radius:6px">
            1. Descarga e instala <b style="color:#7aabcc">VB-Audio Virtual Cable</b> (vb-audio.com — donationware)<br>
            2. Windows &#8594; Ajustes de sonido &#8594; salida predeterminada: <b style="color:#7aabcc">CABLE Input (VB-Audio)</b><br>
            3. Haz clic en <b style="color:#7aabcc">Detectar</b> arriba &#8212; aparecerá como &#8220;CABLE Output&#8221;
          </div>
        </details>
      </div>
    </details>
  </div>
</div>

<!-- ── Setup (collapsible, auto-expanded when cert not trusted) ── -->
<details id="setup" open style="display:none;width:100%;max-width:480px;margin-top:12px">
  <summary id="setup-summary">&#10067; ¿Quieres eliminar el aviso de certificado?</summary>
  <div class="card" style="margin-top:6px;border-radius:0 0 14px 14px;border-top:none">
    <p class="setup-title">Selecciona tu sistema — copia y ejecuta el comando</p>

    <div class="os-row" style="margin-bottom:16px">
      <button class="os-btn" id="os-win"   onclick="showCmd('win')"  >&#128377; Windows</button>
      <button class="os-btn" id="os-linux" onclick="showCmd('linux')">&#9881; Linux</button>
      <button class="os-btn" id="os-mac"   onclick="showCmd('mac')"  >&#63743; macOS</button>
    </div>

    <div id="cmd-box" style="display:none">
      <p id="cmd-hint" style="font-size:.8rem;color:#666;margin-bottom:8px;text-align:left"></p>
      <div style="position:relative">
        <code id="cmd-text" style="display:block;background:#0f0f0f;border:1px solid #2a2a2a;
              border-radius:8px;padding:12px 48px 12px 12px;font-size:.78rem;
              color:#ff8c00;white-space:pre-wrap;word-break:break-all;text-align:left;
              line-height:1.5"></code>
        <button onclick="copyCmd()" title="Copiar"
          style="position:absolute;top:8px;right:8px;background:#222;border:1px solid #333;
                 border-radius:5px;color:#aaa;cursor:pointer;padding:4px 8px;font-size:.75rem;width:auto">
          &#128203; Copiar
        </button>
      </div>
      <p id="copied-msg" style="color:#4caf50;font-size:.78rem;margin-top:6px;display:none">&#10003; Copiado</p>
    </div>
  </div>
</details>

<script>
const IP       = location.hostname;
const isMobile = /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
const canCast  = !isMobile && !!navigator.mediaDevices?.getDisplayMedia;
let micTrack = null;

if (isMobile) {
  document.getElementById('warn-mobile').style.display = 'block';
} else {
  document.getElementById('cast-card').style.display = 'block';
  const setup = document.getElementById('setup');
  setup.style.display = 'block';

  if (!canCast) {
    document.getElementById('btn').disabled = true;
    document.getElementById('status').textContent = 'Instala el certificado y recarga.';
  }

  // Open by default; remember if the user manually closes it
  if (localStorage.getItem('flt-setup-closed') !== '1') {
    setup.setAttribute('open', '');
  }
  setup.addEventListener('toggle', () => {
    localStorage.setItem('flt-setup-closed', setup.open ? '0' : '1');
  });
}

// ── OS command display ───────────────────────────────────────────────────────

const CMDS = {
  win: {
    hint: 'Pega en cualquier PowerShell — pedirá permiso de administrador automáticamente (UAC):',
    cmd:  'Start-Process powershell -Verb RunAs -Wait -ArgumentList \'-NoProfile -Command "iwr http://IP:8080/ca.crt -O ${'$'}env:TEMP\\fca.crt -UseBasicParsing; Import-Certificate ${'$'}env:TEMP\\fca.crt -CertStoreLocation Cert:\\LocalMachine\\Root; del ${'$'}env:TEMP\\fca.crt; Write-Host Listo_reinicia_el_navegador; pause"\''
  },
  linux: {
    hint: 'Abre una terminal y pega (pedirá contraseña de sudo):',
    cmd:  'curl -sf http://IP:8080/ca.crt -o /tmp/flt-ca.crt && sudo cp /tmp/flt-ca.crt /usr/local/share/ca-certificates/FireLink-CA.crt && sudo update-ca-certificates && mkdir -p ${'$'}HOME/.pki/nssdb && rm -f ${'$'}HOME/.pki/nssdb/cert8.db ${'$'}HOME/.pki/nssdb/key3.db ${'$'}HOME/.pki/nssdb/secmod.db ${'$'}HOME/.pki/nssdb/cert9.db ${'$'}HOME/.pki/nssdb/key4.db ${'$'}HOME/.pki/nssdb/pkcs11.txt && certutil -d sql:${'$'}HOME/.pki/nssdb -N --empty-password && certutil -d sql:${'$'}HOME/.pki/nssdb -A -t "CT,," -n "FireLink Local CA" -i /tmp/flt-ca.crt && rm /tmp/flt-ca.crt && echo "Listo — reinicia el navegador"'
  },
  mac: {
    hint: 'Abre Terminal y pega (pedirá contraseña):',
    cmd:  'curl -sf http://IP:8080/ca.crt -o /tmp/flt-ca.crt && sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain /tmp/flt-ca.crt && rm /tmp/flt-ca.crt && echo "Listo — reinicia el navegador"'
  }
};

function showCmd(os) {
  const entry = CMDS[os];
  const cmd   = entry.cmd.replace(/IP/g, IP);
  document.getElementById('cmd-hint').textContent = entry.hint;
  document.getElementById('cmd-text').textContent = cmd;
  document.getElementById('cmd-box').style.display = 'block';
  document.getElementById('copied-msg').style.display = 'none';
  ['win','linux','mac'].forEach(k =>
    document.getElementById('os-'+k).style.borderColor = k === os ? '#ff4500' : '');
}

function copyCmd() {
  const text = document.getElementById('cmd-text').textContent;
  navigator.clipboard.writeText(text).then(() => {
    const msg = document.getElementById('copied-msg');
    msg.style.display = 'block';
    setTimeout(() => msg.style.display = 'none', 2000);
  });
}

// ── WebRTC ───────────────────────────────────────────────────────────────────

let pc, ws, stream, _winLoopbackId = null;

async function startCast() {
  const btn    = document.getElementById('btn');
  const status = document.getElementById('status');
  btn.disabled = true;
  try {
    status.innerHTML = '<span class="dot yellow"></span>Seleccionando pantalla…';
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: {
        displaySurface: 'window',    // pre-selects "Una ventana" in Chrome picker
        frameRate:  { ideal: 30 },
        width:      { ideal: 1920 },
        height:     { ideal: 1080 }
      },
      audio: {
        echoCancellation: false,     // disable voice filters for clean system audio
        noiseSuppression: false,
        autoGainControl:  false,
        sampleRate:    48000,
        channelCount:  2
      },
      preferCurrentTab: false        // deprioritize tab sharing in picker
    });
    stream.getAudioTracks().forEach(t => { t.contentHint = 'music'; });
    stream.getVideoTracks().forEach(t => { t.contentHint = 'motion'; });

    if (stream.getAudioTracks().length === 0) {
      const surface = stream.getVideoTracks()[0]?.getSettings().displaySurface;
      const isLinux = /Linux/.test(navigator.userAgent) && !/Android/.test(navigator.userAgent);
      const isWin   = /Windows/.test(navigator.userAgent);
      if (isLinux) {
        await tryLinuxLoopback();
      } else if (isWin) {
        await tryWindowsLoopback(surface);
      }
    }

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
        const hasAudio = stream.getAudioTracks().length > 0;
        status.innerHTML = '<span class="dot green"></span>Transmitiendo' + (hasAudio ? ' con audio' : '');
        btn.textContent = '⏹ Detener'; btn.disabled = false; btn.onclick = stopCast;
      } else if (['failed','disconnected','closed'].includes(pc.connectionState)) {
        status.innerHTML = '<span class="dot red"></span>Desconectado (' + pc.connectionState + ')';
        resetBtn();
      }
    };
    ws.onopen = () => { status.innerHTML = '<span class="dot yellow"></span>Esperando receiver…'; };
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
    stream.getVideoTracks().forEach(t => { t.onended = stopCast; });
  } catch(e) {
    status.innerHTML = '<span class="dot red"></span>' + e.message;
    resetBtn();
  }
}

async function findLoopbackDevice() {
  const kws = ['monitor','stereo mix','wave out','what u hear','virtual cable','vb-audio','cable output','hi-fi cable','blackhole','loopback'];
  let devs = [];
  try { devs = await navigator.mediaDevices.enumerateDevices(); } catch(_) { return null; }
  return devs.filter(d => d.kind === 'audioinput' && d.label)
    .find(d => kws.some(kw => d.label.toLowerCase().includes(kw))) || null;
}

async function tryLinuxLoopback() {
  document.getElementById('audio-btn').style.display = 'block';
  document.getElementById('status').innerHTML =
    '<span class="dot yellow"></span>Sin audio — haz clic en el botón y selecciona <b>Monitor of Built-in Audio</b>';
}

async function tryWindowsLoopback(surface) {
  const dev = await findLoopbackDevice();
  if (dev) {
    _winLoopbackId = dev.deviceId;
    const name = dev.label.split('(')[0].trim();
    document.getElementById('win-audio-guide').style.display = 'none';
    document.getElementById('status').innerHTML =
      '<span class="dot yellow"></span>Sin audio — encontrado <b>' + name + '</b> → haz clic en el botón';
  } else {
    const hint = surface === 'detect'
      ? 'No se detectó Stereo Mix ni cable virtual — revisa los pasos'
      : (surface === 'window'
          ? 'Sin audio — captura de ventana no incluye audio en Windows'
          : 'Sin audio — activa <b>Compartir audio del sistema</b> en el selector');
    document.getElementById('status').innerHTML = '<span class="dot yellow"></span>' + hint;
    document.getElementById('win-audio-guide').style.display = 'block';
  }
  document.getElementById('audio-btn').style.display = 'block';
}

async function detectWinAudio() {
  const btn = document.getElementById('detect-btn');
  btn.disabled = true; btn.textContent = 'Detectando…';
  try {
    const tmp = await navigator.mediaDevices.getUserMedia({ audio: true });
    tmp.getTracks().forEach(t => t.stop());
    await tryWindowsLoopback('detect');
  } catch(e) {
    if (e.name === 'NotAllowedError')
      document.getElementById('status').innerHTML = '<span class="dot red"></span>Permiso de micrófono denegado';
  } finally {
    btn.disabled = false; btn.textContent = '&#128269; Detectar Stereo Mix / cable virtual';
  }
}

async function addAudio() {
  const btn = document.getElementById('audio-btn');
  btn.disabled = true;
  btn.textContent = 'Seleccionando audio…';
  try {
    // Abort if no loopback device detected — do not fall back to mic (causes feedback)
    if (!_winLoopbackId) {
      btn.textContent = '&#127925; Agregar audio del sistema';
      btn.disabled = false;
      document.getElementById('status').innerHTML =
        '<span class="dot red"></span>No se detectó dispositivo de audio del sistema — ' +
        'reinicia y activa <b>Compartir audio del sistema</b> en el selector';
      return;
    }
    const audioConstraints = {
      echoCancellation: false,
      noiseSuppression: false,
      autoGainControl: false,
      sampleRate: 48000,
      channelCount: 2
    };
    audioConstraints.deviceId = { exact: _winLoopbackId };
    const audioStream = await navigator.mediaDevices.getUserMedia({ audio: audioConstraints });
    const audioTrack = audioStream.getAudioTracks()[0];
    audioTrack.contentHint = 'music';

    // Add audio track to the existing WebRTC sender
    if (pc) {
      pc.addTrack(audioTrack, stream);
      // Renegotiate
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      ws.send(JSON.stringify({type:'offer', from:'controller', sdp:pc.localDescription}));
    }

    stream.addTrack(audioTrack);
    btn.style.display = 'none';
    document.getElementById('mic-btn').style.display = 'block';
    document.getElementById('status').innerHTML = '<span class="dot green"></span>Transmitiendo con audio';
  } catch(e) {
    btn.textContent = '&#127925; Agregar audio del sistema';
    btn.disabled = false;
    document.getElementById('status').innerHTML = '<span class="dot red"></span>Audio no disponible: ' + e.message;
  }
}

async function toggleMic() {
  const btn = document.getElementById('mic-btn');
  if (!micTrack) {
    try {
      btn.disabled = true;
      const micStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, sampleRate: 48000 }
      });
      micTrack = micStream.getAudioTracks()[0];
      if (pc) pc.addTrack(micTrack, stream);
      btn.style.background = '#1b3a1b'; btn.style.borderColor = '#2e6b2e'; btn.style.color = '#6abf6a';
      btn.textContent = '&#127908; Micrófono: ON';
    } catch(e) {
      document.getElementById('status').innerHTML = '<span class="dot red"></span>Micrófono no disponible: ' + e.message;
    } finally { btn.disabled = false; }
  } else {
    micTrack.enabled = !micTrack.enabled;
    const on = micTrack.enabled;
    btn.style.background = on ? '#1b3a1b' : '#1a1a1a';
    btn.style.borderColor = on ? '#2e6b2e' : '#444';
    btn.style.color = on ? '#6abf6a' : '#888';
    btn.textContent = on ? '&#127908; Micrófono: ON' : '&#127908; Micrófono: OFF';
  }
}

function stopCast() {
  stream?.getTracks().forEach(t => t.stop());
  micTrack?.stop(); micTrack = null;
  pc?.close(); ws?.close(); resetBtn();
  _winLoopbackId = null;
  document.getElementById('audio-btn').style.display = 'none';
  document.getElementById('mic-btn').style.display = 'none';
  document.getElementById('mic-btn').style.background = '#1a1a1a';
  document.getElementById('mic-btn').style.borderColor = '#444';
  document.getElementById('mic-btn').style.color = '#888';
  document.getElementById('mic-btn').textContent = '&#127908; Micrófono: OFF';
  document.getElementById('win-audio-guide').style.display = 'none';
  document.getElementById('status').innerHTML = '<span class="dot red"></span>Transmisión terminada';
}

function resetBtn() {
  const btn = document.getElementById('btn');
  btn.textContent = '▶ Compartir pantalla'; btn.onclick = startCast; btn.disabled = false;
}
</script>
</body>
</html>"""
    }
}
