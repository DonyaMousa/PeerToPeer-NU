package com.example.peertopeer.routing.hybrid

class TwoRegimeTelemetry {

    var highDecisions: Long = 0L
        private set

    var lowDecisions: Long = 0L
        private set

    var carryDecisions: Long = 0L
        private set

    var probeDecisions: Long = 0L
        private set

    var probeSuccesses: Long = 0L
        private set

    var probeFailures: Long = 0L
        private set

    var lowToHighRecoveries: Long = 0L
        private set

    var fallbackDrops: Long = 0L
        private set


    fun recordHighDecision() {
        highDecisions++
    }


    fun recordLowDecision() {
        lowDecisions++
    }


    fun recordCarryDecision() {
        carryDecisions++
    }


    fun recordProbeDecision() {
        probeDecisions++
    }


    fun recordProbeSuccess() {
        probeSuccesses++
    }


    fun recordProbeFailure() {
        probeFailures++
    }


    fun recordLowToHighRecovery() {
        lowToHighRecoveries++
    }


    fun recordFallbackDrop() {
        fallbackDrops++
    }


    // =====================================================
    // IMMUTABLE EXPERIMENT SNAPSHOT
    // =====================================================

    fun snapshot():
            TwoRegimeTelemetrySnapshot {

        return TwoRegimeTelemetrySnapshot(

            highDecisions =
                highDecisions,

            lowDecisions =
                lowDecisions,

            carryDecisions =
                carryDecisions,

            probeDecisions =
                probeDecisions,

            probeSuccesses =
                probeSuccesses,

            probeFailures =
                probeFailures,

            lowToHighRecoveries =
                lowToHighRecoveries,

            fallbackDrops =
                fallbackDrops
        )
    }
}