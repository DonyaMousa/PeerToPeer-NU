package com.example.peertopeer.data

import android.content.Context
import com.example.peertopeer.domain.identity.NodeIdentity
import java.security.SecureRandom

class IdentityDataStore(context: Context) {
    private val prefs = context.getSharedPreferences("peer2peer_identity", Context.MODE_PRIVATE)

    fun load(): NodeIdentity? {
        val id = prefs.getString(KEY_NODE_ID, null) ?: return null
        val name = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        return NodeIdentity(id, name)
    }

    fun ensureIdentity(): NodeIdentity {
        return load() ?: NodeIdentity(generateNodeId(), "").also(::save)
    }

    fun save(identity: NodeIdentity) {
        prefs.edit()
            .putString(KEY_NODE_ID, identity.nodeId)
            .putString(KEY_DISPLAY_NAME, identity.displayName.trim())
            // First-run navigation depends on this identity immediately.
            // Commit makes the ID/name durable before Compose moves forward.
            .commit()
    }

    fun updateDisplayName(name: String): NodeIdentity {
        val current = ensureIdentity()
        val updated = current.copy(displayName = name.trim())
        save(updated)
        return updated
    }

    private fun generateNodeId(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    companion object {
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}
