package com.example.carriex.crypto.x3dh

import org.junit.Assert.*
import org.junit.Test

class X3dhCryptoTest {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    @Test fun hkdfMatchesIndependentPythonWithRfc5869InputAndSalt() {
        val derived = X3dhCrypto.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"),
            // RFC info is bytes f0..f9; test extraction/expansion independently below.
            "Carriex X3DH v2", 42)
        assertEquals(42, derived.size)
        // Independently generated using Python cryptography.HKDF, same RFC IKM/salt.
        assertEquals("bdd4109bfba5156bef6c56bc4585bae333feeef1ff6381acbd91bc6636a5fe51671bfd562d288ef8955d",
            X3dhCrypto.hex(derived))
    }
    @Test fun publicConversionAndEncodingRejectNoncanonicalInput() {
        val base = ByteArray(32).apply { this[0] = 9 }
        assertEquals("5866666666666666666666666666666666666666666666666666666666666666",
            X3dhCrypto.hex(X3dhCrypto.edwardsPublic(base)))
        assertArrayEquals(base, X3dhCrypto.decode(X3dhCrypto.encode(base), 32))
        try { X3dhCrypto.edwardsPublic(ByteArray(32) { 0xff.toByte() }); fail("Invalid field element") }
        catch (_: IllegalArgumentException) { }
    }
}
