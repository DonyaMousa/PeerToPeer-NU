package com.example.peertopeer.simulation

class EventDrivenRetryLinkTransmitter(
    private val simulationEngine: SimulationEngine,
    private val maxAttempts: Int,
    private val delayPerAttempt: Long,
    private val attemptPolicy: TimedLinkAttemptPolicy
) : EventDrivenTimedLinkTransmitter {

    init {
        require(maxAttempts > 0) {
            "maxAttempts must be greater than 0"
        }

        require(delayPerAttempt > 0) {
            "delayPerAttempt must be greater than 0"
        }
    }

    override fun transmit(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        startTime: Long,
        onComplete: (
            result: TimedLinkResult,
            completionTime: Long
        ) -> Unit
    ) {
        require(fromNodeId.isNotBlank()) {
            "fromNodeId must not be blank"
        }

        require(toNodeId.isNotBlank()) {
            "toNodeId must not be blank"
        }

        require(messageId.isNotBlank()) {
            "messageId must not be blank"
        }

        require(fromNodeId != toNodeId) {
            "fromNodeId and toNodeId must be different"
        }

        require(startTime >= simulationEngine.currentTime) {
            "startTime cannot be in the past"
        }

        scheduleAttempt(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            messageId = messageId,
            startTime = startTime,
            attemptNumber = 1,
            onComplete = onComplete
        )
    }

    private fun scheduleAttempt(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        startTime: Long,
        attemptNumber: Int,
        onComplete: (
            result: TimedLinkResult,
            completionTime: Long
        ) -> Unit
    ) {
        val attemptTime =
            startTime + (attemptNumber * delayPerAttempt)

        simulationEngine.schedule(attemptTime) {

            val success = attemptPolicy.shouldSucceed(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                messageId = messageId,
                attemptNumber = attemptNumber,
                attemptTime = attemptTime
            )

            if (success) {

                val totalDelay =
                    attemptTime - startTime

                onComplete(
                    TimedLinkResult(
                        success = true,
                        attempts = attemptNumber,
                        totalDelay = totalDelay
                    ),
                    attemptTime
                )

                return@schedule
            }

            if (attemptNumber >= maxAttempts) {

                val totalDelay =
                    attemptTime - startTime

                onComplete(
                    TimedLinkResult(
                        success = false,
                        attempts = attemptNumber,
                        totalDelay = totalDelay
                    ),
                    attemptTime
                )

                return@schedule
            }

            scheduleAttempt(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                messageId = messageId,
                startTime = startTime,
                attemptNumber = attemptNumber + 1,
                onComplete = onComplete
            )
        }
    }
}
