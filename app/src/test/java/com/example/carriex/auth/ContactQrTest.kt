package com.example.carriex.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContactQrTest {
    private val own = "a".repeat(64)
    private val friend = "b".repeat(64)

    @Test fun qrRoundTripKeepsFullIdentity() {
        val text = ContactQr.encode(friend)
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(640 * 640) { if (matrix[it % 640, it / 640]) 0xff000000.toInt() else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(640, 640, pixels)))).text
        assertEquals(friend, ContactQr.parse(decoded))
    }

    @Test fun verificationDistinguishesMatchMismatchOwnAndNewContact() {
        assertEquals(ContactQr.Result.MATCH, ContactQr.compare(friend, own, friend))
        assertEquals(ContactQr.Result.MISMATCH, ContactQr.compare("c".repeat(64), own, friend))
        assertEquals(ContactQr.Result.OWN_ID, ContactQr.compare(own, own, friend))
        assertEquals(ContactQr.Result.NEW_CONTACT, ContactQr.compare(friend, own, null))
    }

    @Test fun rejectsServerQrAndMalformedOrTruncatedIdentity() {
        val good = JSONObject(ContactQr.encode(friend))
        for (text in listOf("", friend, "x".repeat(1025), "{}", "[]",
            JSONObject(good.toString()).put("type", "carriex-server").toString(),
            JSONObject(good.toString()).put("version", 2).toString(),
            JSONObject(good.toString()).put("user_id", "b".repeat(63)).toString(),
            JSONObject(good.toString()).put("user_id", "B".repeat(64)).toString())) {
            assertThrows(IllegalArgumentException::class.java) { ContactQr.parse(text) }
        }
    }
}
