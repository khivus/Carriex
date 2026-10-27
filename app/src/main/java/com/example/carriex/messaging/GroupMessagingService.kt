package com.example.carriex.messaging

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.groups.GroupState
import com.example.carriex.crypto.groups.GroupAdmission
import com.example.carriex.crypto.groups.SenderKeys
import com.example.carriex.crypto.x3dh.X3dhCrypto
import com.example.carriex.crypto.x3dh.X3dhProtocol
import com.example.carriex.model.Message
import com.example.carriex.transport.ServerTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class GroupEvent(val state: GroupState, val afterMessage: String?)
data class GroupConversation(val state: GroupState, val messages: List<Message>, val ready: Boolean,
    val problem: String? = null, val events: List<GroupEvent> = emptyList(), val joining: Boolean = false)

/** All membership, sender-key rotation, fan-out and durable retries run off the UI thread. */
class GroupMessagingService(
    private val device: DeviceIdentity,
    private val transport: ServerTransport,
    private val db: ChatStore,
    private val scope: String,
    allowFallback: () -> Boolean = { false },
) {
    companion object {
        fun historyScope(server: String, group: String) = "$server/group/$group"
        fun clearCachedHistory(db: ChatStore, scope: String, id: String) = db.atomic {
            val saved = db.x3dh("$scope/groups")
            val revision = saved.get("state/$id")?.let { JSONObject(it).getInt("revision") } ?: 0
            db.clearHistory(historyScope(scope, id))
            saved.keys("event/$id/").forEach(saved::remove)
            saved.put("history-clear/$id", revision.toString())
            saved.remove("clear-on-leave/$id")
        }
        private fun events(db: ChatStore, scope: String, id: String, owner: String): List<GroupEvent> {
            val saved = db.x3dh("$scope/groups")
            val cutoff = saved.get("history-clear/$id")?.toIntOrNull() ?: 0
            val all = saved.keys("event/$id/").mapNotNull { key ->
                val event = JSONObject(saved.get(key) ?: return@mapNotNull null)
                if(event.getJSONObject("state").getInt("revision") <= cutoff) return@mapNotNull null
                GroupEvent(GroupState.parse(event.getJSONObject("state"), verifyChain = false),
                    event.optString("after").takeIf { it.isNotEmpty() })
            }.sortedBy { it.state.revision }
            val policy = saved.get("periods/$id")?.let(::JSONArray)
            var member = false
            return all.filter { event ->
                val wasMember = member
                member = owner in event.state.members
                val visible = if(policy == null) member || wasMember else (0 until policy.length()).any {
                    val period = policy.getJSONObject(it)
                    event.state.revision >= period.getInt("first_revision") &&
                        (period.isNull("last_revision") || event.state.revision <= period.getInt("last_revision"))
                }
                // Also clean up history downloaded by earlier app versions.
                if(!visible) saved.remove("event/$id/${event.state.revision}")
                visible
            }
        }
        fun cached(db: ChatStore, scope: String, owner: String): List<GroupConversation> {
            val saved = db.x3dh("$scope/groups")
            return saved.keys("state/").map { key ->
                val state = GroupState.parse(JSONObject(saved.get(key)!!), verifyChain = false)
                if(saved.get("clear-on-leave/${state.id}") == "true" && (state.closed || owner !in state.members))
                    clearCachedHistory(db, scope, state.id)
                GroupConversation(state, db.history(historyScope(scope, state.id)), false,
                    events = events(db, scope, state.id, owner), joining = saved.get("waiting/${state.id}") == "true")
            }
        }
    }
    var onChanged: ((List<GroupConversation>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val syncing = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var started = false
    private var supported = true
    private val saved = db.x3dh("$scope/groups")
    private val pairwise = X3dhProtocol(device, db.x3dh(scope), allowWithoutOneTimeKey = allowFallback)
    private val senderKeys = SenderKeys()
    private fun state(id: String) = saved.get("state/$id")?.let { GroupState.parse(JSONObject(it), verifyChain = false) }
    private inner class PendingAction(val cancelled: () -> Unit, val action: () -> Unit) : Runnable {
        override fun run() {
            if(closed) cancelled()
            else try { action() } catch(e: Exception) { if(!closed) onError?.invoke(e.message ?: "Group operation failed") }
        }
    }
    @Synchronized private fun enqueue(cancelled: () -> Unit = {}, action: () -> Unit) {
        if(closed) cancelled() else worker.execute(PendingAction(cancelled, action))
    }
    @Synchronized fun close() {
        closed = true
        worker.shutdownNow().forEach { (it as PendingAction).cancelled() }
        onChanged = null; onError = null
    }
    fun sync() {
        if(!started || closed) return
        if(!syncing.compareAndSet(false, true)) return
        enqueue { try { synchronize() } finally { syncing.set(false) } }
    }
    fun start() { started = true; sync() }
    fun create(name: String, done: (String) -> Unit) = enqueue {
        require(started) { "Wait for the server connection to finish" }
        val created = GroupState.create(device, name)
        transport.groups(listOf("state"), created.json)
        db.atomic {
            saved.put("state/${created.id}", created.json.toString())
            saved.put("epoch/${created.id}/${created.epoch}", created.json.toString())
            recordEvent(created, null)
        }
        synchronize(); done(created.id)
    }
    fun clearHistory(id: String) = enqueue {
        val group = state(id) ?: error("Group is unavailable")
        require(group.closed || device.userId !in group.members) { "Leave this group before deleting its saved history." }
        clearCachedHistory(db, scope, id); emit()
    }

    fun change(id: String, action: String, target: String = "", deleteHistory: Boolean = false, done: () -> Unit = {}) = enqueue(done) {
        try {
            require(started) { "Wait for the server connection to finish" }
            synchronize(membershipOnly = true)
            val current = state(id) ?: error("Group is unavailable")
            if(deleteHistory && action in listOf("leave", "close")) saved.put("clear-on-leave/$id", "true")
            val invitation = saved.get("invitation/$id")?.let { GroupState.parse(JSONObject(it), verifyChain = false) } ?: current
            val next = when(action) {
                "rename" -> current.next(device, "rename", name = target.trim())
                "join" -> GroupState.joinProposal(device, transport.groups(listOf(id, "join-proposal")).getJSONObject("state"), invitation)
                "decline" -> GroupState.declineProposal(device, transport.groups(listOf(id, "decline-proposal")).getJSONObject("state"), invitation)
                else -> current.next(device, action, target)
            }
            transport.groups(listOf("state"), next.json)
            if(action == "join") saved.put("waiting/$id", "true")
            // Fetch the accepted transition instead of treating a request as confirmation.
            synchronize()
        } finally { done() }
    }
    fun send(id: String, text: String, queued: () -> Unit = {}) = send(id,
        Message(device.userId, id, text.toByteArray(Charsets.UTF_8), Message.MessageType.DATA,
            status = Message.DeliveryStatus.SENDING, sentAt = System.currentTimeMillis()), null) { if(it) queued() }

    fun send(id: String, message: Message, expectedEpoch: Int?, queued: (Boolean) -> Unit) = enqueue({ queued(false) }) {
        var persisted = false
        try {
            require(started) { "Wait for the server connection to finish" }
            persistOutgoing(id, message, expectedEpoch)
            persisted = true
            queued(true)
            emit()
            // Refresh membership before encryption. The durable queue is already visible,
            // and a membership change fails it rather than silently changing its audience.
            flushOutgoing(id)
        } finally {
            if(!persisted) queued(false)
            emit()
        }
    }

    /** Disk only. The caller runs this off-main, independently of slow group HTTP work. */
    fun persistOutgoing(id: String, message: Message, expectedEpoch: Int?) = db.atomic {
        require(message.from == device.userId && message.to == id && message.type == Message.MessageType.DATA)
        MessageLimits.requirePlaintext(message.content)
        val group = state(id) ?: error("Group is unavailable")
        require(device.userId in group.members && !group.closed) { "You are no longer a member of this group" }
        require(expectedEpoch == null || expectedEpoch == group.epoch) { "Group membership changed. Review the group before sending again." }
        db.addOutgoing(historyScope(scope, id), message)
        saved.put("outgoing-epoch/$id/${message.id}", group.epoch.toString())
    }

    fun flush(id: String) = enqueue {
        try { flushOutgoing(id) } finally { emit() }
    }

    private fun flushOutgoing(id: String) {
        synchronize(membershipOnly = true)
        val current = state(id) ?: error("Group is unavailable")
        if(device.userId in current.members && !current.closed) {
            ensureSenderKey(current)
            sendPending(current)
        }
    }

    private fun recordEvent(event: GroupState, afterMessage: String?) {
        if(event.revision <= (saved.get("history-clear/${event.id}")?.toIntOrNull() ?: 0)) return
        val key = "event/${event.id}/${event.revision}"
        if(saved.get(key) == null) saved.put(key, JSONObject().put("state", event.json)
            .put("after", afterMessage ?: "").toString())
    }

    private fun acceptState(updated: GroupState, activity: Boolean = true) {
        val id = updated.id
        val old = state(id)
        val anchor = db.history(historyScope(scope, id)).lastOrNull()?.id
        db.atomic {
            if(activity) recordEvent(updated, anchor)
            val epochKey = "epoch/$id/${updated.epoch}"
            val epoch = saved.get(epochKey)?.let { GroupState.parse(JSONObject(it), verifyChain = false) }
            if(activity && (epoch == null || epoch.revision < updated.revision)) saved.put(epochKey, updated.json.toString())
            if(old != null && old.revision >= updated.revision) return@atomic
            saved.put("state/$id", updated.json.toString())
            if(device.userId in updated.members) saved.remove("waiting/$id")
            if(old == null || updated.epoch != old.epoch || device.userId !in updated.members) {
                saved.remove("own/$id"); saved.remove("batch/$id"); saved.remove("ready/$id")
                if(old != null && updated.epoch != old.epoch) {
                    val lifetime = db.x3dh(scope).get("local/ttl")?.toLongOrNull() ?: 30L * 86_400_000
                    saved.put("retired/$id/${old.epoch}", (System.currentTimeMillis() + lifetime).toString())
                    db.pending(historyScope(scope, id)).forEach {
                        db.mark(historyScope(scope, id), it.first.id, Message.DeliveryStatus.FAILED)
                        saved.remove("outgoing-epoch/$id/${it.first.id}")
                    }
                }
                if(device.userId !in updated.members || updated.closed) {
                    saved.keys("receive/$id/").forEach(saved::remove)
                    saved.keys("packet/$id/").forEach(saved::remove)
                }
            }
        }
    }

    private fun sameGroup(a: GroupState, b: GroupState) {
        require(a.id == b.id && a.admin == b.admin) { "Group identity changed" }
    }

    private fun synchronizeMembership(id: String, last: Int) {
        val original = state(id)
        require(last >= (original?.revision ?: 0)) { "Relay rolled back group membership" }
        if(original != null && last == original.revision && saved.get("history-v2/$id") == "true" &&
            saved.get("event/$id/$last") != null) return
        // Retain only activity this device was entitled to see, including on upgrades.
        val known = events(db, scope, id, device.userId).associate { it.state.revision to it.state }
        val backfill = saved.get("history-v2/$id") != "true" ||
            (original != null && device.userId in original.members && known[original.revision] == null)
        var cursor = if(backfill) 0 else original?.revision ?: 0
        var previous: GroupState? = null
        var waiting = false
        do {
            val response = transport.groups(listOf(id, "events"), after = cursor)
            require(response.optInt("protocol") == 2) { "Update the relay for private group history" }
            val ranges = response.getJSONArray("periods")
            saved.put("periods/$id", ranges.toString())
            fun eligible(revision: Int) = (0 until ranges.length()).any {
                val period = ranges.getJSONObject(it)
                revision >= period.getInt("first_revision") &&
                    (period.isNull("last_revision") || revision <= period.getInt("last_revision"))
            }
            saved.keys("event/$id/").filter { !eligible(it.substringAfterLast('/').toInt()) }.forEach(saved::remove)
            saved.keys("epoch/$id/").forEach { key ->
                val snapshot = GroupState.parse(JSONObject(saved.get(key)!!), verifyChain = false)
                if(!eligible(snapshot.revision)) saved.remove(key)
            }
            val invitation = response.optJSONObject("invitation")?.let { value ->
                GroupState.parse(value, verifyChain = false).also {
                    require(it.id == id && it.json.getString("actor") == it.admin && it.json.getString("action") == "invite" &&
                        it.json.getString("target") == device.userId && device.userId in it.invited)
                    original?.let { old -> sameGroup(old, it) }
                    saved.put("invitation/$id", it.json.toString())
                    if(state(id) == null || (state(id)!!.revision < it.revision && device.userId !in state(id)!!.members))
                        acceptState(it, activity = false)
                }
            }
            val anchor = original ?: invitation
            val batch = response.getJSONArray("events")
            require(batch.length() <= 100)
            val admissions = response.getJSONArray("admissions")
            for(i in 0 until batch.length()) {
                val value = batch.getJSONObject(i)
                val candidate = GroupState.parse(value, verifyChain = false)
                require(candidate.id == id && candidate.revision > cursor && eligible(candidate.revision))
                anchor?.let { sameGroup(it, candidate) }
                val before = listOfNotNull(previous, state(id), invitation, known[candidate.revision - 1]).firstOrNull {
                    it.revision + 1 == candidate.revision && it.hash == candidate.json.getString("previous")
                }
                val ownJoin = candidate.json.getString("action") == "join" && candidate.json.getString("target") == device.userId
                when {
                    candidate.revision == 1 -> {
                        require(candidate.admin == device.userId)
                        GroupState.parse(value)
                    }
                    before != null -> {
                        require(device.userId in before.members || ownJoin) { "Unexpected activity outside membership" }
                        GroupState.parse(value, before)
                    }
                    ownJoin -> {
                        require(anchor != null) { "Missing admin invitation" }
                        val proof = (0 until admissions.length()).map(admissions::getJSONObject).firstOrNull {
                            it.getInt("revision") == candidate.revision
                        }
                        if(proof == null && known[candidate.revision]?.hash != candidate.hash) {
                            waiting = true; saved.put("waiting/$id", "true"); break
                        }
                        if(proof != null) {
                            GroupAdmission.verify(proof, candidate, anchor.admin, device.userId)
                            saved.put("admission/$id/${candidate.revision}", proof.toString())
                        }
                    }
                    known[candidate.revision]?.hash == candidate.hash -> Unit // Previously verified local history.
                    else -> error("Missing signed membership transition or admin admission checkpoint")
                }
                require(device.userId in candidate.members || before?.members?.contains(device.userId) == true ||
                    known[candidate.revision]?.hash == candidate.hash) { "Unexpected activity outside membership" }
                acceptState(candidate)
                previous = candidate; cursor = candidate.revision
            }
            // A revoked/declined invitation or closure is personal control data,
            // not permission to download intervening activity while not a member.
            response.optJSONObject("terminal")?.let { value ->
                val terminal = GroupState.parse(value, verifyChain = false)
                val current = state(id)
                if(current != null && device.userId !in current.members && terminal.revision > current.revision) {
                    sameGroup(current, terminal)
                    require(terminal.json.getString("actor") == terminal.admin ||
                        (terminal.json.getString("actor") == device.userId && terminal.json.getString("action") == "decline"))
                    require(terminal.closed || terminal.json.getString("target") == device.userId)
                    require(device.userId !in terminal.members && device.userId !in terminal.invited)
                    acceptState(terminal, activity = false); saved.remove("waiting/$id")
                }
            }
            if(waiting || batch.length() < 100) break
        } while(!closed)
        if(!waiting) saved.put("history-v2/$id", "true")
    }

    private fun publishAdmissions(group: GroupState) {
        if(group.admin != device.userId) return
        val joins = events(db, scope, group.id, device.userId).map { it.state }.filter {
            it.json.getString("action") == "join" && saved.get("admitted/${group.id}/${it.revision}") == null
        }
        joins.chunked(100).forEach { batch ->
            val payload = JSONArray(batch.map { GroupAdmission.create(it, device) })
            transport.groups(listOf(group.id, "admissions"), JSONObject().put("admissions", payload))
            batch.forEach { saved.put("admitted/${group.id}/${it.revision}", it.hash) }
        }
    }

    private fun synchronize(membershipOnly: Boolean = false) {
        val response = try { transport.groups() } catch(e: ServerTransport.GroupApiException) {
            if(e.code == 404) { supported = false; return }
            throw e
        }
        require(response.optInt("protocol") == 2) { "Update the relay for private group history" }
        val listing = response.getJSONArray("groups")
        supported = true
        require(listing.length() <= 64) { "Too many groups" }
        for(index in 0 until listing.length()) {
            if(closed) return
            val entry = listing.getJSONObject(index); val id = entry.getString("group_id")
            require(id.matches(Regex("[0-9a-f]{32}")))
            synchronizeMembership(id, entry.getInt("revision"))
        }
        if(membershipOnly) { emit(); return }
        val problems = mutableMapOf<String, String>()
        saved.keys("state/").forEach { key ->
            if(closed) return
            val group = state(key.removePrefix("state/"))!!
            saved.keys("retired/${group.id}/").forEach { retired ->
                if(saved.get(retired)!!.toLong() < System.currentTimeMillis()) {
                    val epoch = retired.substringAfterLast('/')
                    saved.remove("epoch/${group.id}/$epoch")
                    saved.keys("receive/${group.id}/$epoch/").forEach(saved::remove)
                    saved.keys("packet/${group.id}/$epoch/").forEach(saved::remove)
                    saved.remove(retired)
                }
            }
            try { publishAdmissions(group) } catch(e: Exception) {
                problems[group.id] = e.message ?: "Unable to confirm group admissions"
            }
            if(device.userId !in group.members || group.closed) return@forEach
            try {
                ensureSenderKey(group)
                receiveSenderKeys(group)
                receiveMessages(group)
                sendPending(group)
                val statuses = transport.groups(listOf(group.id, "statuses")).getJSONArray("statuses")
                for(i in 0 until statuses.length()) {
                    val status = statuses.getJSONObject(i)
                    db.mark(historyScope(scope, group.id), status.getString("id"),
                        if(status.getString("state") == "received") Message.DeliveryStatus.RECEIVED else Message.DeliveryStatus.SENT)
                }
            } catch(e: Exception) {
                if(closed) return
                problems[group.id] = e.message ?: "Unable to update group sender keys"
            }
        }
        emit(problems)
    }

    private fun ensureSenderKey(group: GroupState) {
        require(supported) { "Update this relay to enable groups" }
        if(saved.get("ready/${group.id}") == group.membershipHash) return
        var batch = saved.get("batch/${group.id}")?.let(::JSONObject)
        if(batch == null) {
            val recipients = group.members.filter { it != device.userId }
            // Claims can take seconds for a large roster. Never hold the phone's
            // SQLite write transaction while waiting for these HTTP requests.
            val bundles = recipients.filter(pairwise::needsPrekeys).associateWith(transport::claimPrekeys)
            // Serialize with one-to-one ratchets and persist every exact encrypted packet before HTTP.
            batch = db.atomic {
                val own = senderKeys.create(group, device.userId)
                val distribution = senderKeys.distribution(own).toString().toByteArray(Charsets.UTF_8)
                val packets = JSONArray()
                try {
                    recipients.forEach { recipient ->
                        val plain = Message(device.userId, recipient, distribution, Message.MessageType.DATA)
                        val wire = pairwise.encrypt(plain) { bundles[recipient] ?: error("Pairwise session changed; retry group key distribution") }
                        packets.put(JSONObject().put("recipient", recipient).put("id", wire.id).put("wire", X3dhCrypto.encode(wire.content)))
                    }
                } finally { distribution.fill(0) }
                saved.put("own/${group.id}", own.toString())
                JSONObject().put("epoch", group.epoch).put("packets", packets).also { saved.put("batch/${group.id}", it.toString()) }
            }
        }
        require(batch.getInt("epoch") == group.epoch)
        transport.groups(listOf(group.id, "keys"), batch)
        saved.put("ready/${group.id}", group.membershipHash)
    }

    private fun receiveSenderKeys(group: GroupState) {
        val response = transport.groups(listOf(group.id, "keys"), epoch = group.epoch)
        require(response.getInt("epoch") == group.epoch) { "Group membership changed during key exchange" }
        val packets = response.getJSONArray("packets")
        require(packets.length() <= group.members.size - 1)
        for(index in 0 until packets.length()) {
            val packet = packets.getJSONObject(index); val sender = packet.getString("sender")
            require(sender in group.members && sender != device.userId && packet.getString("recipient") == device.userId)
            val id = packet.getString("id"); val marker = "packet/${group.id}/${group.epoch}/$sender"
            if(saved.get(marker) == id) continue
            require(saved.get(marker) == null) { "Sender tried to replace a key within its membership epoch" }
            val wire = Message(sender, device.userId, X3dhCrypto.decode(packet.getString("wire")), Message.MessageType.DATA, id = id)
            db.atomic {
                val plain = pairwise.decrypt(wire)
                try {
                    val received = senderKeys.receiveDistribution(group, sender, JSONObject(String(plain.content, Charsets.UTF_8)))
                    saved.put("receive/${group.id}/${group.epoch}/$sender", received.toString())
                    saved.put(marker, id)
                } finally { plain.content.fill(0) }
            }
        }
    }

    private fun receiveMessages(group: GroupState) {
        val packets = transport.groups(listOf(group.id, "messages")).getJSONArray("messages")
        val acknowledgements = JSONArray()
        for(index in 0 until packets.length()) {
            val packet = packets.getJSONObject(index); val id = packet.getString("id")
            val history = historyScope(scope, group.id)
            if(db.hasIncoming(history, id)) { acknowledgements.put(id); continue }
            val sender = packet.getString("sender")
            try {
                val epoch = packet.getInt("epoch")
                require(epoch in 1..group.epoch)
                val original = if(epoch == group.epoch) group else GroupState.parse(
                    JSONObject(saved.get("epoch/${group.id}/$epoch") ?: error("Expired receiving epoch")), verifyChain = false)
                require(device.userId in original.members)
                if(saved.get("receive/${group.id}/$epoch/$sender") == null) receiveSenderKeys(original)
                val key = saved.get("receive/${group.id}/$epoch/$sender") ?: continue
                db.atomic {
                    val decoded = senderKeys.decrypt(original, JSONObject(key), sender, id, X3dhCrypto.decode(packet.getString("wire")))
                    check(db.addIncoming(history, decoded.message))
                    saved.put("receive/${group.id}/$epoch/$sender", decoded.state.toString())
                }
                acknowledgements.put(id)
            } catch(e: Exception) {
                onError?.invoke("Rejected a message in ${group.name}: ${e.message}")
                acknowledgements.put(id) // Never repeatedly process attacker-controlled invalid ciphertext.
            }
        }
        if(acknowledgements.length() > 0) transport.groups(listOf(group.id, "ack"), JSONObject().put("ids", acknowledgements))
    }

    private fun sendPending(group: GroupState) {
        val history = historyScope(scope, group.id)
        db.pending(history).forEach { (plain, existing) ->
            if(closed) return
            val queuedEpoch = saved.get("outgoing-epoch/${group.id}/${plain.id}")?.toInt()
            if(queuedEpoch != null && queuedEpoch != group.epoch) {
                db.mark(history, plain.id, Message.DeliveryStatus.FAILED)
                saved.remove("outgoing-epoch/${group.id}/${plain.id}")
                return@forEach
            }
            val wire = existing ?: db.atomic {
                val own = JSONObject(saved.get("own/${group.id}") ?: error("Sender keys are not ready"))
                val encrypted = senderKeys.encrypt(group, own, plain)
                plain.copy(content = encrypted.wire).also {
                    db.setWire(history, it); saved.put("own/${group.id}", encrypted.state.toString())
                }
            }
            val epoch = JSONObject(String(wire.content, Charsets.UTF_8)).getInt("epoch")
            if(epoch != group.epoch) {
                db.mark(history, plain.id, Message.DeliveryStatus.FAILED)
                return@forEach
            }
            transport.groups(listOf(group.id, "messages"), JSONObject().put("id", plain.id).put("epoch", epoch)
                .put("wire", X3dhCrypto.encode(wire.content)))
            db.mark(history, plain.id, Message.DeliveryStatus.SENT)
            saved.remove("outgoing-epoch/${group.id}/${plain.id}")
        }
    }

    private fun emit(problems: Map<String, String> = emptyMap()) {
        if(closed) return
        val groups = saved.keys("state/").map { key ->
            val group = state(key.removePrefix("state/"))!!
            if(saved.get("clear-on-leave/${group.id}") == "true" && (group.closed || device.userId !in group.members))
                clearCachedHistory(db, scope, group.id)
            GroupConversation(group, db.history(historyScope(scope, group.id)),
                saved.get("ready/${group.id}") == group.membershipHash, problems[group.id], events(db, scope, group.id, device.userId), saved.get("waiting/${group.id}") == "true")
        }
        onChanged?.invoke(groups)
    }
}
