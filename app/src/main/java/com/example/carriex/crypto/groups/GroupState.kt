package com.example.carriex.crypto.groups

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.x3dh.X3dhCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** A signed, hash-linked membership transition. The founding admin is permanent. */
class GroupState private constructor(val json: JSONObject) {
    val id get() = json.getString("id")
    val name get() = json.getString("name")
    val admin get() = json.getString("admin")
    val revision get() = json.getInt("revision")
    val epoch get() = json.getInt("epoch")
    val closed get() = json.getBoolean("closed")
    val members get() = strings(json.getJSONArray("members"))
    val invited get() = strings(json.getJSONArray("invited"))
    val hash get() = X3dhCrypto.hex(X3dhCrypto.hash(signingBytes()))
    val membershipHash get() = X3dhCrypto.hex(X3dhCrypto.hash(
        "Carriex group membership v1\n$id\n$epoch\n$admin\n${members.joinToString(",")}\n".toByteArray(Charsets.US_ASCII)))

    fun signingBytes(): ByteArray = ("Carriex group state v1\n$id\n$revision\n$epoch\n${json.getString("previous")}\n" +
        "$admin\n${X3dhCrypto.encode(name.toByteArray(Charsets.UTF_8))}\n${members.joinToString(",")}\n${invited.joinToString(",")}\n" +
        "${json.getString("actor")}\n${json.getString("action")}\n${json.getString("target")}\n${if(closed) 1 else 0}\n").toByteArray(Charsets.US_ASCII)

    fun next(device: DeviceIdentity, action: String, target: String = ""): GroupState {
        val people = members.toMutableSet(); val invitations = invited.toMutableSet()
        val actor = device.userId
        var closing = false
        when {
            action == "invite" && actor == admin && validUser(target) && target !in people && target !in invitations -> invitations.add(target)
            action == "join" && actor == target && actor in invitations -> { invitations.remove(actor); people.add(actor) }
            action == "remove" && actor == admin && target != admin && (target in people || target in invitations) -> { people.remove(target); invitations.remove(target) }
            action == "leave" && actor == target && actor in people && actor != admin -> people.remove(actor)
            action == "decline" && actor == target && actor in invitations -> invitations.remove(actor)
            action == "close" && actor == admin && target.isEmpty() -> { people.clear(); invitations.clear(); closing = true }
            else -> error("Only the permanent admin can invite or remove people. Members can leave their own group.")
        }
        require(!closed && revision < 4096 && people.size + invitations.size <= 32) { "Group is closed or its membership limit was reached" }
        val changed = JSONObject(json.toString()).put("revision", revision + 1).put("previous", hash)
            .put("epoch", epoch + if(people.sorted() != members) 1 else 0)
            .put("members", JSONArray(people.sorted())).put("invited", JSONArray(invitations.sorted()))
            .put("actor", actor).put("action", action).put("target", target).put("closed", closing)
        return sign(changed, device).also { it.checkTransition(this) }
    }

    private fun checkTransition(old: GroupState?) {
        if(old == null) {
            require(revision == 1 && epoch == 1 && json.getString("previous") == "0".repeat(64) &&
                json.getString("action") == "create" && json.getString("actor") == admin && members == listOf(admin) &&
                invited.isEmpty() && !closed && json.getString("target").isEmpty()) { "Invalid group creation" }
            return
        }
        require(!old.closed && revision == old.revision + 1 && json.getString("previous") == old.hash) { "Group state rollback or missing membership transition" }
        require(id == old.id && admin == old.admin && name == old.name) { "Group admin cannot be transferred" }
        val actor = json.getString("actor"); val target = json.getString("target")
        val people = old.members.toMutableSet(); val invitations = old.invited.toMutableSet()
        var closing = false
        when {
            json.getString("action") == "invite" && actor == admin && validUser(target) && target !in people && target !in invitations -> invitations.add(target)
            json.getString("action") == "join" && actor == target && actor in invitations -> { invitations.remove(actor); people.add(actor) }
            json.getString("action") == "remove" && actor == admin && target != admin && (target in people || target in invitations) -> { people.remove(target); invitations.remove(target) }
            json.getString("action") == "leave" && actor == target && actor in people && actor != admin -> people.remove(actor)
            json.getString("action") == "decline" && actor == target && actor in invitations -> invitations.remove(actor)
            json.getString("action") == "close" && actor == admin && target.isEmpty() -> { people.clear(); invitations.clear(); closing = true }
            else -> error("Unauthorized group membership change")
        }
        require(members == people.sorted() && invited == invitations.sorted() && closed == closing &&
            epoch == old.epoch + if(people.sorted() != old.members) 1 else 0) { "Membership change must rotate the sender-key epoch" }
    }

