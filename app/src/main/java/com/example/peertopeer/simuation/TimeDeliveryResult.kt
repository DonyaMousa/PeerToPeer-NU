package com.example.peertopeer.simulation

import com.example.peertopeer.network.PacketDropReason

data class TimedDeliveryResult(
    val messageId: String,
    val createdAt: Long,
    val deliveredAt: Long?,
    val delivered: Boolean,
    val dropped: Boolean,
    val dropReason: PacketDropReason? = null
) {

    init {
        require(messageId.isNotBlank()) {
            "messageId cannot be blank."
        }

        require(createdAt >= 0L) {
            "createdAt cannot be negative."
        }

        require(!(delivered && dropped)) {
            "A packet cannot be both delivered and dropped."
        }

        if (delivered) {

            require(deliveredAt != null) {
                "Delivered packet must have deliveredAt."
            }

            require(deliveredAt >= createdAt) {
                "deliveredAt cannot be earlier than createdAt."
            }

            require(dropReason == null) {
                "Delivered packet cannot have a drop reason."
            }

        } else {

            require(deliveredAt == null) {
                "Undelivered packet cannot have deliveredAt."
            }
        }

        if (dropped) {

            require(dropReason != null) {
                "Dropped packet must have a drop reason."
            }

        } else {

            require(dropReason == null) {
                "Non-dropped packet cannot have a drop reason."
            }
        }
    }

    fun endToEndLatency(): Long? {

        if (!delivered) {
            return null
        }

        return deliveredAt!! - createdAt
    }
}