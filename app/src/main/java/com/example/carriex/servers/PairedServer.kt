package com.example.carriex.servers

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Public pairing information. Construction always validates imported data. */
class PairedServer private constructor(
    val name: String,
    val url: String,
    private val encodedCertificate: String,
    private val certificate: X509Certificate,
) {
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        .joinToString("") { "%02x".format(it) }

    fun toJson(): JSONObject = JSONObject().put("type", "carriex-server").put("version", 1)
        .put("name", name).put("url", url).put("certificate", encodedCertificate)

    fun client(): OkHttpClient {
        // Trust exactly the imported server, never every self-signed certificate.
        // Normal OkHttp hostname/SAN verification remains enabled.
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not used by this trust manager")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
                if (!MessageDigest.isEqual(leaf.publicKey.encoded, certificate.publicKey.encoded)) {
                    throw CertificateException("Server identity changed. Verify the server before pairing again")
                }
                leaf.checkValidity()
                if (leaf.subjectX500Principal != leaf.issuerX500Principal) {
                    throw CertificateException("Expected a self-signed server certificate")
                }
                leaf.verify(certificate.publicKey)
                // The QR pins the key, not the certificate's renewal date. Only
                // after proving that pin and self-signature, trust this current
                // leaf. An old non-CA leaf cannot be used as a CA for its renewal.
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null); setCertificateEntry("paired-server", leaf)
                }
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
                val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
                delegate.checkServerTrusted(chain, authType)
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
            .followRedirects(false).followSslRedirects(false)
            .pingInterval(20, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    companion object {
        /** Importing is offline: it never downloads or trusts a key from the URL. */
        fun parse(text: String): PairedServer {
            require(text.length in 1..8192) { "Pairing text is empty or too long" }
            val payload = try { JSONObject(text.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("Paste the complete Carriex pairing text from pairing.json")
            }
            require(payload.optString("type") == "carriex-server" && payload.opt("version") == 1) {
                "Unsupported Carriex pairing format"
            }
            val name = payload.opt("name") as? String ?: throw IllegalArgumentException("Missing server name")
            require(name.isNotBlank() && name.length <= 80 && name.none { it.code < 32 }) { "Invalid server name" }
            val rawUrl = payload.opt("url") as? String ?: throw IllegalArgumentException("Missing server address")
            require(rawUrl.length <= 512 && rawUrl.none { it.isWhitespace() }) { "Invalid server address" }
            val endpoint = try { rawUrl.toHttpUrl() } catch (_: Exception) { throw IllegalArgumentException("Invalid HTTPS server address") }
            require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
                endpoint.encodedPath == "/" && endpoint.query == null && endpoint.fragment == null &&
                endpoint.host !in setOf("0.0.0.0", "::")) {
                "Pairing requires an HTTPS address with no credentials, path, query or fragment"
            }
            val encoded = payload.opt("certificate") as? String ?: throw IllegalArgumentException("Missing server certificate")
            val bytes = encoded.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("Invalid server certificate encoding")
            require(bytes.size in 1..4096) { "Invalid certificate size" }
            val certificate = try {
                val input = ByteArrayInputStream(bytes)
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
                require(input.available() == 0 && cert.encoded.contentEquals(bytes)) { "Invalid certificate data" }
                val key = cert.publicKey as? ECPublicKey ?: throw IllegalArgumentException("Expected a P-256 server key")
                require(key.params.curve.field.fieldSize == 256 && cert.sigAlgOID == "1.2.840.10045.4.3.2") { "Unsupported server certificate algorithm" }
                require(cert.subjectX500Principal == cert.issuerX500Principal) { "Expected a self-signed server certificate" }
                cert.verify(key)
                // Check dates at connection time, so a saved key remains usable
                // after the server renews its certificate using the same key.
                cert
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid server certificate: ${e.message}", e)
            }
            return PairedServer(name.trim(), endpoint.toString().trimEnd('/'), encoded, certificate)
        }
    }
}
