package dev.firecast.castv2

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

// DIAL (Discovery and Launch) protocol — HTTP server on port 8008.
// Chrome uses DIAL to discover Cast-compatible devices and request app launches.
class DialServer(
    private val port: Int = 8008,
    private val deviceId: String,
    private val friendlyName: String,
) {
    private var running = false
    var onAppLaunch: ((appId: String, params: String?) -> Unit)? = null

    fun start() {
        running = true
        thread(name = "DialServer") {
            try {
                val ss = ServerSocket(port)
                Log.i(TAG, "DIAL server on :$port")
                while (running) {
                    try { val client = ss.accept(); thread { handle(client) } }
                    catch (e: Exception) { if (running) Log.e(TAG, "accept: $e") }
                }
                ss.close()
            } catch (e: Exception) { Log.e(TAG, "start: $e") }
        }
    }

    fun stop() { running = false }

    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]; val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                line = reader.readLine()
            }
            val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
            val body    = if (bodyLen > 0) { val buf = CharArray(bodyLen); reader.read(buf, 0, bodyLen); String(buf) } else null

            Log.d(TAG, "$method $path")
            val out = socket.outputStream

            when {
                path == "/ssdp/device-desc.xml"   -> sendXml(out, deviceDesc(), headers)
                path == "/apps"                    -> sendXml(out, "<apps/>", headers)
                path.startsWith("/apps/")          -> handleApp(out, method, path, body, headers)
                path == "/setup/icon.png"          -> sendIcon(out)
                else                               -> send(out, 404, "text/plain", "Not Found", headers)
            }
        } catch (e: Exception) { Log.w(TAG, "handle: $e") }
        finally { runCatching { socket.close() } }
    }

    private fun handleApp(out: OutputStream, method: String, path: String, body: String?, headers: Map<String, String>) {
        val appId = path.removePrefix("/apps/").split("/")[0]
        when (method) {
            "GET"    -> sendXml(out, appStatus(appId, "stopped"), headers)
            "POST"   -> {
                Log.i(TAG, "Launch: appId=$appId body=$body")
                onAppLaunch?.invoke(appId, body)
                val localIp = getLocalIp()
                val header  = "HTTP/1.1 201 Created\r\nLocation: http://$localIp:$port/apps/$appId/run\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\n\r\n"
                out.write(header.toByteArray()); out.flush()
            }
            "DELETE" -> send(out, 200, "text/plain", "", headers)
            else     -> send(out, 405, "text/plain", "Method Not Allowed", headers)
        }
    }

    // ── Response helpers ──────────────────────────────────────────────────────

    private fun sendXml(out: OutputStream, xml: String, headers: Map<String, String> = emptyMap()) =
        send(out, 200, "text/xml; charset=utf-8", xml, headers)

    private fun send(out: OutputStream, code: Int, mime: String, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes   = body.toByteArray(Charsets.UTF_8)
        val localIp = getLocalIp()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code ${statusText(code)}\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Application-URL: http://$localIp:$port/apps/\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun sendIcon(out: OutputStream) {
        val png = byteArrayOf( // 1x1 transparent PNG
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x62, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x01, 0xE5.toByte(), 0x27, 0xDE.toByte(), 0xFC.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        val header = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n"
        out.write(header.toByteArray()); out.write(png); out.flush()
    }

    // ── XML templates ─────────────────────────────────────────────────────────

    private fun deviceDesc() = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
    <friendlyName>$friendlyName</friendlyName>
    <manufacturer>Google Inc.</manufacturer>
    <modelName>Chromecast</modelName>
    <UDN>uuid:$deviceId</UDN>
    <serviceList>
      <service>
        <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
        <SCPDURL>/apps</SCPDURL>
        <controlURL>/apps</controlURL>
      </service>
    </serviceList>
  </device>
</root>"""

    private fun appStatus(appId: String, state: String) = """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="2.1">
  <name>$appId</name>
  <options allowStop="true"/>
  <state>$state</state>
</service>"""

    private fun statusText(code: Int) = when(code) { 200 -> "OK"; 201 -> "Created"; 404 -> "Not Found"; else -> "Error" }

    private fun getLocalIp() = Companion.getLocalIp()

    companion object {
        private const val TAG = "DialServer"

        fun getLocalIp(): String = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) { "127.0.0.1" }
    }
}
