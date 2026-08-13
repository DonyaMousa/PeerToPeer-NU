package com.example.peertopeer.network

class NetworkTelemetry {

    var generatedPackets: Int = 0
        private set

    var deliveredPackets: Int = 0
        private set

    var droppedPackets: Int = 0
        private set

    var attemptedHops: Int = 0
        private set

    var successfulHops: Int = 0
        private set

    var transmissionAttempts: Int = 0
        private set

    fun record(
        result: ForwardingResult
    ) {

        generatedPackets++

        if (result.finalState.delivered) {
            deliveredPackets++
        }

        if (result.finalState.dropped) {
            droppedPackets++
        }

        attemptedHops +=
            result.attemptedHops

        successfulHops +=
            result.successfulHops

        transmissionAttempts +=
            result.transmissionAttempts
    }

    fun packetDeliveryRatio(): Double {

        if (generatedPackets == 0) {
            return 0.0
        }

        return deliveredPackets.toDouble() /
                generatedPackets.toDouble()
    }

    fun dropRate(): Double {

        if (generatedPackets == 0) {
            return 0.0
        }

        return droppedPackets.toDouble() /
                generatedPackets.toDouble()
    }

    fun attemptsPerDeliveredPacket(): Double {

        if (deliveredPackets == 0) {
            return 0.0
        }

        return transmissionAttempts.toDouble() /
                deliveredPackets.toDouble()
    }

    /*
     * Successful physical transmission attempts.
     *
     * Every successful hop requires exactly
     * one successful attempt.
     */
    fun successfulTransmissionAttempts(): Int {
        return successfulHops
    }

    /*
     * Attempts that failed physically.
     */
    fun failedTransmissionAttempts(): Int {

        return transmissionAttempts -
                successfulHops
    }

    /*
     * First physical attempt for every hop
     * that the simulator tried.
     */
    fun initialTransmissionAttempts(): Int {
        return attemptedHops
    }

    /*
     * Physical attempts made after the first
     * attempt of a hop.
     */
    fun retransmissions(): Int {

        return transmissionAttempts -
                attemptedHops
    }
}