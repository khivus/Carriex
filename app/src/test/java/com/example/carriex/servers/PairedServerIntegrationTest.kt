package com.example.carriex.servers

import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.model.Message
import com.example.carriex.transport.ServerTransport
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Uses the shipped Python launcher, generated QR image and Kotlin TLS client. */
class PairedServerIntegrationTest {
    @Test fun generatedQrAndPastedTextPairAndExchangeMessagesOverTls() {
        val python = System.getenv("CARRIEX_TEST_PYTHON")
        val root = System.getenv("CARRIEX_TEST_SERVER_ROOT")
        assumeTrue("Set CARRIEX_TEST_PYTHON and CARRIEX_TEST_SERVER_ROOT", python != null && root != null)
        val directory = Files.createTempDirectory("carriex-paired-integration-").toFile()
        val identityDirectory = File(directory, "identity")
        val log = File(directory, "server.log")
        val port = ServerSocket(0).use { it.localPort }
        val url = "https://127.0.0.1:$port"
        var process: Process? = null
        try {
            val command = listOf(python!!, "-m", "src.pairing", "--data-dir", identityDirectory.path,
                "--address", url, "--bind", "127.0.0.1", "--port", port.toString())
            val generate = ProcessBuilder(command + "--pair-only").directory(File(root!!))
                .redirectErrorStream(true).redirectOutput(log).start()
            assertTrue(generate.waitFor(20, TimeUnit.SECONDS))
            assertEquals(log.readText(), 0, generate.exitValue())
            val text = File(identityDirectory, "pairing.json").readText()
            // java.desktop exists on the test JVM, but is absent from Android's compile API.
            val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java)
                .invoke(null, File(identityDirectory, "pairing.png"))
            val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
            val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
            val intType = Int::class.javaPrimitiveType!!
            val pixels = image.javaClass.getMethod("getRGB", intType, intType, intType, intType,
                IntArray::class.java, intType, intType).invoke(image, 0, 0, width, height, null, 0, width) as IntArray
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
            // The exact generated PNG has no camera perspective or surrounding scene.
            val decoded = MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
            assertEquals(text, decoded)
            val fromQr = PairedServer.parse(decoded)
            val fromText = PairedServer.parse(text)
            assertEquals(fromQr.fingerprint, fromText.fingerprint)
            process = ProcessBuilder(command).directory(File(root))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            val healthClient = fromQr.client()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var ready = false
                while (System.nanoTime() < deadline && process.isAlive) {
                    try {
                        healthClient.newCall(Request.Builder().url("$url/health").build()).execute().use {
                            if (it.code == 200) ready = true
                        }
                    } catch (_: Exception) { }
                    if (ready) break
                    Thread.sleep(50)
                }
                assertTrue(log.readText(), ready)
            } finally { healthClient.dispatcher.executorService.shutdown(); healthClient.connectionPool.evictAll() }
            fun device() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair())
            val alice = device()
            val bob = device()
            val sender = ServerTransport(url, DeviceAuthentication(url, alice, fromQr.client()), client = fromQr.client())
            val receiver = ServerTransport(url, DeviceAuthentication(url, bob, fromText.client()), client = fromText.client())
            val received = LinkedBlockingQueue<Message>()
            try {
                assertTrue(sender.testServerActive())
                assertTrue(receiver.testServerActive())
                receiver.connectWebSocket(bob.userId) { received.offer(it) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (receiver.connectionStatus != ServerTransport.ConnectionStatus.CONNECTED && System.nanoTime() < deadline) Thread.sleep(20)
                assertEquals(ServerTransport.ConnectionStatus.CONNECTED, receiver.connectionStatus)
                val message = Message(alice.userId, bob.userId, "paired TLS".toByteArray(), Message.MessageType.DATA)
                assertTrue(sender.sendMessage(message))
                val delivered = received.poll(5, TimeUnit.SECONDS)!!
                assertEquals(alice.userId, delivered.from)
                assertArrayEquals(message.content, delivered.content)
            } finally { sender.close(); receiver.close() }
        } finally {
            process?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS)
            // Only this test's newly created temporary directory is removed.
            directory.deleteRecursively()
        }
    }
}
