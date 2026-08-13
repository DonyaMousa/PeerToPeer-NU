package com.example.peertopeer.network


data class Packet(
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val createdAt: Long,
    val ttl: Int,
    val payload: String
) {

    init {
        require(messageId.isNotBlank()) {
            "messageId cannot be blank."
        }

        require(sourceId.isNotBlank()) {
            "sourceId cannot be blank."
        }

        require(destinationId.isNotBlank()) {
            "destinationId cannot be blank."
        }

        require(sourceId != destinationId) {
            "Source and destination must be different."
        }

        require(ttl > 0) {
            "TTL must be greater than zero."
        }
    }
}
