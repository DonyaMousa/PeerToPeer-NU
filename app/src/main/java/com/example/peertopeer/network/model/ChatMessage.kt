package com.example.peertopeer.network.model

enum class MessageStatus { QUEUED, SENDING, IN_TRANSIT, DELIVERED, RECEIVED, FAILED }

data class ChatMessage(
    val messageId: String,
    val peerId: String,
    val text: String,
    val outgoing: Boolean,
    val createdAtElapsedMs: Long,
    val status: MessageStatus,
    val latencyMs: Long? = null,
    val routeTrace: List<String> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    /** Null for a regular one-to-one chat; non-null for a shared group timeline. */
    val groupId: String? = null,
    val senderId: String? = null,
    /** Per-recipient outcome for one logical group message. */
    val recipientStatuses: Map<String, MessageStatus> = emptyMap()
)
