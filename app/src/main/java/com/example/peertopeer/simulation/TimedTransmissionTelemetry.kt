package com.example.peertopeer.simulation

class TimedTransmissionTelemetry {

    private var totalTransmissionAttempts = 0
    private var logicalHopAttempts = 0
    private var successfulHops = 0

    fun record(
        result: TimedLinkResult
    ) {

        logicalHopAttempts++

        totalTransmissionAttempts +=
            result.attempts

        if (result.success) {
            successfulHops++
        }
    }

    fun transmissionAttempts(): Int {
        return totalTransmissionAttempts
    }

    fun logicalHopAttempts(): Int {
        return logicalHopAttempts
    }

    fun successfulHopTransmissions(): Int {
        return successfulHops
    }

    fun failedTransmissionAttempts(): Int {

        return totalTransmissionAttempts -
                successfulHops
    }

    fun retransmissions(): Int {

        return totalTransmissionAttempts -
                logicalHopAttempts
    }
}