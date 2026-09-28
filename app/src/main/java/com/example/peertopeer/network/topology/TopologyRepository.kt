package com.example.peertopeer.network.topology

import android.os.SystemClock
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.model.PeerInfo
import org.json.JSONArray
import org.json.JSONObject

/** Sender-owned link-state advertisements. Relayed copies never refresh their age. */
class TopologyRepository(
    private val localNode: Node,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class Report(val owner: String, val sequence: Long, val neighbors: Set<String>, val expires: Long)
    private val peers = linkedMapOf<String, PeerInfo>()
    /** Advertisement sightings are discovery evidence; only this set is usable local BLE transport. */
    private val transportReady = linkedSetOf<String>()
    private val reports = linkedMapOf<String, Report>()
    private val highWater = mutableMapOf<String, Long>()
    private var sequence = wallClockMs()
    private val lifetime = 20_000L
    @Synchronized fun isDirectPeer(id: String) = peers[id]?.isDirect == true
    @Synchronized fun isTransportReady(id: String) = id in transportReady
    @Synchronized fun upsertDirectPeer(nodeId: String, displayName: String, address: String, rssi: Int) {
        if (nodeId == localNode.nodeId) return
        val old = peers[nodeId]
        peers[nodeId] = PeerInfo(nodeId, displayName.takeIf { it.isNotBlank() && it != nodeId } ?: old?.displayName ?: nodeId,
            true, rssi = rssi, address = address, lastSeenElapsedMs = elapsedRealtime(), hopEstimate = 1)
    }
    @Synchronized fun touchDirectPeer(nodeId: String, address: String? = null) {
        val old = peers[nodeId] ?: return
        peers[nodeId] = old.copy(isDirect = true, address = address ?: old.address, lastSeenElapsedMs = elapsedRealtime(), hopEstimate = 1)
    }
    @Synchronized fun setTransportReady(nodeId: String, ready: Boolean, address: String? = null) {
        if (nodeId == localNode.nodeId) return
        if (ready) {
            transportReady += nodeId
            val old = peers[nodeId]
            peers[nodeId] = (old ?: PeerInfo(nodeId, nodeId, false)).copy(
                address = address ?: old?.address,
                lastSeenElapsedMs = elapsedRealtime()
            )
        } else {
            transportReady -= nodeId
        }
    }
    @Synchronized fun markPeerName(nodeId: String, displayName: String) {
        peers[nodeId] = (peers[nodeId] ?: PeerInfo(nodeId, displayName, false)).copy(displayName = displayName)
    }
    private fun prune() { val now = elapsedRealtime(); reports.entries.removeAll { it.value.expires <= now } }

    /** Owner topology update, separated from JSON parsing for deterministic route tests. */
    @Synchronized
    internal fun updateRemoteReport(owner: String, reportSequence: Long, neighbors: Set<String>, remainingMs: Long): Boolean {
        if (owner == localNode.nodeId || !owner.matches(Regex("[0-9A-F]{8}"))) return false
        if (reportSequence <= (highWater[owner] ?: Long.MIN_VALUE)) return false
        val remaining = remainingMs.coerceIn(0L, lifetime)
        if (remaining == 0L) return false
        val validNeighbors = neighbors.filter { it != owner && it.matches(Regex("[0-9A-F]{8}")) }.toSet()
        peers.putIfAbsent(owner, PeerInfo(owner, owner, false))
        validNeighbors.forEach { id -> peers.putIfAbsent(id, PeerInfo(id, id, false)) }
        highWater[owner] = reportSequence
        reports[owner] = Report(owner, reportSequence, validNeighbors, elapsedRealtime() + remaining)
        return true
    }
    @Synchronized fun mergeTopology(payload: String, sourceNodeId: String) {
        runCatching {
            val root = JSONObject(payload)
            val nodes = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until minOf(nodes.length(), 100)) {
                val n = nodes.getJSONObject(i); val id = n.optString("id")
                if (id.matches(Regex("[0-9A-F]{8}")) && id != localNode.nodeId) {
                    val name = n.optString("name", id).take(24)
                    if (peers[id] == null) peers[id] = PeerInfo(id, name, false)
                    else if (peers[id]?.displayName == id) markPeerName(id, name)
                }
            }
            val array = root.optJSONArray("reports") ?: return
            for (i in 0 until minOf(array.length(), 100)) {
                val item = array.getJSONObject(i); val owner = item.getString("owner")
                if (owner == localNode.nodeId || !owner.matches(Regex("[0-9A-F]{8}"))) continue
                val seq = item.getLong("sequence")
                val remaining = item.optLong("remainingMs", 0L).coerceIn(0L, lifetime)
                if (remaining == 0L) continue
                val neighbors = item.getJSONArray("neighbors")
                val ids = (0 until minOf(neighbors.length(), 100)).map { neighbors.getString(it) }.filter { it != owner && it.matches(Regex("[0-9A-F]{8}")) }.toSet()
                updateRemoteReport(owner, seq, ids, remaining)
            }
            prune()
        }
    }
    @Synchronized fun snapshotJson(): String {
        prune(); sequence = maxOf(sequence + 1, wallClockMs())
        val now = elapsedRealtime()
        // Only GATT-ready neighbors are published as physical edges. Seeing an
        // advertisement is useful for discovery but is not proof that a write works.
        val direct = transportReady.toSet()
        val all = listOf(Report(localNode.nodeId, sequence, direct, now + lifetime)) + reports.values
        val array = JSONArray()
        all.forEach { r -> array.put(JSONObject().put("owner", r.owner).put("sequence", r.sequence)
            .put("remainingMs", (r.expires - now).coerceAtLeast(0L)).put("neighbors", JSONArray(r.neighbors.toList()))) }
        val nodes = JSONArray().put(JSONObject().put("id", localNode.nodeId).put("name", localNode.displayName))
        peers.values.forEach { nodes.put(JSONObject().put("id", it.nodeId).put("name", it.displayName)) }
        return JSONObject().put("nodes", nodes).put("directNeighbors", JSONArray(direct.toList())).put("reports", array).toString()
    }
    @Synchronized fun graphSnapshot(): Graph {
        prune()
        val g = Graph(); g.addNode(localNode)
        peers.values.forEach { g.addNode(Node(it.nodeId, it.displayName)) }
        reports.values.forEach { r ->
            if (!g.containsNode(r.owner)) g.addNode(Node(r.owner, r.owner))
            r.neighbors.forEach { if (!g.containsNode(it)) g.addNode(Node(it, it)) }
        }
        transportReady.forEach { id ->
            if (!g.containsNode(id)) g.addNode(Node(id, peers[id]?.displayName ?: id))
            g.addEdge(localNode.nodeId, id, 1)
        }
        reports.values.forEach { r -> r.neighbors.forEach { n ->
            if (n != localNode.nodeId && !g.containsEdge(r.owner, n)) {
                // A fresh opposite report that withdrew this edge wins over an old positive claim.
                val reverse = reports[n]
                if (reverse == null || r.owner in reverse.neighbors) g.addEdge(r.owner, n, 1)
            }
        } }
        return g
    }
    @Synchronized fun expireDirectPeers(maxAgeMs: Long): List<String> {
        val now = elapsedRealtime()
        val expired = peers.values.filter { it.isDirect && now - it.lastSeenElapsedMs > maxAgeMs }.map { it.nodeId }
        expired.forEach { id -> peers[id] = peers.getValue(id).copy(isDirect = false, rssi = null, address = null, hopEstimate = null) }
        prune(); return expired
    }
    @Synchronized fun directAddress(nodeId: String) = peers[nodeId]?.takeIf { nodeId in transportReady }?.address
    @Synchronized fun allPeers(): List<PeerInfo> {
        val g = graphSnapshot()
        return peers.values.map { stored ->
            // `isDirect` in the public peer model now means a ready one-hop
            // messaging link. A nearby advertisement alone never labels a peer
            // Direct or pins its distance at one hop.
            val p = stored.copy(isDirect = stored.nodeId in transportReady)
            val route = g.getNode(p.nodeId)?.let { com.example.peertopeer.routing.DijkstraEngine().findRoute(g, localNode, it) }
            p.copy(hopEstimate = if (p.isDirect) 1 else route?.path?.size?.minus(1))
        }.sortedBy { it.displayName.lowercase() }
    }
}
