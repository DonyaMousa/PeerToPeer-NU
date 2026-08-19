package com.example.peertopeer.network

enum class PacketDropReason {
    QUEUE_FULL,
    RETRY_EXHAUSTED,
    TTL_EXPIRED,
    NO_ROUTE,
    LINK_UNAVAILABLE
}
