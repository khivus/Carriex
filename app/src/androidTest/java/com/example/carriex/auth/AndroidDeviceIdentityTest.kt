package com.example.carriex.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.Signature

@RunWith(AndroidJUnit4::class)
class AndroidDeviceIdentityTest {
    @Test fun keystoreIdentityPersistsAndPrivateKeyCannotBeExported() {
        val first = AndroidDeviceIdentity.load()
        val reloaded = AndroidDeviceIdentity.load()
        assertEquals(first.userId, reloaded.userId)
        assertArrayEquals(first.publicKey, reloaded.publicKey)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = store.getEntry("carriex_device_auth_v1", null) as KeyStore.PrivateKeyEntry
        assertNull(entry.privateKey.encoded)
        val challenge = "a".repeat(43)
        val proof = reloaded.signChallenge(reloaded.userId, challenge, "https://chat.example")
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(entry.certificate.publicKey) }
        verifier.update("Carriex device authentication v1\nhttps://chat.example\n${first.userId}\n$challenge".toByteArray())
        assertTrue(verifier.verify(proof))
    }
}
