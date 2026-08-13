package com.example.peertopeer.network

data class PacketState(
    val packet: Packet,
    val currentNodeId: String,
    val remainingTtl: Int,
    val hopCount: Int = 0,
    val delivered: Boolean = false,
    val dropped: Boolean = false
) {

    init {
        require(currentNodeId.isNotBlank()) {
            "currentNodeId cannot be blank."
        }

        require(remainingTtl >= 0) {
            "remainingTtl cannot be negative."
        }

        require(hopCount >= 0) {
            "hopCount cannot be negative."
        }

        require(!(delivered && dropped)) {
            "A packet cannot be both delivered and dropped."
        }
    }

    fun forwardTo(
        nextNodeId: String
    ): PacketState {

        require(nextNodeId.isNotBlank()) {
            "nextNodeId cannot be blank."
        }

        require(!delivered) {
            "Delivered packet cannot be forwarded."
        }

        require(!dropped) {
            "Dropped packet cannot be forwarded."
        }

        require(remainingTtl > 0) {
            "Packet TTL exhausted."
        }

        return copy(
            currentNodeId = nextNodeId,
            remainingTtl = remainingTtl - 1,
            hopCount = hopCount + 1
        )
    }

    fun markDelivered(): PacketState {

        require(!dropped) {
            "Dropped packet cannot be delivered."
        }

        return copy(
            delivered = true
        )
    }

    fun markDropped(): PacketState {

        require(!delivered) {
            "Delivered packet cannot be dropped."
        }

        return copy(
            dropped = true
        )
    }
}
