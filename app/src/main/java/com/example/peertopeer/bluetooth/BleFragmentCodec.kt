package com.example.peertopeer.bluetooth

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** v11 framing: magic, transfer ID, index, count, reserved. ATT MTU 23 is supported. */
object BleFragmentCodec {
    private const val MAGIC: Byte = 0x53
    private const val HEADER = 10
    private val sequence = AtomicInteger(java.util.Random().nextInt())
    const val MAX_PACKET_BYTES = 64 * 1024
    fun fragment(data: ByteArray, maxPayload: Int = BleConstants.MAX_FRAGMENT_PAYLOAD): List<ByteArray> {
        require(data.size <= MAX_PACKET_BYTES)
        val limit = maxPayload.coerceIn(10, BleConstants.MAX_FRAGMENT_PAYLOAD)
        val total = ((data.size + limit - 1) / limit).coerceAtLeast(1)
        val id = sequence.incrementAndGet()
        return (0 until total).map { i ->
            val payload = data.copyOfRange(i * limit, minOf(data.size, (i + 1) * limit))
            ByteBuffer.allocate(HEADER + payload.size).put(MAGIC).putInt(id).putShort(i.toShort()).putShort(total.toShort()).put(0).put(payload).array()
        }
    }
    class Reassembler(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
        private data class Bucket(val total: Int, var touched: Long, val parts: MutableMap<Int, ByteArray> = mutableMapOf(), var size: Int = 0)
        private val buckets = linkedMapOf<String, Bucket>()
        @Synchronized fun accept(senderKey: String, fragment: ByteArray): ByteArray? {
            val time = now()
            buckets.entries.removeAll { time - it.value.touched > 30_000L }
            if (fragment.size < HEADER || fragment[0] != MAGIC) return null
            val b = ByteBuffer.wrap(fragment); b.get()
            val key = "$senderKey:${b.int}"
            val index = b.short.toInt() and 65535
            val total = b.short.toInt() and 65535
            b.get()
            if (total !in 1..6554 || index >= total) return null
            val existing = buckets[key]
            if (existing != null && existing.total != total) { buckets.remove(key); return null }
            if (existing == null && buckets.size >= 32) buckets.remove(buckets.keys.first())
            val bucket = buckets.getOrPut(key) { Bucket(total, time) }
            val part = ByteArray(b.remaining()).also(b::get)
            val old = bucket.parts[index]
            if (old != null && !old.contentEquals(part)) { buckets.remove(key); return null }
            if (old == null) { bucket.parts[index] = part; bucket.size += part.size }
            bucket.touched = time
            if (bucket.size > MAX_PACKET_BYTES) { buckets.remove(key); return null }
            if (bucket.parts.size != total) return null
            val result = ByteArray(bucket.size); var offset = 0
            for (i in 0 until total) { val p = bucket.parts[i] ?: return null; p.copyInto(result, offset); offset += p.size }
            buckets.remove(key); return result
        }
        @Synchronized fun clear(senderKey: String? = null) {
            if (senderKey == null) buckets.clear() else buckets.keys.removeAll { it.startsWith("$senderKey:") }
        }
    }
}
