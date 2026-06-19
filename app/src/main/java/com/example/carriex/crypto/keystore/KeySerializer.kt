package com.example.carriex.crypto.keystore

import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.model.SessionKeys
import java.nio.ByteBuffer

/**
 * Utility to serialize and deserialize KeyPair and SessionKeys objects into ByteArrays.
 * 
 * Used for storing encrypted keys in internal storage.
 */
object KeySerializer {
    fun serialize(keyPair: KeyPair): ByteArray {
        val buffer = ByteBuffer.allocate(4 + keyPair.publicKey.size + 4 + keyPair.secretKey.size)
        buffer.putInt(keyPair.publicKey.size)
        buffer.put(keyPair.publicKey)
        buffer.putInt(keyPair.secretKey.size)
        buffer.put(keyPair.secretKey)
        return buffer.array()
    }

    fun deserialize(data: ByteArray): KeyPair {
        val buffer = ByteBuffer.wrap(data)
        val pkSize = buffer.getInt()
        val pk = ByteArray(pkSize)
        buffer.get(pk)
        val skSize = buffer.getInt()
        val sk = ByteArray(skSize)
        buffer.get(sk)
        return KeyPair(pk, sk)
    }

    fun serializeSessionKeys(keys: SessionKeys): ByteArray {
        val buffer = ByteBuffer.allocate(4 + keys.rx.size + 4 + keys.tx.size)
        buffer.putInt(keys.rx.size)
        buffer.put(keys.rx)
        buffer.putInt(keys.tx.size)
        buffer.put(keys.tx)
        return buffer.array()
    }

    fun deserializeSessionKeys(data: ByteArray): SessionKeys {
        val buffer = ByteBuffer.wrap(data)
        val rxSize = buffer.getInt()
        val rx = ByteArray(rxSize)
        buffer.get(rx)
        val txSize = buffer.getInt()
        val tx = ByteArray(txSize)
        buffer.get(tx)
        return SessionKeys(rx, tx)
    }
}
