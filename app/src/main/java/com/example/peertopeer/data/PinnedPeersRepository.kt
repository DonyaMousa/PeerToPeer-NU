package com.example.peertopeer.data

import android.content.Context

class PinnedPeersRepository(context: Context) {
    private val prefs = context.getSharedPreferences("peer2peer_pins", Context.MODE_PRIVATE)
    fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    fun toggle(nodeId: String): Set<String> {
        val next = load().toMutableSet()
        if (!next.add(nodeId)) next.remove(nodeId)
        prefs.edit().putStringSet(KEY, next).apply()
        return next
    }
    companion object { private const val KEY = "pinned_peer_ids" }
}
