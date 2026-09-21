package com.example.carriex.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

object AndroidDeviceIdentity {
    private const val ALIAS = "carriex_device_auth_v1"

    /** Separate from message-encryption keys; never exported or backed up. */
    @Synchronized
    fun load(): DeviceIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            if (Build.VERSION.SDK_INT >= 28) {
                try { generate(true) }
                catch (_: StrongBoxUnavailableException) { generate(false) }
            } else {
                generate(false)
            }
        }
        // Fail visibly if an existing entry is unusable; do not silently replace it.
        val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return DeviceIdentity(KeyPair(entry.certificate.publicKey, entry.privateKey))
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // No biometric prompt per request: reconnect must work unattended.
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(strongBox)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec.build())
        }.generateKeyPair()
    }
}
