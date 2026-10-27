package com.example.carriex.crypto.groups

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.x3dh.DoubleRatchetTest
import com.example.carriex.crypto.x3dh.X3dhCrypto
import com.example.carriex.crypto.x3dh.X3dhPrimitives
import com.example.carriex.model.Message
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** JCA primitives exercise the sender-key state machine; JNI is exercised on Android. */
class SenderKeysTest {
    private class Ops : X3dhPrimitives {
        private val aead = DoubleRatchetTest.JcaOps()
        private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        override fun random(size: Int) = aead.random(size)
        override fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().let {
            KeyPair(it.public.encoded.takeLast(32).toByteArray(), it.private.encoded.takeLast(32).toByteArray())
        }
        override fun dh(secret: ByteArray, publicKey: ByteArray): ByteArray = error("Not used by sender keys")
        override fun sign(secret: ByteArray, message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
            initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes("302e020100300506032b657004220420") + secret)))
            update(message); sign()
        }
        override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = Signature.getInstance("Ed25519").run {
            initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes("302a300506032b6570032100") + publicKey)))
            update(message); verify(signature)
        }
        override fun encrypt(plain: ByteArray, aad: ByteArray, key: ByteArray) = aead.encrypt(plain, aad, key)
        override fun decrypt(wire: ByteArray, aad: ByteArray, key: ByteArray) = aead.decrypt(wire, aad, key)
    }
    private val engine = SenderKeys(Ops())
    private fun device() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())
    private val admin = device()
    private val bob = device()
    private val carol = device()
    private fun group() = GroupState.create(admin, "Friends").next(admin, "invite", bob.userId).next(bob, "join", bob.userId)
    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Operation must reject invalid data", failed)
    }
    private fun message(group: GroupState, text: String) = Message(admin.userId, group.id, text.toByteArray(), Message.MessageType.DATA, sentAt = 1234567890123)

    @Test fun adminRenameIsSignedAndPreservesMembershipAndExistingSenderKeys() {
        val original = group()
        val own = engine.create(original, admin.userId)
        val receiving = engine.receiveDistribution(original, admin.userId, engine.distribution(own))
        val renamed = original.next(admin, "rename", name = "New shared name")
        GroupState.parse(renamed.json, original)
        assertEquals("New shared name", renamed.name)
        assertEquals(original.epoch, renamed.epoch)
        assertEquals(original.membershipHash, renamed.membershipHash)
        assertEquals(original.members, renamed.members)
        assertEquals(original.invited, renamed.invited)
        val plain = message(renamed, "after rename")
        val wire = engine.encrypt(renamed, own, plain).wire
        assertArrayEquals(plain.content, engine.decrypt(renamed, receiving, admin.userId, plain.id, wire).message.content)
        expectFailure { original.next(bob, "rename", name = "Not allowed") }
        expectFailure { original.next(admin, "rename", name = " ") }
        expectFailure { original.next(admin, "invite", carol.userId, name = "Hidden rename") }
        expectFailure { GroupState.parse(JSONObject(renamed.json.toString()).put("name", "Forged"), original) }
    }

    @Test fun joiningAfterHiddenRenameRequiresExactAdminAdmission() {
        val invitation = GroupState.create(admin, "Original name").next(admin, "invite", bob.userId)
        val renamed = invitation.next(admin, "rename", name = "Shared new name")
        val joined = renamed.next(bob, "join", bob.userId)
        val proposal = JSONObject(joined.json.toString()).also { it.remove("auth_key"); it.remove("signature") }
        val signed = GroupState.joinProposal(bob, proposal, invitation)
        assertEquals(joined.hash, signed.hash)
        expectFailure { GroupState.parse(signed.json, invitation) }
        GroupAdmission.verify(GroupAdmission.create(joined, admin), signed, admin.userId, bob.userId)
    }

    @Test fun admissionAnchorsExactJoinWithoutDisclosingEarlierEvents() {
        val created = GroupState.create(admin, "Private history")
        val first = created.next(admin, "invite", carol.userId).next(carol, "join", carol.userId)
        val left = first.next(carol, "leave", carol.userId)
        val invitation = left.next(admin, "invite", bob.userId)
        val joined = invitation.next(bob, "join", bob.userId)
        val proof = GroupAdmission.create(joined, admin)
        GroupAdmission.verify(proof, joined, admin.userId, bob.userId)
        assertFalse("The checkpoint contains no former member", proof.toString().contains(carol.userId))
        expectFailure { GroupState.parse(joined.json) } // It is not a genesis event.
        expectFailure { GroupAdmission.verify(JSONObject(proof.toString()).put("revision", joined.revision - 1), joined, admin.userId, bob.userId) }
        expectFailure { GroupAdmission.verify(proof, joined, carol.userId, bob.userId) }
        expectFailure { GroupAdmission.verify(proof, joined, admin.userId, carol.userId) }
        expectFailure { GroupAdmission.create(joined, bob) }
    }

    @Test fun joinProposalCannotReplaceAdminOrAuthorizeForgedRoster() {
        val invitation = GroupState.create(admin, "Friends").next(admin, "invite", bob.userId)
        val joined = invitation.next(bob, "join", bob.userId)
        fun proposal() = JSONObject(joined.json.toString()).also { it.remove("auth_key"); it.remove("signature") }
        val signed = GroupState.joinProposal(bob, proposal(), invitation)
        assertEquals(joined.hash, signed.hash)
        val proof = GroupAdmission.create(joined, admin)
        GroupAdmission.verify(proof, signed, admin.userId, bob.userId)
        expectFailure { GroupState.joinProposal(carol, proposal(), invitation) }
        expectFailure { GroupState.joinProposal(bob, proposal().put("admin", carol.userId), invitation) }
        val forged = GroupState.joinProposal(bob, proposal().put("members", org.json.JSONArray((joined.members + carol.userId).sorted())), invitation)
        expectFailure { GroupAdmission.verify(proof, forged, admin.userId, bob.userId) }
    }

    @Test fun declineProposalCanFollowUnseenChangesAndMustRemainPersonal() {
        val invitation = GroupState.create(admin, "Friends").next(admin, "invite", bob.userId)
        val latest = invitation.next(admin, "invite", carol.userId).next(carol, "join", carol.userId)
        val declined = latest.next(bob, "decline", bob.userId)
        val proposal = JSONObject(declined.json.toString()).also { it.remove("auth_key"); it.remove("signature") }
        assertEquals(declined.hash, GroupState.declineProposal(bob, proposal, invitation).hash)
        expectFailure { GroupState.joinProposal(bob, proposal, invitation) }
        expectFailure { GroupState.declineProposal(carol, proposal, invitation) }
    }

    @Test fun oneCiphertextDecryptsForEveryCurrentMemberWithDistinctPerMessageKeys() {
        val group = group().next(admin, "invite", carol.userId).next(carol, "join", carol.userId)
        var own = engine.create(group, admin.userId)
        val distribution = engine.distribution(own)
        assertFalse(distribution.has("secret"))
        var bobState = engine.receiveDistribution(group, admin.userId, distribution)
        var carolState = engine.receiveDistribution(group, admin.userId, distribution)
        val chains = mutableSetOf<String>()
        repeat(5) { index ->
            val plain = message(group, "hello $index")
            chains.add(own.getString("chain"))
            val encrypted = engine.encrypt(group, own, plain); own = encrypted.state
            val b = engine.decrypt(group, bobState, admin.userId, plain.id, encrypted.wire); bobState = b.state
            val c = engine.decrypt(group, carolState, admin.userId, plain.id, encrypted.wire); carolState = c.state
            assertArrayEquals(plain.content, b.message.content); assertArrayEquals(plain.content, c.message.content)
            assertEquals(plain.sentAt, b.message.sentAt)
        }
        assertEquals(5, chains.size)
        assertFalse(own.toString().contains(distribution.getString("chain")))
    }

    @Test fun outOfOrderMessagesDecryptOnceAndSignatureFailureNeverAdvancesState() {
        val group = group(); var own = engine.create(group, admin.userId)
        var receiving = engine.receiveDistribution(group, admin.userId, engine.distribution(own))
        val messages = (0..2).map { message(group, "message $it") }
        val wires = messages.map { val encrypted = engine.encrypt(group, own, it); own = encrypted.state; encrypted.wire }
        val original = receiving.toString()
        val forged = JSONObject(String(wires[0])).put("ciphertext", X3dhCrypto.encode(ByteArray(60))).toString().toByteArray()
        expectFailure { engine.decrypt(group, receiving, admin.userId, messages[0].id, forged) }
        assertEquals(original, receiving.toString())
        for(index in listOf(2, 0, 1)) {
            val decoded = engine.decrypt(group, receiving, admin.userId, messages[index].id, wires[index]); receiving = decoded.state
            assertArrayEquals(messages[index].content, decoded.message.content)
            expectFailure { engine.decrypt(group, receiving, admin.userId, messages[index].id, wires[index]) }
        }
        assertEquals(0, receiving.getJSONObject("skipped").length())
    }

    @Test fun membershipChangesRequireFreshChainAndSigningKeysAndRejectOldEpochs() {
        val before = group(); val old = engine.create(before, admin.userId)
        val oldDistribution = engine.distribution(old)
        val text = message(before, "old membership")
        val oldWire = engine.encrypt(before, old, text).wire
        val joined = before.next(admin, "invite", carol.userId).next(carol, "join", carol.userId)
        val fresh = engine.create(joined, admin.userId)
        assertNotEquals(old.getString("chain"), fresh.getString("chain"))
        assertNotEquals(old.getString("signing"), fresh.getString("signing"))
        expectFailure { engine.receiveDistribution(joined, admin.userId, oldDistribution) }
        val state = engine.receiveDistribution(joined, admin.userId, engine.distribution(fresh))
        expectFailure { engine.decrypt(joined, state, admin.userId, text.id, oldWire) }
        expectFailure { engine.encrypt(joined, old, message(joined, "reuse old keys")) }
        val removed = joined.next(admin, "remove", bob.userId)
        expectFailure { engine.create(removed, bob.userId) }
        expectFailure { engine.receiveDistribution(removed, admin.userId, engine.distribution(fresh)) }
        val left = removed.next(carol, "leave", carol.userId)
        assertEquals(removed.epoch + 1, left.epoch)
    }

    @Test fun groupAndSenderAndMessageIdAreAuthenticatedAndGapIsBounded() {
        val group = group(); var own = engine.create(group, admin.userId)
        val receiving = engine.receiveDistribution(group, admin.userId, engine.distribution(own))
        val plain = message(group, "bound context")
        val encrypted = engine.encrypt(group, own, plain); own = encrypted.state
        expectFailure { engine.decrypt(GroupState.create(admin, "Other"), receiving, admin.userId, plain.id, encrypted.wire) }
        expectFailure { engine.decrypt(group, receiving, bob.userId, plain.id, encrypted.wire) }
        expectFailure { engine.decrypt(group, receiving, admin.userId, "1".repeat(32), encrypted.wire) }
        repeat(SenderKeys.MAX_SKIP) { own = engine.encrypt(group, own, message(group, "skip")).state }
        val far = message(group, "too far"); val wire = engine.encrypt(group, own, far).wire
        expectFailure { engine.decrypt(group, receiving, admin.userId, far.id, wire) }
        assertEquals(0, receiving.getInt("counter"))
    }

    @Test fun onlyPermanentAdminCanInviteRemoveOrCloseAndSignedStateCannotBeRewritten() {
        val initial = GroupState.create(admin, "Membership")
        val invite = initial.next(admin, "invite", bob.userId)
        assertEquals(initial.epoch, invite.epoch)
        val joined = invite.next(bob, "join", bob.userId)
        assertEquals(initial.epoch + 1, joined.epoch)
        GroupState.parse(initial.json)
        GroupState.parse(invite.json, initial)
        GroupState.parse(joined.json, invite)
        expectFailure { joined.next(bob, "invite", carol.userId) }
        expectFailure { joined.next(bob, "remove", admin.userId) }
        expectFailure { joined.next(admin, "leave", admin.userId) }
        expectFailure { joined.next(bob, "close") }
        expectFailure { GroupState.parse(JSONObject(joined.json.toString()).put("admin", bob.userId), invite) }
        expectFailure { GroupState.parse(JSONObject(joined.json.toString()).put("epoch", invite.epoch), invite) }
        expectFailure { GroupState.parse(joined.json, initial) }
        val closed = joined.next(admin, "close")
        assertTrue(closed.closed); assertEquals(emptyList<String>(), closed.members)
    }
}
