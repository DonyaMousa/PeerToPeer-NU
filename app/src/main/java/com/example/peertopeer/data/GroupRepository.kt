package com.example.peertopeer.data

import android.content.Context
import com.example.peertopeer.network.model.PeerGroup
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable metadata for serverless group conversations. */
class GroupRepository(context: Context) {
    private val prefs = context.getSharedPreferences("peer2peer_groups", Context.MODE_PRIVATE)

    fun load(): List<PeerGroup> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val members = o.optJSONArray("members") ?: JSONArray()
                add(
                    PeerGroup(
                        groupId = o.getString("id"),
                        name = o.getString("name"),
                        memberNodeIds = buildList { for (j in 0 until members.length()) add(members.getString(j)) }.distinct(),
                        ownerNodeId = o.optString("owner"),
                        version = o.optLong("version", 1L).coerceAtLeast(1L),
                        updatedAtEpochMs = o.optLong("updatedAt", 0L)
                    )
                )
            }
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun create(name: String, ownerNodeId: String, memberNodeIds: List<String>): PeerGroup {
        val group = PeerGroup(
            groupId = UUID.randomUUID().toString(),
            name = name.trim(),
            ownerNodeId = ownerNodeId,
            memberNodeIds = (memberNodeIds + ownerNodeId).filter { it.isNotBlank() }.distinct(),
            version = 1L,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        save(load() + group)
        return group
    }

    fun find(groupId: String): PeerGroup? = load().firstOrNull { it.groupId == groupId }

    fun addMembers(groupId: String, members: Collection<String>, editorNodeId: String): PeerGroup? {
        val current = find(groupId) ?: return null
        if (current.ownerNodeId.isNotBlank() && current.ownerNodeId != editorNodeId) return null
        val updated = current.copy(
            ownerNodeId = current.ownerNodeId.ifBlank { editorNodeId },
            memberNodeIds = (current.memberNodeIds + members + editorNodeId).filter { it.isNotBlank() }.distinct(),
            version = current.version + 1,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        save(load().map { if (it.groupId == groupId) updated else it })
        return updated
    }

    /** Accept a newer group definition received from the group owner. */
    fun upsertRemote(group: PeerGroup): PeerGroup {
        val current = find(group.groupId)
        if (current != null && current.version > group.version) return current
        save(load().filterNot { it.groupId == group.groupId } + group.copy(memberNodeIds = group.memberNodeIds.distinct()))
        return group
    }

    fun delete(groupId: String) = save(load().filterNot { it.groupId == groupId })

    private fun save(groups: List<PeerGroup>) {
        val array = JSONArray()
        groups.forEach { g ->
            array.put(
                JSONObject()
                    .put("id", g.groupId)
                    .put("name", g.name)
                    .put("members", JSONArray(g.memberNodeIds))
                    .put("owner", g.ownerNodeId)
                    .put("version", g.version)
                    .put("updatedAt", g.updatedAtEpochMs)
            )
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    companion object { private const val KEY = "groups_json" }
}
