package com.example.peertopeer.network

data class ForwardingResult(
    val finalState: PacketState,
    val visitedNodes: List<String>,
    val attemptedHops: Int,
    val successfulHops: Int,
    val transmissionAttempts: Int
) {

    val retransmissions: Int
        get() =
            transmissionAttempts -
                    attemptedHops
    val failedTransmissionAttempts: Int
        get() =
            transmissionAttempts -
                    successfulHops
}