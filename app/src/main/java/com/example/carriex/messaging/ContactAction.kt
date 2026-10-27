package com.example.carriex.messaging

import com.example.carriex.model.Message
import org.json.JSONArray
import org.json.JSONObject

/** One authenticated packet can carry several separate lifecycle notices. */
data class ContactAction(val deleteHistory: Boolean = false, val block: Boolean = false,
                         val removeKeys: Boolean = false, val restart: Boolean = false) {
    val kinds get() = buildList {
        if(deleteHistory) add("history_deleted")
        if(block) add("blocked")
        if(removeKeys) add("keys_removed")
        if(restart) add("session_restarted")
    }
    fun bytes(): ByteArray {
        require(kinds.isNotEmpty())
        return JSONObject().put("version", 1).put("actions", JSONArray(kinds)).toString().toByteArray(Charsets.UTF_8)
    }
    companion object {
        fun parse(bytes: ByteArray): ContactAction {
            require(bytes.size <= 1024)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val values = json.getJSONArray("actions")
            require(values.length() in 1..4)
            val kinds = (0 until values.length()).map { values.getString(it) }
            require(kinds.distinct().size == kinds.size && kinds.all { it in setOf("history_deleted", "blocked", "keys_removed", "session_restarted") })
            return ContactAction("history_deleted" in kinds, "blocked" in kinds, "keys_removed" in kinds, "session_restarted" in kinds)
        }
        fun timeline(message: Message): List<Message> = if(message.type != Message.MessageType.CHAT_EVENT) listOf(message)
            else parse(message.content).kinds.map { message.copy(id = "${message.id}/$it", content = it.toByteArray(Charsets.UTF_8)) }
    }
}
