package com.example.peertopeer.simulation

import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord

class EventDrivenRetryLinkTransmitter(
    private val simulationEngine: SimulationEngine,
    private val maxAttempts: Int,
    private val delayPerAttempt: Long,
    private val attemptPolicy: TimedLinkAttemptPolicy,
    private val runId: String? = null,
    private val instrumentation: ExperimentInstrumentation? = null
) : EventDrivenTimedLinkTransmitter {

    init {
        require(maxAttempts > 0) {
            "maxAttempts must be greater than 0"
        }

        require(delayPerAttempt > 0) {
            "delayPerAttempt must be greater than 0"
        }
    }

    /*
     * Every call to transmit() represents ONE logical hop.
     *
     * Example:
     *
     * message MSG-1
     *
     * A -> B = logical hop 0
     * B -> C = logical hop 1
     * C -> D = logical hop 2
     *
     * Retries of A -> B all remain logical hop 0.
     */
    private val nextLogicalHopIndexByMessage =
        mutableMapOf<String, Int>()

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

        val logicalHopIndex =
            nextLogicalHopIndexByMessage[
                messageId
            ] ?: 0

        nextLogicalHopIndexByMessage[
            messageId
        ] =
            logicalHopIndex + 1

        scheduleAttempt(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            messageId = messageId,
            startTime = startTime,
            logicalHopIndex = logicalHopIndex,
            attemptNumber = 1,
            onComplete = onComplete
        )
    }

    private fun scheduleAttempt(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        startTime: Long,
        logicalHopIndex: Int,
        attemptNumber: Int,
        onComplete: (
            result: TimedLinkResult,
            completionTime: Long
        ) -> Unit
    ) {

        val attemptTime =
            startTime +
                    (attemptNumber * delayPerAttempt)

        simulationEngine.schedule(
            attemptTime
        ) {

            val success =
                attemptPolicy.shouldSucceed(
                    fromNodeId = fromNodeId,
                    toNodeId = toNodeId,
                    messageId = messageId,
                    attemptNumber = attemptNumber,
                    attemptTime = attemptTime
                )

            // =================================================
            // RAW TRANSMISSION RECORD
            // =================================================

            instrumentation?.onTransmission(
                TransmissionRecord(
                    runId = effectiveRunId(),
                    messageId = messageId,
                    fromNodeId = fromNodeId,
                    toNodeId = toNodeId,
                    attemptNumber = attemptNumber,
                    attemptTime = attemptTime,
                    success = success,
                    logicalHopIndex = logicalHopIndex
                )
            )

            // =================================================
            // SUCCESS
            // =================================================

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

            // =================================================
            // RETRY EXHAUSTION
            // =================================================

            if (
                attemptNumber >= maxAttempts
            ) {

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

            // =================================================
            // NEXT RETRY
            // =================================================

            scheduleAttempt(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                messageId = messageId,
                startTime = startTime,
                logicalHopIndex = logicalHopIndex,
                attemptNumber = attemptNumber + 1,
                onComplete = onComplete
            )
        }
    }

    private fun effectiveRunId(): String {
        return runId ?: "UNINSTRUMENTED"
    }
}