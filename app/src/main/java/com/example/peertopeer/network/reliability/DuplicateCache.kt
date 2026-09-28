package com.example.peertopeer.network.reliability

/**
 * Bounded duplicate gate with a configurable suppression window.
 *
 * It intentionally does NOT suppress the same packet forever. Real BLE retries
 * may be required after an end-to-end ACK is lost. A relay therefore uses a
 * short suppression window, while a destination uses a much longer window for
 * idempotent delivery (but can still re-send the ACK for duplicate arrivals).
 */
class DuplicateCache(
    private val maxEntries: Int = 2_000,
    private val suppressionWindowMs: Long = 2_000L
) {
    private val seenAtMs = LinkedHashMap<String, Long>()

    @Synchronized
    fun firstSeen(messageId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val previous = seenAtMs[messageId]
        if (previous != null && nowMs - previous < suppressionWindowMs) return false

        seenAtMs.remove(messageId)
        seenAtMs[messageId] = nowMs

        while (seenAtMs.size > maxEntries) {
            val first = seenAtMs.entries.firstOrNull()?.key ?: break
            seenAtMs.remove(first)
        }
        return true
    }

    @Synchronized
    fun forget(messageId: String) {
        seenAtMs.remove(messageId)
    }

    @Synchronized
    fun clear() {
        seenAtMs.clear()
    }
}
