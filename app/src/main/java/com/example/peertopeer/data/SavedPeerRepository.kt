package com.example.peertopeer.data

import android.content.Context
import com.example.peertopeer.network.model.SavedPeer
import org.json.JSONArray
import org.json.JSONObject

class SavedPeerRepository(context: Context) {
    private val prefs = context.getSharedPreferences("peer2peer_saved_peers", Context.MODE_PRIVATE)

    fun load(): List<SavedPeer> = runCatching {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SavedPeer(
                        userId = o.getString("id"),
                        displayName = o.optString("name", o.getString("id")),
                        addedAtEpochMs = o.optLong("added", System.currentTimeMillis()),
                        lastSeenEpochMs = o.optLong("seen").takeIf { it > 0L }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun add(userId: String, displayName: String? = null): List<SavedPeer> {
        val id = normalize(userId) ?: return load()
        val current = load().toMutableList()
        if (current.none { it.userId == id }) {
            current += SavedPeer(id, displayName?.trim().orEmpty().ifBlank { id }, System.currentTimeMillis())
            save(current)
        }
        return current
    }

    fun remove(userId: String): List<SavedPeer> {
        val next = load().filterNot { it.userId == userId.uppercase() }
        save(next)
        return next
    }

    fun updateNameAndSeen(userId: String, displayName: String, seen: Boolean = true): List<SavedPeer> {
        val id = userId.uppercase()
        val next = load().map {
            if (it.userId == id) it.copy(
                displayName = displayName.ifBlank { it.displayName },
                lastSeenEpochMs = if (seen) System.currentTimeMillis() else it.lastSeenEpochMs
            ) else it
        }
        save(next)
        return next
    }

    fun contains(userId: String): Boolean = load().any { it.userId == userId.uppercase() }

    private fun save(items: List<SavedPeer>) {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().put("id", p.userId).put("name", p.displayName).put("added", p.addedAtEpochMs).put("seen", p.lastSeenEpochMs ?: 0L))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun normalize(raw: String): String? {
        val id = raw.trim().uppercase().replace("-", "")
        return id.takeIf { it.matches(Regex("[0-9A-F]{8}")) }
    }

    companion object { private const val KEY = "saved_peers_json" }
}
