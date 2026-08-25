package com.example.peertopeer.simulation

class DeterministicTimedLinkTransmitter(
    private val maxAttempts: Int,
    private val delayPerAttempt: Long,
    private val attemptPolicy: (
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        attemptNumber: Int
    ) -> Boolean
) : TimedLinkTransmitter {

    init {
        require(maxAttempts > 0) {
            "maxAttempts must be greater than zero."
        }

        require(delayPerAttempt >= 0L) {
            "delayPerAttempt cannot be negative."
        }
    }

    override fun transmit(
        fromNodeId: String,
        toNodeId: String,
        messageId: String
    ): TimedLinkResult {

        for (
        attemptNumber in 1..maxAttempts
        ) {

            val success =
                attemptPolicy(
                    fromNodeId,
                    toNodeId,
                    messageId,
                    attemptNumber
                )

            if (success) {

                return TimedLinkResult(
                    success = true,
                    attempts = attemptNumber,
                    totalDelay =
                        attemptNumber *
                                delayPerAttempt
                )
            }
        }

        return TimedLinkResult(
            success = false,
            attempts = maxAttempts,
            totalDelay =
                maxAttempts *
                        delayPerAttempt
        )
    }
}
