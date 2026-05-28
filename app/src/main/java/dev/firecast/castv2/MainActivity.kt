package dev.firecast.castv2

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
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
    private lateinit var titleView: TextView
    private lateinit var urlText: TextView
    private lateinit var statusReceiver: TextView

    private val deviceId     = UUID.randomUUID().toString()
    private val friendlyName = "FireLinkTab"

    private val discovery        = lazy { CastDiscovery(this) }
    private val dialServer       = lazy { DialServer(8008, deviceId, friendlyName) }
    private val castServer       = lazy { CastV2Server(9009) }
    private val ssdpServer       = lazy { SsdpServer(this, deviceId, DialServer.getLocalIp(), 8008) }
    private val webServer        = lazy { WebServer(8080) }
    private val signalingServer  = lazy { SignalingServer(8081) }
    private val controllerServer = lazy { ControllerServer(8443) }

    @Volatile private var receiverConnected  = false
    @Volatile private var controllerConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        statusContainer = findViewById(R.id.status_container)
        webView         = findViewById(R.id.webview)
        titleView       = findViewById(R.id.title)
        urlText         = findViewById(R.id.url_text)
        statusReceiver  = findViewById(R.id.status_receiver)

        val localIp = DialServer.getLocalIp()
        urlText.text = "https://$localIp:8443"

        // Apply red→orange gradient to title after layout pass
        titleView.viewTreeObserver.addOnGlobalLayoutListener {
            applyTitleGradient()
        }

        setupWebView()
        startServices(localIp)
    }

    private fun applyTitleGradient() {
        val width = titleView.paint.measureText(titleView.text.toString())
        if (width <= 0f) return
        val gradient = LinearGradient(
            0f, 0f, width, 0f,
            intArrayOf(
                Color.parseColor("#FF2D2D"),  // red
                Color.parseColor("#FF6B00"),  // orange-red
                Color.parseColor("#FF9500"),  // orange
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        titleView.paint.shader = gradient
        titleView.invalidate()
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
                handler.proceed()
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

    private fun startServices(localIp: String) {
        // ── Receiver signaling (WS plain) ─────────────────────────
        signalingServer.value.apply {
            onConnected = {
                receiverConnected = true
                if (controllerConnected) controllerServer.value.sendToController(READY)
                ui { statusReceiver.text = "● listo"; statusReceiver.setTextColor(READY_COLOR) }
                android.util.Log.i("Relay", "Receiver connected")
            }
            onDisconnected = {
                receiverConnected = false
                ui { statusReceiver.text = "● reconectando…"; statusReceiver.setTextColor(DIM_COLOR) }
            }
            onMessage = { msg -> controllerServer.value.sendToController(msg) }
            start()
        }

        // ── Controller server (HTTPS + WSS) ───────────────────────
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

        // ── HTTP server (receiver.html → WebView) ─────────────────
        webServer.value.start()

        // ── mDNS ──────────────────────────────────────────────────
        discovery.value.onRegistered = { _ -> }
        discovery.value.start(deviceId, friendlyName, 9009)

        // ── DIAL ──────────────────────────────────────────────────
        dialServer.value.start()

        // ── SSDP ──────────────────────────────────────────────────
        ssdpServer.value.start()

        // ── Cast v2 ───────────────────────────────────────────────
        castServer.value.start()

        // ── Load receiver in hidden WebView ───────────────────────
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
        private val READY_COLOR = 0xFF4CAF50.toInt()
        private val DIM_COLOR   = 0xFF333333.toInt()
        private const val READY = """{"type":"ready"}"""
    }
}
