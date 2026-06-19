package com.example.carriex.crypto.libsodium

import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.model.SessionKeys

object NativeCrypto {
    init {
        System.loadLibrary("carriex_jni")
    }

    external fun secretboxEasy(input: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    external fun secretboxOpenEasy(input: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    external fun randombytes(size: Int): ByteArray

    external fun aeadXchacha20poly1305Encrypt(m: ByteArray, ad: ByteArray?, npub: ByteArray, k: ByteArray): ByteArray

    external fun aeadXchacha20poly1305Decrypt(c: ByteArray, ad: ByteArray?, npub: ByteArray, k: ByteArray): ByteArray?

    external fun kxKeypair(): KeyPair

    external fun kxClientSessionKeys(clientPk: ByteArray, clientSk: ByteArray, serverPk: ByteArray): SessionKeys?

    external fun kxServerSessionKeys(serverPk: ByteArray, serverSk: ByteArray, clientPk: ByteArray): SessionKeys?

    external fun boxSeal(input: ByteArray, pk: ByteArray): ByteArray

    external fun boxSealOpen(input: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray?
}