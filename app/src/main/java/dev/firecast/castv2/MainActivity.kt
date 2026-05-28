package dev.firecast.castv2

import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusContainer: LinearLayout
    private lateinit var webView: WebView
    private lateinit var statusMdns: TextView
    private lateinit var statusDial: TextView
    private lateinit var statusCast: TextView

    private val deviceId     = UUID.randomUUID().toString()
    private val friendlyName = "FireLinkTab"

    private val discovery        = lazy { CastDiscovery(this) }
    private val dialServer       = lazy { DialServer(8008, deviceId, friendlyName) }
    private val castServer       = lazy { CastV2Server(9009) }
    private val ssdpServer       = lazy { SsdpServer(this, deviceId, DialServer.getLocalIp(), 8008) }
    private val webServer        = lazy { WebServer(8080) }      // HTTP: receiver.html for WebView
    private val signalingServer  = lazy { SignalingServer(8081) } // WS: receiver signaling
    private val controllerServer = lazy { ControllerServer(8443) } // HTTPS+WSS: controller

    // Track connection state for the "ready" signal
    @Volatile private var receiverConnected = false
    @Volatile private var controllerConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        statusContainer = findViewById(R.id.status_container)
        webView         = findViewById(R.id.webview)
        statusMdns      = findViewById(R.id.status_mdns)
        statusDial      = findViewById(R.id.status_dial)
        statusCast      = findViewById(R.id.status_cast)

        setupWebView()
        startServices()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode                        = WebSettings.LOAD_NO_CACHE
            useWideViewPort                  = true
            loadWithOverviewMode             = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed() // Accept app's own self-signed cert
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onStreamStarted() = ui { showReceiver() }
            @JavascriptInterface
            fun onStreamStopped() = ui { showStatus() }
            @JavascriptInterface
            fun log(msg: String) = android.util.Log.i("ReceiverJS", msg)
        }, "Android")
    }

    private fun startServices() {
        val localIp = DialServer.getLocalIp()

        // ── Receiver signaling (WS, plain) ────────────────────────────────────
        signalingServer.value.apply {
            onConnected = {
                receiverConnected = true
                if (controllerConnected) controllerServer.value.sendToController(READY)
                android.util.Log.i("Relay", "Receiver connected")
            }
            onDisconnected = { receiverConnected = false }
            onMessage = { msg -> controllerServer.value.sendToController(msg) }
            start()
        }

        // ── Controller server (HTTPS + WSS on same port) ──────────────────────
        controllerServer.value.apply {
            onConnected = {
                controllerConnected = true
                if (receiverConnected) sendToController(READY)
                android.util.Log.i("Relay", "Controller connected")
            }
            onDisconnected = { controllerConnected = false }
            onMessage = { msg -> signalingServer.value.sendToReceiver(msg) }
            start()
        }

        // ── WebServer (HTTP, serves receiver.html to WebView) ─────────────────
        webServer.value.start()

        // ── mDNS ─────────────────────────────────────────────────────────────
        discovery.value.onRegistered = { name ->
            ui { statusMdns.text = "● mDNS: activo ($name)"; statusMdns.setTextColor(GREEN) }
        }
        discovery.value.start(deviceId, friendlyName, 9009)
        ui { statusMdns.text = "● mDNS: registrando…" }

        // ── DIAL HTTP (port 8008) ─────────────────────────────────────────────
        dialServer.value.onAppLaunch = { appId, _ ->
            ui { statusDial.text = "● DIAL: lanzando $appId" }
        }
        dialServer.value.start()
        ui { statusDial.text = "● Visita https://$localIp:8443 para transmitir"; statusDial.setTextColor(GREEN) }

        // ── SSDP ─────────────────────────────────────────────────────────────
        ssdpServer.value.start()

        // ── Cast v2 TLS (port 9009) ───────────────────────────────────────────
        castServer.value.onStatusUpdate = { msg ->
            ui { statusCast.text = "● Cast v2: $msg" }
        }
        castServer.value.onClientConnected = {
            ui { statusCast.setTextColor(YELLOW) }
        }
        castServer.value.start()
        ui { statusCast.text = "● Cast v2: escuchando (:9009)"; statusCast.setTextColor(GREEN) }

        // ── Load receiver in WebView (HTTP, cleartext allowed) ────────────────
        ui {
            webView.loadDataWithBaseURL(
                "http://$localIp:8080/",
                WebServer.RECEIVER_HTML,
                "text/html", "utf-8", null
            )
        }
    }

    private fun showReceiver() {
        statusContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun showStatus() {
        webView.visibility = View.GONE
        val localIp = DialServer.getLocalIp()
        webView.loadDataWithBaseURL(
            "http://$localIp:8080/",
            WebServer.RECEIVER_HTML,
            "text/html", "utf-8", null
        )
        statusContainer.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.visibility == View.VISIBLE) {
            showStatus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.value.stop()
        runCatching { dialServer.value.stop() }
        castServer.value.stop()
        runCatching { ssdpServer.value.stop() }
        runCatching { webServer.value.stop() }
        signalingServer.value.stop()
        controllerServer.value.stop()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    companion object {
        private val GREEN  = 0xFF4CAF50.toInt()
        private val YELLOW = 0xFFF0A500.toInt()
        private const val READY = """{"type":"ready"}"""
    }
}
