package com.example.peertopeer.network

class RetryLinkTransmitter(
    private val maxAttempts: Int,
    private val attemptPolicy:
        (
        fromNodeId: String,
        toNodeId: String,
        packetState: PacketState,
        attemptNumber: Int
    ) -> Boolean
) : LinkTransmitter {

    init {
        require(maxAttempts > 0) {
            "maxAttempts must be greater than zero."
        }
    }

    override fun transmit(
        fromNodeId: String,
        toNodeId: String,
        packetState: PacketState
    ): LinkTransmissionResult {

        for (
        attempt in 1..maxAttempts
        ) {

            val success =
                attemptPolicy(
                    fromNodeId,
                    toNodeId,
                    packetState,
                    attempt
                )

            if (success) {

                return LinkTransmissionResult(
                    success = true,
                    attempts = attempt
                )
            }
        }

        return LinkTransmissionResult(
            success = false,
            attempts = maxAttempts
        )
    }
}
