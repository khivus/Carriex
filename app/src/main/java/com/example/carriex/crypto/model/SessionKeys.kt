package com.example.carriex.crypto.model

data class SessionKeys(
    val rx: ByteArray,
    val tx: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SessionKeys

        if (!rx.contentEquals(other.rx)) return false
        if (!tx.contentEquals(other.tx)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rx.contentHashCode()
        result = 31 * result + tx.contentHashCode()
        return result
    }
}