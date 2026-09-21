package com.example.carriex.transport

import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** Real localhost TLS handshakes; no trust-all validator or in-memory HTTP client. */
class LocalCertificateAuthorityTest {
    companion object {
        private lateinit var server: SSLServerSocket
        private lateinit var serverThread: Thread
        private lateinit var ca: LocalCertificateAuthority
        private lateinit var otherCa: LocalCertificateAuthority
        private lateinit var temp: File
        private lateinit var endpoint: String

        private fun createStore(name: String): KeyStore {
            val path = File(temp, "$name.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=$name", "-validity", "2", "-ext", "bc=ca:true", "-ext", "san=dns:localhost", "-storetype", "PKCS12", "-keystore", path.path, "-storepass", "test-only-password", "-noprompt").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return KeyStore.getInstance("PKCS12").apply { path.inputStream().use { load(it, "test-only-password".toCharArray()) } }
        }

        @JvmStatic @BeforeClass fun startServer() {
            temp = java.nio.file.Files.createTempDirectory("carriex-tls-test").toFile()
            val store = createStore("Trusted-test-CA")
            ca = LocalCertificateAuthority(store.getCertificate("test").encoded)
            otherCa = LocalCertificateAuthority(createStore("Untrusted-test-CA").getCertificate("test").encoded)
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only-password".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            server = context.serverSocketFactory.createServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            serverThread = kotlin.concurrent.thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 3000
                            val reader = socket.inputStream.bufferedReader()
                            var line = reader.readLine()
                            while (line != null && line.isNotEmpty()) line = reader.readLine()
                            socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nalive".toByteArray())
                            socket.outputStream.flush()
                        }
                    } catch (_: java.io.IOException) { /* Failed client handshakes are expected. */ }
                }
            }
            endpoint = "https://localhost:${server.localPort}/health"
        }
        @JvmStatic @AfterClass fun stopServer() {
            if (::server.isInitialized) server.close()
            if (::serverThread.isInitialized) serverThread.join(4000)
            if (::temp.isInitialized) temp.deleteRecursively()
        }
    }

    private fun client(authority: LocalCertificateAuthority) = OkHttpClient.Builder()
        .sslSocketFactory(authority.socketFactory, authority.trustManager).build()

    @Test fun approvedCaAcceptsMatchingServer() {
        client(ca).newCall(Request.Builder().url(endpoint).build()).execute().use { assertEquals("alive", it.body!!.string()) }
    }

    @Test fun approvedCaAlsoWorksForHttpFallback() {
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ca.socketFactory
        try { assertEquals("alive", connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
    }

    @Test fun rejectsUnapprovedCertificate() {
        assertThrows(SSLException::class.java) { client(otherCa).newCall(Request.Builder().url(endpoint).build()).execute().close() }
    }

    @Test fun rejectsWrongHostname() {
        assertThrows(SSLException::class.java) { client(ca).newCall(Request.Builder().url(endpoint.replace("localhost", "127.0.0.1")).build()).execute().close() }
    }

    @Test fun httpFallbackAlsoRejectsWrongHostname() {
        val connection = URL(endpoint.replace("localhost", "127.0.0.1")).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ca.socketFactory
        try { assertThrows(SSLException::class.java) { connection.inputStream.close() } }
        finally { connection.disconnect() }
    }

    @Test fun rejectsInvalidCertificateInput() {
        assertThrows(CertificateException::class.java) { LocalCertificateAuthority("not a certificate".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { LocalCertificateAuthority(ByteArray(65537)) }
        assertTrue(ca.fingerprint.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
}
