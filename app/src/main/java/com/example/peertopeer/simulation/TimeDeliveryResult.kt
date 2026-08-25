package com.example.peertopeer.simulation

import com.example.peertopeer.network.PacketDropReason

data class TimedDeliveryResult(
    val messageId: String,
    val createdAt: Long,
    val deliveredAt: Long?,
    val droppedAt: Long? = null,
    val delivered: Boolean,
    val dropped: Boolean,
    val dropReason: PacketDropReason? = null
) {

    init {
        require(messageId.isNotBlank()) {
            "messageId must not be blank"
        }

        require(createdAt >= 0) {
            "createdAt must not be negative"
        }

        require(!(delivered && dropped)) {
            "A packet cannot be both delivered and dropped"
        }

        if (delivered) {
            require(deliveredAt != null) {
                "Delivered packet must have deliveredAt"
            }

            require(deliveredAt >= createdAt) {
                "deliveredAt cannot be before createdAt"
            }

            require(droppedAt == null) {
                "Delivered packet cannot have droppedAt"
            }

            require(dropReason == null) {
                "Delivered packet cannot have a drop reason"
            }
        }

        if (dropped) {
            require(deliveredAt == null) {
                "Dropped packet cannot have deliveredAt"
            }

            require(dropReason != null) {
                "Dropped packet must have a drop reason"
            }

            if (droppedAt != null) {
                require(droppedAt >= createdAt) {
                    "droppedAt cannot be before createdAt"
                }
            }
        }

        if (!dropped) {
            require(dropReason == null) {
                "Non-dropped packet cannot have a drop reason"
            }

            require(droppedAt == null) {
                "Non-dropped packet cannot have droppedAt"
            }
        }
    }

    fun endToEndLatency(): Long? {
        return if (delivered && deliveredAt != null) {
            deliveredAt - createdAt
        } else {
            null
        }
    }

    fun terminalTime(): Long? {
        return when {
            delivered -> deliveredAt
            dropped -> droppedAt
            else -> null
        }
    }

    fun timeUntilTermination(): Long? {
        val terminalTime = terminalTime() ?: return null

        return terminalTime - createdAt
    }
}