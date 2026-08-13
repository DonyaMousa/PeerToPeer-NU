package com.example.peertopeer.network

class PacketQueue(
    val capacity: Int
) {

    init {
        require(capacity > 0) {
            "Queue capacity must be greater than zero."
        }
    }

    private val queue =
        ArrayDeque<PacketState>()

    fun enqueue(
        packetState: PacketState
    ): Boolean {

        if (queue.size >= capacity) {
            return false
        }

        queue.addLast(packetState)

        return true
    }

    fun dequeue(): PacketState? {

        if (queue.isEmpty()) {
            return null
        }

        return queue.removeFirst()
    }

    fun peek(): PacketState? {
        return queue.firstOrNull()
    }

    fun size(): Int {
        return queue.size
    }

    fun isEmpty(): Boolean {
        return queue.isEmpty()
    }

    fun isFull(): Boolean {
        return queue.size >= capacity
    }

    fun clear() {
        queue.clear()
    }
}
