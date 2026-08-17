package com.example.peertopeer.simulation

data class TimedDeliveryResult(
    val messageId: String,
    val createdAt: Long,
    val deliveredAt: Long?,
    val delivered: Boolean,
    val dropped: Boolean
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

        } else {

            require(deliveredAt == null) {
                "Undelivered packet cannot have deliveredAt."
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
