package dev.firecast.castv2

import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object CertUtils {

    private const val TAG      = "CertUtils"
    private const val KS_FILE  = "firelinkab.p12"
    private const val KS_ALIAS = "server"
    private val KS_PASS        = charArrayOf()

    // ── Self-signed cert — persisted across restarts ──────────────────────────
    // Stored in app internal storage so the browser only needs to accept it once.

    private var _keyPair: KeyPair?       = null
    private var _cert: X509Certificate?  = null
    private var _sslContext: SSLContext? = null

    val keyPair: KeyPair             get() = _keyPair!!
    val certificate: X509Certificate get() = _cert!!
    val privateKey: PrivateKey       get() = keyPair.private

    /**
     * Must be called once at app start (e.g. from MainActivity.onCreate).
     * Loads the persisted cert/key from internal storage, or generates and saves a new one.
     */
    fun init(context: android.content.Context) {
        if (_keyPair != null) return
        val ksFile = java.io.File(context.filesDir, KS_FILE)
        if (ksFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12").apply {
                    ksFile.inputStream().use { load(it, KS_PASS) }
                }
                val entry = ks.getEntry(KS_ALIAS, KeyStore.PasswordProtection(KS_PASS))
                        as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    _keyPair = java.security.KeyPair(entry.certificate.publicKey, entry.privateKey)
                    _cert    = entry.certificate as X509Certificate
                    Log.i(TAG, "Loaded persisted cert (expires ${_cert!!.notAfter})")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load persisted cert, regenerating: ${e.message}")
            }
        }

        // Generate new cert and persist it
        val kp   = KeyPairGenerator.getInstance("RSA").run { initialize(2048); generateKeyPair() }
        val cert = buildSelfSigned(kp)
        val ks   = KeyStore.getInstance("PKCS12").apply {
            load(null, KS_PASS)
            setKeyEntry(KS_ALIAS, kp.private, KS_PASS, arrayOf(cert))
        }
        ksFile.outputStream().use { ks.store(it, KS_PASS) }
        _keyPair = kp
        _cert    = cert
        Log.i(TAG, "Generated and persisted new self-signed cert")
    }

    fun sslContext(): SSLContext = _sslContext ?: buildSslContext().also { _sslContext = it }

    private fun buildSelfSigned(kp: KeyPair): X509Certificate {
        val subject  = X500Name("CN=FireLinkTab,O=FireLinkTab,C=US")
        val now      = Date()
        val notAfter = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        val builder  = JcaX509v3CertificateBuilder(subject, BigInteger.ONE, now, notAfter, subject, kp.public)
        val signer   = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun buildSslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, KS_PASS)
            setKeyEntry("cast", keyPair.private, KS_PASS, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, KS_PASS)
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, trustAllManager(), null) }
    }

    /** Cast v2 server socket (BouncyCastle TLS — compatible with Chrome's Cast client). */
    fun createServerSocket(port: Int): SSLServerSocket {
        val ss = sslContext().serverSocketFactory.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = false
        ss.wantClientAuth = false
        return ss
    }

    // ── HTTPS server socket ───────────────────────────────────────────────────

    /**
     * Creates an HTTPS server socket using the native Android TLS provider (Conscrypt/AndroidOpenSSL).
     * If [certFile] and [keyFile] are provided and valid (mkcert or similar), they are used —
     * no browser warning will appear. Falls back to the self-signed cert otherwise.
     *
     * Expected file format: PEM (the default output of mkcert).
     * Typical setup:
     *   mkcert -install
     *   mkcert <firetv-ip>
     *   adb push <ip>.pem       /sdcard/Android/data/dev.firecast.castv2/files/cert.pem
     *   adb push <ip>-key.pem   /sdcard/Android/data/dev.firecast.castv2/files/key.pem
     */
    fun createHttpsServerSocket(port: Int, certFile: File? = null, keyFile: File? = null): SSLServerSocket {
        val kmf = if (certFile != null && keyFile != null && certFile.exists() && keyFile.exists()) {
            loadExternalKmf(certFile, keyFile)
        } else null

        if (kmf == null && certFile != null) {
            Log.w(TAG, "External cert not loaded — falling back to self-signed")
        } else if (kmf != null) {
            Log.i(TAG, "Using external cert: ${certFile!!.name}")
        }

        val finalKmf = kmf ?: run {
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("key", privateKey, null, arrayOf(certificate))
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        }

        val nativeProvider = java.security.Security.getProviders().firstOrNull { p ->
            p.name.contains("OpenSSL", ignoreCase = true) ||
            p.name.contains("Conscrypt", ignoreCase = true)
        }
        val ctx = if (nativeProvider != null)
            SSLContext.getInstance("TLS", nativeProvider)
        else
            SSLContext.getInstance("TLS")
        ctx.init(finalKmf.keyManagers, trustAllManager(), null)

        return (ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket).also { ss ->
            ss.needClientAuth = false
            ss.wantClientAuth = false
            val protos = ss.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            if (protos.isNotEmpty()) ss.enabledProtocols = protos
        }
    }

    // ── PEM loader ────────────────────────────────────────────────────────────

    private fun loadExternalKmf(certFile: File, keyFile: File): KeyManagerFactory? {
        return try {
            // Certificate (PEM — CertificateFactory handles the header automatically)
            val cert = certFile.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }

            // Private key — strip PEM headers and decode base64
            val keyPem = keyFile.readText()
                .replace(Regex("-----BEGIN.*?-----"), "")
                .replace(Regex("-----END.*?-----"), "")
                .replace(Regex("\\s"), "")
            val keyBytes = Base64.getDecoder().decode(keyPem)
            val key = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            } catch (e: Exception) {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            }

            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("mkcert", key, null, arrayOf(cert))
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load external cert: ${e.message}")
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun trustAllManager() = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    })

    /** Standard directory for cert files, writable via ADB without root. */
    fun certDir(context: android.content.Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir
}
