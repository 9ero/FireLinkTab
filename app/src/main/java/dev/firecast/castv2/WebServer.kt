package dev.firecast.castv2

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

// HTTP server on port 8080 — serves receiver.html to the internal WebView.
// The controller page is served separately over HTTPS by ControllerServer (port 8443).
class WebServer(private val port: Int = 8080) {

    private var running = false

    fun start() {
        running = true
        thread(name = "WebServer") {
            try {
                val ss = ServerSocket(port)
                Log.i(TAG, "Web server (HTTP) on :$port")
                while (running) {
                    try { val client = ss.accept(); thread { handle(client) } }
                    catch (e: Exception) { if (running) Log.e(TAG, "accept: ${e.message}") }
                }
                ss.close()
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }
    }

    fun stop() { running = false }

    private fun handle(socket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrElse(1) { "/" }
            Log.d(TAG, "GET $path")
            val out = socket.outputStream
            val body = when {
                path == "/receiver" -> RECEIVER_HTML
                path == "/ping"     -> "pong"
                else                -> RECEIVER_HTML
            }.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(body)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "handle: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "WebServer"

        // ── Controller page (Chrome) ──────────────────────────────────────────
        val CONTROLLER_HTML = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FireCast — Transmitir</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0f0f0f;color:#f1f1f1;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
     display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;gap:20px;padding:24px}
h1{font-size:2rem;font-weight:700;color:#ff4444}
.card{background:#1e1e1e;border-radius:12px;padding:24px;max-width:480px;width:100%;text-align:center}
.warn{background:#2a1a00;border:1px solid #ff8800;border-radius:8px;padding:14px;font-size:.85rem;
      color:#ffb347;margin-bottom:16px;text-align:left;line-height:1.5}
.warn code{background:#1a1000;padding:2px 5px;border-radius:3px;font-size:.8rem}
button{background:#e53935;color:#fff;border:none;padding:14px 28px;border-radius:8px;
       font-size:1rem;font-weight:600;cursor:pointer;width:100%;transition:.2s}
button:hover:not(:disabled){background:#c62828}
button:disabled{background:#444;cursor:not-allowed}
#status{margin-top:12px;font-size:.9rem;color:#aaa;min-height:20px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px}
.green{background:#4caf50}.red{background:#f44336}.yellow{background:#ff9800}
</style>
</head>
<body>
<h1>&#128308; FireCast</h1>
<div class="card">
  <div class="warn" id="warn" style="display:none">
    <b>&#9888; Certificado no reconocido</b><br><br>
    Si Chrome muestra advertencia de seguridad al cargar esta página, haz clic en
    <b>Avanzado &rarr; Acceder a <span id="myIp2"></span> (sitio no seguro)</b>
    una sola vez. El certificado es auto-firmado por la propia app.
  </div>
  <button id="btn" onclick="startCast()">&#128250; Compartir pantalla</button>
  <p id="status"></p>
</div>

<script>
document.getElementById('myIp2').textContent = location.hostname;

// getDisplayMedia requires secure context (HTTPS) — show hint if unavailable
if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
  document.getElementById('warn').style.display = 'block';
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
    ws = new WebSocket('ws://' + location.hostname + ':8081');
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
      ws.send(JSON.stringify({type:'hello', role:'controller'}));
    };

    ws.onmessage = async e => {
      const msg = JSON.parse(e.data);
      if (msg.type === 'ready') {
        // Receiver is connected — now send the offer
        status.innerHTML = '<span class="dot yellow"></span>Receiver listo, negociando…';
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        ws.send(JSON.stringify({type:'offer', from:'controller', sdp:pc.localDescription}));
      } else if (msg.type === 'answer') {
        await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
      } else if (msg.type === 'ice' && msg.from === 'receiver') {
        await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
      }
    };

    ws.onerror = () => { status.innerHTML = '<span class="dot red"></span>Error de conexión WebSocket'; resetBtn(); };
    stream.getTracks()[0].onended = stopCast;

  } catch(e) {
    if (e.name === 'NotAllowedError') {
      status.innerHTML = '<span class="dot red"></span>Permiso denegado';
    } else if (e.name === 'NotFoundError' || e.name === 'NotSupportedError') {
      document.getElementById('warn').style.display = 'block';
      status.innerHTML = '<span class="dot red"></span>getDisplayMedia no disponible — sigue las instrucciones.';
    } else {
      status.innerHTML = '<span class="dot red"></span>' + e.message;
    }
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

        // ── Receiver page (WebView on Fire TV) ───────────────────────────────
        val RECEIVER_HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
*{margin:0;padding:0}
body{background:#000;overflow:hidden;width:100vw;height:100vh}
video{width:100%;height:100%;object-fit:contain;display:block}
#overlay{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;
          flex-direction:column;gap:16px;color:#555;font-family:sans-serif}
.logo{font-size:3rem;font-weight:700;color:#ff4444}
#msg{font-size:1.1rem}
</style>
</head>
<body>
<video id="v" autoplay playsinline></video>
<div id="overlay">
  <div class="logo">&#128308; FireCast</div>
  <div id="msg">Conectando&#8230;</div>
</div>
<script>
function log(s) { document.getElementById('msg').textContent = s; try{Android.log(s);}catch(e){} }

let pc;

function initPC() {
  try {
    pc = new RTCPeerConnection();
  } catch(e) {
    log('WebRTC no disponible: ' + e.message);
    try { Android.log('RTCPeerConnection failed: ' + e.message); } catch(_) {}
    return false;
  }

  pc.ontrack = e => {
    log('Stream recibido');
    document.getElementById('v').srcObject = e.streams[0];
    document.getElementById('overlay').style.display = 'none';
    try { Android.onStreamStarted(); } catch(_) {}
  };

  pc.onicecandidate = e => {
    if (e.candidate && ws && ws.readyState === 1)
      ws.send(JSON.stringify({type:'ice', from:'receiver', candidate:e.candidate}));
  };

  pc.onconnectionstatechange = () => {
    log('WebRTC: ' + pc.connectionState);
    if (['disconnected','failed','closed'].includes(pc.connectionState)) {
      try { Android.onStreamStopped(); } catch(_) {}
    }
  };
  return true;
}

// Diagnostic: test basic HTTP first
fetch('/ping')
  .then(r => r.text()).then(t => log('HTTP OK: ' + t))
  .catch(e => log('HTTP FAIL: ' + e.message));

let ws;
function connect() {
  log('Conectando al servidor…');
  ws = new WebSocket('ws://' + location.hostname + ':8081');

  ws.onopen = () => {
    log('Esperando controlador…');
    ws.send(JSON.stringify({type:'hello', role:'receiver'}));
    if (!initPC()) return;
  };

  ws.onmessage = async e => {
    try {
      const msg = JSON.parse(e.data);
      if (msg.type === 'offer') {
        log('Oferta recibida, respondiendo…');
        await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        ws.send(JSON.stringify({type:'answer', from:'receiver', sdp:pc.localDescription}));
      } else if (msg.type === 'ice' && msg.from === 'controller') {
        await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
      }
    } catch(e) { log('Error: ' + e.message); }
  };

  ws.onerror = e => log('WS error');
  ws.onclose = () => { log('WS cerrado, reconectando…'); setTimeout(connect, 2000); };
}

connect();
</script>
</body>
</html>"""
    }
}
