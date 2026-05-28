package dev.firecast.castv2

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Generates a self-signed RSA certificate for the TLS server.
// Note: Chrome validates the application-level DeviceAuth certificate (not this TLS cert)
// against Google's Cast CA. This TLS cert establishes the transport — authentication
// happens at the protocol layer inside CastV2Server.
object CertUtils {

    private var _keyPair: KeyPair?       = null
    private var _cert: X509Certificate?  = null
    private var _sslContext: SSLContext? = null

    val keyPair: KeyPair get() = _keyPair ?: KeyPairGenerator.getInstance("RSA").run {
        initialize(2048); generateKeyPair()
    }.also { _keyPair = it }

    val certificate: X509Certificate get() = _cert ?: buildSelfSigned(keyPair).also { _cert = it }
    val privateKey: PrivateKey         get() = keyPair.private

    fun sslContext(): SSLContext = _sslContext ?: buildSslContext().also { _sslContext = it }

    private fun buildSelfSigned(kp: KeyPair): X509Certificate {
        val subject = X500Name("CN=Chromecast,O=Google Inc,L=Mountain View,ST=California,C=US")
        val now     = Date()
        val notAfter= Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger.ONE, now, notAfter, subject, kp.public)
        val signer  = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun buildSslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("cast", keyPair.private, null, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, null)
        }
        // Accept any client certificate (Chrome presents none for Cast)
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, trustAll, null) }
    }

    fun createServerSocket(port: Int): SSLServerSocket {
        val ss = sslContext().serverSocketFactory.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = false
        ss.wantClientAuth = false
        return ss
    }

    // SSLContext using Android's native OpenSSL/Conscrypt provider instead of BouncyCastle.
    // BouncyCastle's JSSE TLS implementation can't parse Chrome's TLS 1.3 Client Hello,
    // causing ERR_SSL_VERSION_OR_CIPHER_MISMATCH for HTTPS connections.
    fun createHttpsServerSocket(port: Int): SSLServerSocket {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("key", privateKey, null, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, null)
        }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        })
        // Pick Android's native TLS provider (AndroidOpenSSL or Conscrypt),
        // skipping BouncyCastle which intercepts SSLContext.getInstance("TLS").
        val nativeProvider = java.security.Security.getProviders().firstOrNull { p ->
            p.name.contains("OpenSSL", ignoreCase = true) ||
            p.name.contains("Conscrypt", ignoreCase = true)
        }
        val ctx = if (nativeProvider != null)
            SSLContext.getInstance("TLS", nativeProvider)
        else
            SSLContext.getInstance("TLSv1.2") // fallback
        ctx.init(kmf.keyManagers, trustAll, null)

        return (ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket).also { ss ->
            ss.needClientAuth = false
            ss.wantClientAuth = false
            val protos = ss.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            if (protos.isNotEmpty()) ss.enabledProtocols = protos
        }
    }
}
