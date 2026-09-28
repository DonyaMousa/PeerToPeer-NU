package com.example.peertopeer.network.model

data class NetworkPacket(
    val version: Int = 5,
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val previousHopId: String? = null,
    /** Next hop currently holding this node's custody copy; persisted with pending packets. */
    val custodyNextHopId: String? = null,
    val lowReevaluations: Int = 0,
    val copyBudgetRemaining: Int = 1,
    val hopCount: Int = 0,
    val maxHops: Int = 12,
    val createdAtElapsedMs: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiryAfterMs: Long = 120_000L,
    val protocol: ProtocolType,
    val type: PacketType,
    val payload: String,
    val routeTrace: List<String> = listOf(sourceId)
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - createdAtEpochMs > expiryAfterMs
}
