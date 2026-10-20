package com.example.carriex.crypto.groups

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.x3dh.X3dhCrypto
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** A history-free trust anchor, signed only after the admin verifies the join's chain. */
object GroupAdmission {
    private val fields = setOf("version", "group_id", "member", "revision", "state_hash", "admin", "auth_key", "signature")
    private fun bytes(proof: JSONObject) = ("Carriex group admission v1\n${proof.getString("group_id")}\n" +
        "${proof.getString("member")}\n${proof.getInt("revision")}\n${proof.getString("state_hash")}\n${proof.getString("admin")}\n")
        .toByteArray(Charsets.US_ASCII)

    fun create(joined: GroupState, admin: DeviceIdentity): JSONObject {
        require(joined.admin == admin.userId && joined.json.getString("action") == "join")
        return JSONObject().put("version", 1).put("group_id", joined.id).put("member", joined.json.getString("target"))
            .put("revision", joined.revision).put("state_hash", joined.hash).put("admin", admin.userId)
            .put("auth_key", X3dhCrypto.encode(admin.publicKey)).also {
                it.put("signature", X3dhCrypto.encode(admin.signGroupAdmission(bytes(it))))
            }
    }

    fun verify(proof: JSONObject, joined: GroupState, expectedAdmin: String, member: String) {
        require(proof.keys().asSequence().toSet() == fields && proof.get("version") == 1 && proof.get("revision") is Int)
        require(joined.admin == expectedAdmin && joined.json.getString("action") == "join" &&
            joined.json.getString("actor") == member && joined.json.getString("target") == member && member in joined.members)
        require(proof.getString("group_id") == joined.id && proof.getString("member") == member &&
            proof.getInt("revision") == joined.revision && proof.getString("state_hash") == joined.hash && proof.getString("admin") == expectedAdmin)
        val encoded = X3dhCrypto.decode(proof.getString("auth_key"))
        require(encoded.size <= 256 && X3dhCrypto.hex(X3dhCrypto.hash(encoded)) == expectedAdmin)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey
        require(key.params.curve.field.fieldSize == 256 && key.encoded.contentEquals(encoded))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(bytes(proof)); verify(X3dhCrypto.decode(proof.getString("signature")))
        }) { "Invalid admin admission checkpoint" }
    }
}
