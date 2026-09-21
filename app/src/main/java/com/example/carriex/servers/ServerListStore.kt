package com.example.carriex.servers

import org.json.JSONArray

/** Storage-independent list logic, tested without an Android runtime. */
class ServerListStore(private val read: () -> String?, private val write: (String) -> Boolean) {
    fun list(): List<PairedServer> {
        val raw = read() ?: return emptyList()
        require(raw.length <= 512 * 1024) { "Saved server list is too large" }
        val array = JSONArray(raw)
        require(array.length() <= 50) { "Too many saved servers" }
        val servers = (0 until array.length()).map { PairedServer.parse(array.getJSONObject(it).toString()) }
        require(servers.map { it.url }.distinct().size == servers.size) { "Duplicate saved server addresses" }
        return servers
    }

    @Synchronized
    fun save(server: PairedServer) {
        val servers = list().toMutableList()
        val existing = servers.indexOfFirst { it.url == server.url }
        if (existing >= 0) {
            require(servers[existing].fingerprint == server.fingerprint) {
                "A different server key is already saved for this address. Verify the change, then forget the old server before importing it again"
            }
            servers[existing] = server
        } else {
            require(servers.size < 50) { "Remove a saved server before adding another (limit 50)" }
            servers.add(server)
        }
        persist(servers)
    }

    @Synchronized
    fun remove(url: String) = persist(list().filterNot { it.url == url })

    private fun persist(servers: List<PairedServer>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        check(write(array.toString())) { "Could not save the server list" }
    }
}
