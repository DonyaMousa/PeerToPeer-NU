package com.example.peertopeer.data

import android.content.Context
import com.example.peertopeer.network.model.ChatMessage
import com.example.peertopeer.network.model.MessageStatus
import org.json.JSONArray
import org.json.JSONObject

class ChatRepository(context: Context) {
    private val prefs = context.getSharedPreferences("peer2peer_chats", Context.MODE_PRIVATE)

    fun load(): List<ChatMessage> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val traceArray = o.optJSONArray("trace") ?: JSONArray()
                val trace = buildList { for (j in 0 until traceArray.length()) add(traceArray.getString(j)) }
                val recipientStatuses = o.optJSONObject("recipientStatuses") ?: JSONObject()
                val statusMap = buildMap {
                    recipientStatuses.keys().forEach { recipientId ->
                        runCatching { MessageStatus.valueOf(recipientStatuses.getString(recipientId)) }
                            .getOrNull()
                            ?.let { put(recipientId, it) }
                    }
                }
                add(
                    ChatMessage(
                        messageId = o.getString("id"),
                        peerId = o.getString("peer"),
                        text = o.getString("text"),
                        outgoing = o.getBoolean("outgoing"),
                        createdAtElapsedMs = o.optLong("elapsed", 0L),
                        status = runCatching { MessageStatus.valueOf(o.getString("status")) }.getOrDefault(MessageStatus.RECEIVED),
                        latencyMs = o.optLong("latency", -1L).takeIf { it >= 0L },
                        routeTrace = trace,
                        createdAtEpochMs = o.optLong("epoch", System.currentTimeMillis()),
                        groupId = o.optString("groupId").takeIf { it.isNotBlank() },
                        senderId = o.optString("senderId").takeIf { it.isNotBlank() },
                        recipientStatuses = statusMap
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.messageId)
                    .put("peer", m.peerId)
                    .put("text", m.text)
                    .put("outgoing", m.outgoing)
                    .put("elapsed", m.createdAtElapsedMs)
                    .put("epoch", m.createdAtEpochMs)
                    .put("status", m.status.name)
                    .put("latency", m.latencyMs ?: -1L)
                    .put("trace", JSONArray(m.routeTrace))
                    .put("groupId", m.groupId ?: "")
                    .put("senderId", m.senderId ?: "")
                    .put("recipientStatuses", JSONObject().also { statuses ->
                        m.recipientStatuses.forEach { (recipient, status) -> statuses.put(recipient, status.name) }
                    })
            )
        }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    companion object {
        private const val KEY = "chat_messages_json"
        private const val MAX_MESSAGES = 5000
    }
}
