package com.example.carriex.crypto.groups

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.messaging.ChatStore
import com.example.carriex.messaging.GroupConversation
import com.example.carriex.messaging.GroupMessagingService
import com.example.carriex.messaging.MessagingService
import com.example.carriex.transport.ServerTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Production JNI sender keys + encrypted phone storage + three identities + actual Flask relay. */
@RunWith(AndroidJUnit4::class)
class GroupMessagingAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val errors = LinkedBlockingQueue<String>()
    private inner class Node(url: String) : AutoCloseable {
        val device = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())
        val scope = UUID.randomUUID().toString()
        val database = "group-test-${UUID.randomUUID()}.db"
        val db = ChatStore(context, database)
        val transport = ServerTransport(url, DeviceAuthentication(url, device))
        val pairwise = MessagingService(device.userId, device, transport, db, scope, protectUnknown = false)
        var service = GroupMessagingService(device, transport, db, scope)
        @Volatile var groups = emptyList<GroupConversation>()
        fun start() {
            assertTrue(transport.testServerActive())
            pairwise.start()
            attach(); service.start()
        }
        fun attach() { service.onChanged = { groups = it }; service.onError = { errors.offer(it) } }
        fun group(id: String) = groups.firstOrNull { it.state.id == id }
        fun keyId(id: String) = db.x3dh("$scope/groups").get("own/$id")?.let { JSONObject(it).getString("key_id") }
        fun restartGroups() { service.close(); service = GroupMessagingService(device, transport, db, scope); attach(); service.start() }
        override fun close() { service.close(); pairwise.close(); transport.close(); db.close(); context.deleteDatabase(database) }
    }
    private fun await(label: String, nodes: List<Node>, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while(System.nanoTime() < deadline) {
            nodes.forEach { it.service.sync() }
            if(predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out: $label; errors=$errors; groups=" + nodes.map { node -> node.groups.map {
            "epoch=${it.state.epoch}, ready=${it.ready}, problem=${it.problem}"
        } })
    }
    private fun expectApiRejection(code: Int, action: () -> Unit) {
        try { action(); fail("Expected rejected group operation") }
        catch(e: ServerTransport.GroupApiException) { assertEquals(code, e.code) }
    }

    @Test fun adminRenameReachesEveryMemberAndPreexistingInviteCanJoinAfterRename() {
        val url = InstrumentationRegistry.getArguments().getString("x3dhServer").orEmpty()
        assumeTrue("Supply the actual test relay URL with x3dhServer", url.isNotEmpty())
        Node(url).use { alice -> Node(url).use { bob -> Node(url).use { carol ->
            val nodes = listOf(alice, bob, carol)
            nodes.forEach { it.start() }
            val created = LinkedBlockingQueue<String>()
            alice.service.create("Original shared name") { created.offer(it) }
            val id = created.poll(20, TimeUnit.SECONDS) ?: error("Group not created: $errors")
            alice.service.change(id, "invite", bob.device.userId)
            await("Bob invitation", nodes) { bob.group(id)?.state?.invited?.contains(bob.device.userId) == true }
            bob.service.change(id, "join", bob.device.userId)
            await("two members ready", nodes) { listOf(alice, bob).all { it.group(id)?.ready == true } }
            alice.service.change(id, "invite", carol.device.userId)
            await("Carol invited before rename", nodes) { carol.group(id)?.state?.invited?.contains(carol.device.userId) == true }
            val epoch = alice.group(id)!!.state.epoch
            val keys = listOf(alice.keyId(id), bob.keyId(id))
            alice.service.change(id, "rename", "Renamed for everyone")
            await("both members see the rename", nodes) { listOf(alice, bob).all { it.group(id)?.state?.name == "Renamed for everyone" } }
            assertEquals(epoch, bob.group(id)!!.state.epoch)
            assertEquals(keys, listOf(alice.keyId(id), bob.keyId(id)))
            carol.service.change(id, "join", carol.device.userId)
            await("old invitation joins renamed group with admission", nodes) { nodes.all {
                it.group(id)?.let { group -> group.ready && !group.joining && group.state.name == "Renamed for everyone" && group.state.members.size == 3 } == true
            } }
            assertEquals(listOf("join"), carol.group(id)!!.events.map { it.state.json.getString("action") })
            val sharedEpoch = alice.group(id)!!.state.epoch
            val sharedKeys = nodes.map { it.keyId(id) }
            alice.service.change(id, "rename", "Final shared name")
            await("all three receive final name", nodes) { nodes.all { it.group(id)?.state?.name == "Final shared name" } }
            assertEquals(sharedKeys, nodes.map { it.keyId(id) })
            assertTrue(nodes.all { it.group(id)!!.state.epoch == sharedEpoch })
            bob.restartGroups()
            await("renamed title survives restart", nodes) { bob.group(id)?.let { it.ready && it.state.name == "Final shared name" } == true }
            alice.service.send(id, "message after group rename")
            await("group encryption still works after renaming", nodes) { listOf(bob, carol).all { it.group(id)?.messages?.any { m -> String(m.content) == "message after group rename" } == true } }
            assertTrue("No unexpected protocol failures: $errors", errors.isEmpty())
        } } }
    }

    @Test fun privateJoinWaitsForAdminCheckpointAndRejoinHidesAbsence() {
        val url = InstrumentationRegistry.getArguments().getString("x3dhServer").orEmpty()
        assumeTrue("Supply the actual test relay URL with x3dhServer", url.isNotEmpty())
        Node(url).use { alice -> Node(url).use { bob -> Node(url).use { carol ->
            val nodes = listOf(alice, bob, carol)
            nodes.forEach { it.start() }
            val created = LinkedBlockingQueue<String>()
            alice.service.create("Private event history") { created.offer(it) }
            val id = created.poll(20, TimeUnit.SECONDS) ?: error("Group not created: $errors")
            alice.service.change(id, "invite", bob.device.userId)
            await("Bob invited", nodes) { bob.group(id)?.state?.invited?.contains(bob.device.userId) == true }
            bob.service.change(id, "join", bob.device.userId)
            await("Bob joins", nodes) { listOf(alice, bob).all { it.group(id)?.ready == true } }
            alice.service.change(id, "invite", carol.device.userId)
            await("Carol receives only her invitation", nodes) { carol.group(id)?.state?.invited?.contains(carol.device.userId) == true }
            assertTrue(carol.group(id)!!.events.isEmpty())
            assertEquals(0, carol.transport.groups(listOf(id, "events"), after = 0).getJSONArray("events").length())
            alice.service.close()
            bob.service.change(id, "leave", bob.device.userId)
            await("Bob leaves while admin is offline", listOf(bob, carol)) { bob.group(id)?.state?.members?.contains(bob.device.userId) == false }
            val leftRevision = bob.group(id)!!.state.revision
            carol.service.change(id, "join", carol.device.userId)
            await("Carol waits for an authenticated checkpoint", listOf(bob, carol)) { carol.group(id)?.joining == true }
            assertFalse(carol.group(id)!!.ready)
            assertNull("An unconfirmed roster cannot send keys", carol.keyId(id))
            val raw = carol.transport.groups(listOf(id, "events"), after = 0)
            assertEquals(1, raw.getJSONArray("events").length())
            assertEquals("join", raw.getJSONArray("events").getJSONObject(0).getString("action"))
            assertEquals(carol.device.userId, raw.getJSONArray("events").getJSONObject(0).getString("target"))
            assertEquals(0, raw.getJSONArray("admissions").length())
            alice.restartGroups()
            await("admin automatically confirms exact join", nodes) { carol.group(id)?.let { it.ready && !it.joining } == true }
            assertEquals(listOf("join"), carol.group(id)!!.events.map { it.state.json.getString("action") })
            val carolJoin = carol.group(id)!!.state.revision
            // Simulate a cache left by the version that downloaded pre-join activity.
            val forbidden = alice.db.x3dh("${alice.scope}/groups").get("event/$id/1")!!
            val local = carol.db.x3dh("${carol.scope}/groups")
            local.put("event/$id/1", forbidden)
            assertEquals(1, GroupMessagingService.cached(carol.db, carol.scope, carol.device.userId).single().events.size)
            assertNull("Old unauthorized cached events are erased", local.get("event/$id/1"))
            alice.service.change(id, "invite", bob.device.userId)
            await("Bob reinvited", nodes) { bob.group(id)?.state?.invited?.contains(bob.device.userId) == true }
            val reinvite = bob.group(id)!!.state.revision
            bob.service.change(id, "join", bob.device.userId)
            await("all current members ready after rejoin", nodes) { nodes.all { it.group(id)?.let { g -> g.ready && g.state.members.size == 3 } == true } }
            val bobEvents = bob.transport.groups(listOf(id, "events"), after = 0).getJSONArray("events")
            val revisions = (0 until bobEvents.length()).map { bobEvents.getJSONObject(it).getInt("revision") }
            assertTrue(leftRevision in revisions)
            assertFalse("No activity from Bob's absence", carolJoin in revisions)
            assertFalse("Reinvitation is control data, not historical activity", reinvite in revisions)
            assertTrue("No unexpected protocol failures: $errors", errors.isEmpty())
        } } }
    }

    @Test fun realSenderKeysRotateForEveryJoinRemovalAndLeaveAndSurviveRestart() {
        val url = InstrumentationRegistry.getArguments().getString("x3dhServer").orEmpty()
        assumeTrue("Supply the actual test relay URL with x3dhServer", url.isNotEmpty())
        Node(url).use { alice -> Node(url).use { bob -> Node(url).use { carol ->
            val nodes = listOf(alice, bob, carol)
            nodes.forEach { it.start() }
            val created = LinkedBlockingQueue<String>()
            alice.service.create("Sender key integration") { created.offer(it) }
            val id = created.poll(20, TimeUnit.SECONDS) ?: error("Group not created: $errors")
            alice.service.change(id, "invite", bob.device.userId)
            await("Bob's invitation", nodes) { bob.group(id)?.state?.invited?.contains(bob.device.userId) == true }
            assertNull("Invitation does not provide a sender key", bob.keyId(id))
            bob.service.change(id, "join", bob.device.userId)
            await("two ready members", nodes) { listOf(alice, bob).all { it.group(id)?.let { g -> g.state.epoch == 2 && g.ready } == true } }
            assertEquals(listOf("create", "invite", "join"), alice.group(id)!!.events.map { it.state.json.getString("action") })
            assertEquals(listOf("join"), bob.group(id)!!.events.map { it.state.json.getString("action") })
            val aliceKey2 = alice.keyId(id); val bobKey2 = bob.keyId(id)
            alice.service.send(id, "before Carol joined")
            await("Bob receives first group message", nodes) { bob.group(id)?.messages?.any { String(it.content) == "before Carol joined" } == true }
            val previous = alice.group(id)!!.messages.single { String(it.content) == "before Carol joined" }
            val oldWire = alice.db.savedWire(GroupMessagingService.historyScope(alice.scope, id), previous)!!
            bob.service.close()
            alice.service.send(id, "queued while Bob is offline")
            await("message queued for offline Bob", listOf(alice, carol)) { alice.group(id)?.messages?.any {
                String(it.content) == "queued while Bob is offline" && it.status != com.example.carriex.model.Message.DeliveryStatus.SENDING
            } == true }
            alice.service.change(id, "invite", carol.device.userId)
            await("Carol's invitation", nodes) { carol.group(id)?.state?.invited?.contains(carol.device.userId) == true }
            carol.service.change(id, "join", carol.device.userId)
            await("Carol joins while Bob is offline", listOf(alice, carol)) { carol.group(id)?.let { it.state.epoch == 3 && it.ready } == true }
            bob.restartGroups()
            await("all three rotate", nodes) { nodes.all { it.group(id)?.let { g -> g.state.epoch == 3 && g.ready } == true } }
            await("continuing member can decrypt queued previous-epoch message", nodes) { bob.group(id)?.messages?.any {
                String(it.content) == "queued while Bob is offline"
            } == true }
            assertTrue("Alice rotated", alice.keyId(id) != aliceKey2)
            assertTrue("Bob rotated", bob.keyId(id) != bobKey2)
            assertTrue("New member has no earlier history", carol.group(id)!!.messages.isEmpty())
            assertEquals("New member receives only its own join", listOf("join"), carol.group(id)!!.events.map { it.state.json.getString("action") })
            val raw = carol.transport.groups(listOf(id, "events"), after = 0).getJSONArray("events")
            assertEquals(1, raw.length())
            assertEquals(carol.device.userId, raw.getJSONObject(0).getString("target"))
            alice.service.send(id, "for all three")
            await("single sender ciphertext reaches both peers", nodes) { listOf(bob, carol).all { it.group(id)?.messages?.any { m -> String(m.content) == "for all three" } == true } }
            val bobKey3 = bob.keyId(id)
            assertEquals(3, bob.group(id)!!.events.size)
            bob.restartGroups()
            await("restart keeps exact sender key", nodes) { bob.group(id)?.ready == true && bob.keyId(id) == bobKey3 }
            assertEquals(3, bob.group(id)!!.events.size)
            // Existing installs have signed membership snapshots but no activity log.
            bob.service.close()
            bob.db.atomic { bob.db.x3dh("${bob.scope}/groups").let { store -> store.keys("event/$id/").forEach(store::remove) } }
            bob.restartGroups()
            await("upgrade backfills only entitled activity without duplicates", nodes) { bob.group(id)?.events?.size == 3 }
            carol.service.send(id, "reply after restart")
            await("restarted Bob and Alice decrypt", nodes) { listOf(alice, bob).all { it.group(id)?.messages?.any { m -> String(m.content) == "reply after restart" } == true } }
            val aliceKey3 = alice.keyId(id); val carolKey3 = carol.keyId(id)
            alice.service.change(id, "remove", bob.device.userId)
            await("Bob removed; remaining members rotate", nodes) {
                listOf(alice, carol).all { it.group(id)?.let { g -> g.state.epoch == 4 && g.ready } == true } &&
                    bob.group(id)?.state?.members?.contains(bob.device.userId) == false
            }
            assertTrue(alice.keyId(id) != aliceKey3); assertTrue(carol.keyId(id) != carolKey3)
            nodes.forEach { node ->
                assertEquals("Every participant, including the removed person, sees the removal", "remove", node.group(id)!!.events.last().state.json.getString("action"))
            }
            assertNull("Removed user erases its local sending key", bob.keyId(id))
            assertTrue(bob.db.x3dh("${bob.scope}/groups").keys("receive/$id/").isEmpty())
            expectApiRejection(403) { bob.transport.groups(listOf(id, "keys")) }
            expectApiRejection(403) { bob.transport.groups(listOf(id, "messages")) }
            expectApiRejection(409) { alice.transport.groups(listOf(id, "messages"), JSONObject().put("epoch", 2)
                .put("id", oldWire.id).put("wire", com.example.carriex.crypto.x3dh.X3dhCrypto.encode(oldWire.content))) }
            carol.service.send(id, "only current members")
            await("remaining members communicate", nodes) { alice.group(id)?.messages?.any { String(it.content) == "only current members" } == true }
            assertFalse(bob.group(id)!!.messages.any { String(it.content) == "only current members" })
            val beforeLeave = alice.keyId(id)
            carol.service.change(id, "leave", carol.device.userId)
            await("voluntary leave rotates remaining sender", nodes) { alice.group(id)?.let { it.state.epoch == 5 && it.ready } == true &&
                carol.group(id)?.state?.members?.contains(carol.device.userId) == false }
            assertTrue(alice.keyId(id) != beforeLeave); assertNull(carol.keyId(id))
            listOf(alice, carol).forEach { node ->
                assertEquals("leave", node.group(id)!!.events.last().state.json.getString("action"))
            }
            alice.service.change(id, "close")
            await("permanent admin closes group", nodes) { alice.group(id)?.state?.closed == true }
            assertNull(alice.keyId(id))
            assertTrue("No unexpected protocol failures: $errors", errors.isEmpty())
        } } }
    }
}
