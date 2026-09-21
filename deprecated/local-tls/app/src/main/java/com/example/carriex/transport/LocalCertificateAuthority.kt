package com.example.carriex.transport

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Uses the platform PKIX validator with exactly the explicitly approved CA. */
class LocalCertificateAuthority(bytes: ByteArray) {
    val certificate: X509Certificate
    val fingerprint: String
    val trustManager: X509TrustManager
    val socketFactory: javax.net.ssl.SSLSocketFactory

    init {
        require(bytes.size <= 65536) { "Certificate file is too large." }
        val certificates = CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream())
        require(certificates.size == 1) { "Select a single root CA certificate." }
        certificate = certificates.single() as X509Certificate
        certificate.checkValidity()
        require(certificate.basicConstraints >= 0) { "This is not a CA certificate." }
        require(certificate.subjectX500Principal == certificate.issuerX500Principal) { "Select the root CA certificate, not an intermediate." }
        certificate.verify(certificate.publicKey)
        val usage = certificate.keyUsage
        require(usage == null || (usage.size > 5 && usage[5])) { "Certificate cannot sign certificates." }
        fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("approved-local-ca", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }
}