    companion object {
        private val fields = setOf("version", "id", "revision", "epoch", "previous", "admin", "name", "members",
            "invited", "actor", "action", "target", "closed", "auth_key", "signature")
        fun validUser(value: String) = value.matches(Regex("[0-9a-f]{64}"))
        private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
        private fun sign(json: JSONObject, device: DeviceIdentity): GroupState {
            json.put("auth_key", X3dhCrypto.encode(device.publicKey))
            val state = GroupState(json)
            json.put("signature", X3dhCrypto.encode(device.signGroupState(state.signingBytes())))
            return parse(json, verifyChain = false)
        }
        fun create(device: DeviceIdentity, name: String, id: String = java.util.UUID.randomUUID().toString().replace("-", "")): GroupState =
            sign(JSONObject().put("version", 1).put("id", id).put("name", name.trim()).put("admin", device.userId)
                .put("revision", 1).put("epoch", 1).put("previous", "0".repeat(64)).put("members", JSONArray(listOf(device.userId)))
                .put("invited", JSONArray()).put("actor", device.userId).put("action", "create").put("target", "").put("closed", false), device)
        /** The proposal is not a trusted roster until the admin confirms this exact signed join. */
        fun joinProposal(device: DeviceIdentity, proposal: JSONObject, invitation: GroupState) =
            invitationProposal(device, proposal, invitation, "join")
        fun declineProposal(device: DeviceIdentity, proposal: JSONObject, invitation: GroupState) =
            invitationProposal(device, proposal, invitation, "decline")
        private fun invitationProposal(device: DeviceIdentity, proposal: JSONObject, invitation: GroupState, action: String): GroupState {
            require(invitation.json.getString("actor") == invitation.admin && invitation.json.getString("action") == "invite" &&
                invitation.json.getString("target") == device.userId && device.userId in invitation.invited)
            require(proposal.keys().asSequence().toSet() == fields - setOf("auth_key", "signature"))
            val joined = sign(JSONObject(proposal.toString()), device)
            require(joined.id == invitation.id && joined.admin == invitation.admin && joined.name == invitation.name &&
                joined.revision > invitation.revision && joined.epoch >= invitation.epoch + (if(action == "join") 1 else 0) && !joined.closed &&
                joined.json.getString("action") == action && joined.json.getString("actor") == device.userId &&
                joined.json.getString("target") == device.userId && (device.userId in joined.members) == (action == "join") &&
                device.userId !in joined.invited && joined.admin in joined.members)
            return joined
        }
        fun parse(json: JSONObject, previous: GroupState? = null, verifyChain: Boolean = true): GroupState {
            val state = GroupState(JSONObject(json.toString()))
            require(json.keys().asSequence().toSet() == fields && json.get("version") == 1 && state.id.matches(Regex("[0-9a-f]{32}")))
            require(json.get("revision") is Int && state.revision in 1..4096 && json.get("epoch") is Int && state.epoch in 1..4096)
            require(validUser(state.admin) && validUser(json.getString("actor")) && validUser(json.getString("previous")))
            require(state.name.isNotBlank() && state.name.codePointCount(0, state.name.length) <= 80 && state.name.none { it.code < 32 } && json.get("closed") is Boolean)
            listOf(state.members, state.invited).forEach { list -> require(list.all(::validUser) && list == list.distinct().sorted()) }
            require(state.members.size + state.invited.size <= 32 && state.members.intersect(state.invited.toSet()).isEmpty())
            val bytes = X3dhCrypto.decode(json.getString("auth_key"))
            require(bytes.size <= 256 && X3dhCrypto.hex(X3dhCrypto.hash(bytes)) == json.getString("actor"))
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
            require(key.params.curve.field.fieldSize == 256 && key.encoded.contentEquals(bytes))
            require(Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(state.signingBytes()); verify(X3dhCrypto.decode(json.getString("signature")))
            }) { "Invalid group membership signature" }
            if(verifyChain) state.checkTransition(previous)
            return state
        }
    }
}
