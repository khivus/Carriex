package com.example.carriex.servers

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PairedServerTest {
    private fun certificate(host: String = "127.0.0.1") = HeldCertificate.Builder()
        .ecdsa256().commonName("Carriex paired server").addSubjectAlternativeName(host).build()

    private fun payload(cert: HeldCertificate, url: String = "https://127.0.0.1:8443", name: String = "Home") = JSONObject()
        .put("type", "carriex-server").put("version", 1).put("name", name).put("url", url)
        .put("certificate", cert.certificate.encoded.toByteString().base64())

    private fun shutdown(client: OkHttpClient) {
        client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun importIsOfflineAndPersistsMultipleServersAcrossReload() {
        var disk: String? = null
        val store = ServerListStore({ disk }, { disk = it; true })
        val first = PairedServer.parse(payload(certificate()).toString())
        val second = PairedServer.parse(payload(certificate(), "https://192.168.1.10:8443", "Office").toString())
        store.save(first); store.save(second)
        val reloaded = ServerListStore({ disk }, { disk = it; true })
        assertEquals(listOf("Home", "Office"), reloaded.list().map { it.name })
        assertEquals(first.fingerprint, reloaded.list().first().fingerprint)
        reloaded.remove(first.url)
        assertEquals(listOf(second.url), store.list().map { it.url })
    }

    @Test fun duplicateImportUpdatesNameButCannotReplaceTrustedKey() {
        var disk: String? = null
        val store = ServerListStore({ disk }, { disk = it; true })
        val key = certificate()
        store.save(PairedServer.parse(payload(key).toString()))
        store.save(PairedServer.parse(payload(key, name = "Renamed").toString()))
        assertEquals(1, store.list().size)
        assertEquals("Renamed", store.list().single().name)
        val original = disk
        assertThrows(IllegalArgumentException::class.java) { store.save(PairedServer.parse(payload(certificate()).toString())) }
        assertEquals(original, disk)
    }

    @Test fun startupSelectionIsExclusiveOptionalAndSurvivesReloadAndReimport() {
        var disk: String? = null
        val store = ServerListStore({ disk }, { disk = it; true })
        val key = certificate()
        val first = PairedServer.parse(payload(key).toString())
        val second = PairedServer.parse(payload(certificate(), "https://192.168.1.10:8443", "Office").toString())
        store.save(first); store.save(second)
        assertNull(store.autoConnectUrl()) // Existing lists opt out by default.
        store.setAutoConnect(first.url)
        store.save(PairedServer.parse(payload(key, name = "Renamed").toString()))
        assertEquals(first.url, store.autoConnectUrl())
        store.setAutoConnect(second.url)
        val reloaded = ServerListStore({ disk }, { disk = it; true })
        assertEquals(second.url, reloaded.autoConnectUrl())
        val records = org.json.JSONArray(disk)
        assertFalse(records.getJSONObject(0).getBoolean("auto_connect"))
        assertTrue(records.getJSONObject(1).getBoolean("auto_connect"))
        reloaded.setAutoConnect(null)
        assertNull(store.autoConnectUrl())
        store.setAutoConnect(first.url)
        store.remove(second.url)
        assertEquals(first.url, store.autoConnectUrl())
        store.remove(first.url)
        store.save(first)
        assertNull(store.autoConnectUrl()) // Forgetting does not select a replacement.
    }

    @Test fun startupSelectionRejectsUnknownServerAndFailedWritesKeepPreviousChoice() {
        var disk: String? = null
        val store = ServerListStore({ disk }, { disk = it; true })
        val server = PairedServer.parse(payload(certificate()).toString())
        store.save(server); store.setAutoConnect(server.url)
        val original = disk
        assertThrows(IllegalArgumentException::class.java) { store.setAutoConnect("https://unknown.example") }
        assertThrows(IllegalStateException::class.java) {
            ServerListStore({ disk }, { false }).setAutoConnect(null)
        }
        assertEquals(original, disk)
        assertEquals(server.url, store.autoConnectUrl())
    }

    @Test fun legacyListsDoNotAutoconnectAndAmbiguousStartupChoicesAreRejected() {
        val first = payload(certificate())
        val second = payload(certificate(), "https://192.168.1.10:8443")
        val legacy = org.json.JSONArray().put(first).put(second)
        val store = ServerListStore({ legacy.toString() }, { false })
        assertEquals(2, store.list().size)
        assertNull(store.autoConnectUrl())
        first.put("auto_connect", true); second.put("auto_connect", true)
        assertThrows(IllegalArgumentException::class.java) { store.autoConnectUrl() }
    }

    @Test fun failedWritesAndCorruptStorageDoNotAppearSuccessful() {
        val server = PairedServer.parse(payload(certificate()).toString())
        assertThrows(IllegalStateException::class.java) { ServerListStore({ null }, { false }).save(server) }
        assertThrows(Exception::class.java) { ServerListStore({ "corrupted" }, { fail("Must not overwrite corrupt storage"); true }).save(server) }
    }

    @Test fun rejectsMalformedPairingAndUnsecuredUrls() {
        val cert = certificate()
        for (text in listOf("", "not json", "[]", "x".repeat(8193), payload(cert).put("version", 2).toString(),
            payload(cert).put("certificate", "invalid").toString(), payload(cert).put("name", "\nname").toString())) {
            assertThrows(IllegalArgumentException::class.java) { PairedServer.parse(text) }
        }
        for (url in listOf("http://127.0.0.1", "https://user:pass@example.com", "https://example.com/path",
            "https://example.com?query=1", "https://example.com#fragment", "https://0.0.0.0", "https://[::]")) {
            assertThrows(IllegalArgumentException::class.java) { PairedServer.parse(payload(cert, url).toString()) }
        }
    }

    private fun tlsRequest(served: HeldCertificate, trusted: HeldCertificate, expectSuccess: Boolean) {
        MockWebServer().use { server ->
            val tls = HandshakeCertificates.Builder().heldCertificate(served).build()
            server.useHttps(tls.sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val url = server.url("/").newBuilder().host("127.0.0.1").build().toString()
            val profile = PairedServer.parse(payload(trusted, url).toString())
            server.enqueue(MockResponse().setBody("verified"))
            val client = profile.client()
            try {
                val request = Request.Builder().url(url).build()
                if (expectSuccess) {
                    client.newCall(request).execute().use { assertEquals("verified", it.body!!.string()) }
                } else {
                    assertThrows(IOException::class.java) { client.newCall(request).execute().use { } }
                    assertEquals(0, server.requestCount)
                }
            } finally { shutdown(client) }
        }
    }

    @Test fun connectsToPairedSelfSignedServer() {
        val cert = certificate()
        tlsRequest(cert, cert, true)
    }

    @Test fun rejectsDifferentServerKeyBeforeSendingHttp() = tlsRequest(certificate(), certificate(), false)

    @Test fun stillRejectsWrongHostnameWithCorrectKey() {
        val cert = certificate("wrong.example")
        tlsRequest(cert, cert, false)
    }

    @Test fun rejectsExpiredServerCertificateEvenThoughItWasPaired() {
        val now = System.currentTimeMillis()
        val cert = HeldCertificate.Builder().ecdsa256().commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1")
            .validityInterval(now - TimeUnit.DAYS.toMillis(2), now - TimeUnit.DAYS.toMillis(1)).build()
        tlsRequest(cert, cert, false)
    }

    @Test fun renewedCertificateWithSameKeyWorksWithoutPairingAgain() {
        val now = System.currentTimeMillis()
        val original = HeldCertificate.Builder().ecdsa256().commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1")
            .validityInterval(now - TimeUnit.DAYS.toMillis(2), now - TimeUnit.DAYS.toMillis(1)).build()
        val renewed = HeldCertificate.Builder().keyPair(original.keyPair).commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1").build()
        tlsRequest(renewed, original, true)
    }
}
