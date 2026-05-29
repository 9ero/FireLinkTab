package dev.firecast.castv2

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Two-layer PKI for FireLink:
//
//   FireLink Local CA  (persistent, user installs this once in the browser)
//       └── Server cert   (signed by CA, IP SAN, auto-regenerated when IP changes)
//
// The CA cert is served at http://ip:8080/ca.crt for easy one-click installation.
// Once the CA is trusted, all future HTTPS connections are warning-free.
object CertUtils {

    private const val TAG           = "CertUtils"
    private const val KS_FILE       = "firelink.p12"
    private const val KS_PASS_STR   = ""
    private val KS_PASS             = KS_PASS_STR.toCharArray()
    private const val CA_ALIAS      = "ca"
    private const val SERVER_ALIAS  = "server"

    // ── Public properties ─────────────────────────────────────────────────────

    var caCert:     X509Certificate? = null; private set
    var serverCert: X509Certificate? = null; private set
    var privateKey: PrivateKey?      = null; private set

    private var caKeyPair:     KeyPair? = null
    private var _sslContext:   SSLContext? = null

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Call once from MainActivity.onCreate().
     * Loads or generates the CA + server cert, regenerating the server cert
     * automatically if the device IP has changed since last run.
     */
    fun init(context: Context) {
        val currentIp = localIp()
        val ksFile    = File(context.filesDir, KS_FILE)

        var ks         = KeyStore.getInstance("PKCS12").apply { load(null, KS_PASS) }
        var needsCa    = true
        var needsServer = true

        if (ksFile.exists()) {
            try {
                ks = KeyStore.getInstance("PKCS12").apply {
                    ksFile.inputStream().use { load(it, KS_PASS) }
                }
                // Load CA
                val caEntry = ks.getEntry(CA_ALIAS, KeyStore.PasswordProtection(KS_PASS))
                        as? KeyStore.PrivateKeyEntry
                if (caEntry != null) {
                    caKeyPair = KeyPair(caEntry.certificate.publicKey, caEntry.privateKey)
                    caCert    = caEntry.certificate as X509Certificate
                    needsCa   = false
                    Log.i(TAG, "CA loaded (expires ${caCert!!.notAfter})")
                }

                // Load server cert — only reuse if IP matches
                if (!needsCa) {
                    val srvEntry = ks.getEntry(SERVER_ALIAS, KeyStore.PasswordProtection(KS_PASS))
                            as? KeyStore.PrivateKeyEntry
                    if (srvEntry != null) {
                        val certIp = ipFromCert(srvEntry.certificate as X509Certificate)
                        if (certIp == currentIp) {
                            privateKey  = srvEntry.privateKey
                            serverCert  = srvEntry.certificate as X509Certificate
                            needsServer = false
                            Log.i(TAG, "Server cert loaded for $currentIp")
                        } else {
                            Log.i(TAG, "IP changed ($certIp → $currentIp), regenerating server cert")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Keystore load error — regenerating: ${e.message}")
                ks = KeyStore.getInstance("PKCS12").apply { load(null, KS_PASS) }
                needsCa = true; needsServer = true
            }
        }

        if (needsCa) {
            val kp   = genRsaKeyPair()
            val cert = buildCaCert(kp)
            caKeyPair = kp
            caCert    = cert
            ks.setKeyEntry(CA_ALIAS, kp.private, KS_PASS, arrayOf(cert))
            needsServer = true
            Log.i(TAG, "New CA generated")
        }

        if (needsServer) {
            val kp   = genRsaKeyPair()
            val cert = buildServerCert(kp, currentIp, caKeyPair!!, caCert!!)
            privateKey  = kp.private
            serverCert  = cert
            // Chain: [server, CA] so browsers receive the full chain during TLS
            ks.setKeyEntry(SERVER_ALIAS, kp.private, KS_PASS, arrayOf(cert, caCert))
            Log.i(TAG, "New server cert generated for $currentIp")
        }

        ksFile.outputStream().use { ks.store(it, KS_PASS) }
        _sslContext = null // invalidate cached context
    }

    // ── Cast v2 server socket (self-signed, BouncyCastle TLS) ─────────────────

    fun createServerSocket(port: Int): SSLServerSocket {
        val ctx = castSslContext()
        val ss  = ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = false
        ss.wantClientAuth = false
        return ss
    }

    private fun castSslContext(): SSLContext = _sslContext ?: run {
        // Cast v2 uses the old self-signed approach — same cert, BouncyCastle TLS
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, KS_PASS)
            setKeyEntry("cast", privateKey, KS_PASS, arrayOf(serverCert))
        }
        val kmf = kmfFrom(ks)
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, trustAll(), null) }
    }.also { _sslContext = it }

    // ── HTTPS server socket (native TLS — Chrome compatible) ──────────────────

    /**
     * Creates an HTTPS/WSS server socket.
     * Priority: mkcert external cert → self-hosted CA cert.
     */
    fun createHttpsServerSocket(
        port: Int,
        certFile: File? = null,
        keyFile:  File? = null,
    ): SSLServerSocket {
        val kmf = if (certFile?.exists() == true && keyFile?.exists() == true) {
            loadMkcertKmf(certFile, keyFile)
                ?.also { Log.i(TAG, "Using mkcert cert: ${certFile.name}") }
        } else null

        val finalKmf = kmf ?: run {
            // Use self-hosted CA cert chain
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, KS_PASS)
                setKeyEntry(SERVER_ALIAS, privateKey, KS_PASS, arrayOf(serverCert, caCert))
            }
            kmfFrom(ks).also {
                if (kmf == null && certFile != null) Log.w(TAG, "mkcert cert not found, using CA cert")
            }
        }

        val nativeProvider = java.security.Security.getProviders().firstOrNull { p ->
            p.name.contains("OpenSSL", ignoreCase = true) ||
            p.name.contains("Conscrypt", ignoreCase = true)
        }
        val ctx = if (nativeProvider != null)
            SSLContext.getInstance("TLS", nativeProvider)
        else
            SSLContext.getInstance("TLS")
        ctx.init(finalKmf.keyManagers, trustAll(), null)

        return (ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket).also { ss ->
            ss.needClientAuth = false
            ss.wantClientAuth = false
            val protos = ss.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            if (protos.isNotEmpty()) ss.enabledProtocols = protos
        }
    }

    // ── CA cert access ────────────────────────────────────────────────────────

    /** DER-encoded CA cert bytes — served at /ca.crt for browser installation. */
    fun caCertDer(): ByteArray = caCert?.encoded ?: ByteArray(0)

    /** Standard external files dir — writable via ADB without root. */
    fun certDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    // ── Cert builders ─────────────────────────────────────────────────────────

    private fun buildCaCert(kp: KeyPair): X509Certificate {
        val name     = X500Name("CN=FireLink Local CA,O=FireLink")
        val now      = Date()
        val notAfter = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        return JcaX509v3CertificateBuilder(name, BigInteger.ONE, now, notAfter, name, kp.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(kp.private))
            .let { JcaX509CertificateConverter().getCertificate(it) }
    }

    private fun buildServerCert(
        kp: KeyPair, ip: String,
        caKp: KeyPair, caCert: X509Certificate,
    ): X509Certificate {
        val subject  = X500Name("CN=FireLink Server,O=FireLink")
        val issuer   = X500Name("CN=FireLink Local CA,O=FireLink")
        val now      = Date()
        val notAfter = Date(now.time + 2L * 365 * 24 * 3600 * 1000)
        val ipBytes  = InetAddress.getByName(ip).address   // 4 bytes for IPv4
        return JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(2), now, notAfter, subject, kp.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            .addExtension(Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.iPAddress, DEROctetString(ipBytes))))
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(caKp.private))
            .let { JcaX509CertificateConverter().getCertificate(it) }
    }

    // ── mkcert PEM loader ─────────────────────────────────────────────────────

    private fun loadMkcertKmf(certFile: File, keyFile: File): KeyManagerFactory? = try {
        val cert = certFile.inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        val keyPem = keyFile.readText()
            .replace(Regex("-----BEGIN.*?-----"), "")
            .replace(Regex("-----END.*?-----"), "")
            .replace(Regex("\\s"), "")
        val keyBytes = Base64.decode(keyPem, Base64.DEFAULT)
        val key = try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } catch (e: Exception) {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        }
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, KS_PASS)
            setKeyEntry("mkcert", key, KS_PASS, arrayOf(cert))
        }
        kmfFrom(ks)
    } catch (e: Exception) {
        Log.e(TAG, "mkcert load failed: ${e.message}"); null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun genRsaKeyPair() =
        KeyPairGenerator.getInstance("RSA").run { initialize(2048); generateKeyPair() }

    private fun kmfFrom(ks: KeyStore) =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, KS_PASS)
        }

    private fun trustAll() = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    })

    /** Read the first iPAddress SAN from a cert, or null if absent. */
    private fun ipFromCert(cert: X509Certificate): String? = try {
        cert.subjectAlternativeNames
            ?.firstOrNull { (it as List<*>)[0] == 7 }
            ?.let { (it as List<*>)[1] as? String }
    } catch (e: Exception) { null }

    fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress ?: "127.0.0.1"
    } catch (e: Exception) { "127.0.0.1" }
}
