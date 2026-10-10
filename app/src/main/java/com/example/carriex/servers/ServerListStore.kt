package com.example.carriex.servers

import org.json.JSONArray

/** Storage-independent list logic, tested without an Android runtime. */
class ServerListStore(private val read: () -> String?, private val write: (String) -> Boolean) {
    private fun load(): Pair<List<PairedServer>, String?> {
        val raw = read() ?: return emptyList<PairedServer>() to null
        require(raw.length <= 512 * 1024) { "Saved server list is too large" }
        val array = JSONArray(raw)
        require(array.length() <= 50) { "Too many saved servers" }
        val servers = (0 until array.length()).map { PairedServer.parse(array.getJSONObject(it).toString()) }
        require(servers.map { it.url }.distinct().size == servers.size) { "Duplicate saved server addresses" }
        val selected = servers.indices.filter { array.getJSONObject(it).optBoolean("auto_connect", false) }
        require(selected.size <= 1) { "Multiple startup servers are selected" }
        return servers to selected.firstOrNull()?.let { servers[it].url }
    }

    fun list(): List<PairedServer> = load().first
    fun autoConnectUrl(): String? = load().second

    @Synchronized
    fun setAutoConnect(url: String?) {
        val servers = list()
        require(url == null || servers.any { it.url == url }) { "Select a saved server for startup" }
        persist(servers, url)
    }

    @Synchronized
    fun save(server: PairedServer) {
        val (saved, selected) = load()
        val servers = saved.toMutableList()
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
        persist(servers, selected)
    }

    @Synchronized
    fun remove(url: String) {
        val (servers, selected) = load()
        persist(servers.filterNot { it.url == url }, selected?.takeUnless { it == url })
    }

    private fun persist(servers: List<PairedServer>, selected: String?) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson().put("auto_connect", it.url == selected)) }
        check(write(array.toString())) { "Could not save the server list" }
    }
}
