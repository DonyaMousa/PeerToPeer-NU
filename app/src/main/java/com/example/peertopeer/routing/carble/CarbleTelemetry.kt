package com.example.peertopeer.routing.carble

class CarbleTelemetry {

    var highDecisions:
            Long = 0
        private set

    var mediumDecisions:
            Long = 0
        private set

    var lowDecisions:
            Long = 0
        private set

    var m1Decisions:
            Long = 0
        private set

    var m2Decisions:
            Long = 0
        private set

    var m3Decisions:
            Long = 0
        private set

    var downstreamWarnings:
            Long = 0
        private set

    var backupPrepared:
            Long = 0
        private set

    var backupActivations:
            Long = 0
        private set

    var backupSuccesses:
            Long = 0
        private set

    var backupFailures:
            Long = 0
        private set

    var duplicateSuppressions:
            Long = 0
        private set

    var mediumToHighRecoveries:
            Long = 0
        private set

    var mediumToLowEscalations:
            Long = 0
        private set

    var lowToMediumRecoveries:
            Long = 0
        private set

    var lowToHighRecoveries:
            Long = 0
        private set

    var carryDecisions:
            Long = 0
        private set

    var probeDecisions:
            Long = 0
        private set

    var probeSuccesses:
            Long = 0
        private set

    var probeFailures:
            Long = 0
        private set

    var copyBudgetExhaustions:
            Long = 0
        private set

    var fallbackDrops:
            Long = 0
        private set


    // =====================================================
    // REGIMES
    // =====================================================

    fun recordHighDecision() {
        highDecisions++
    }

    fun recordMediumDecision(
        stage: CarbleMediumStage
    ) {

        mediumDecisions++

        when (
            stage
        ) {

            CarbleMediumStage.M1 ->
                m1Decisions++

            CarbleMediumStage.M2 ->
                m2Decisions++

            CarbleMediumStage.M3 ->
                m3Decisions++
        }
    }

    fun recordLowDecision() {
        lowDecisions++
    }


    // =====================================================
    // MEDIUM
    // =====================================================

    fun recordDownstreamWarning() {
        downstreamWarnings++
    }

    fun recordBackupPrepared() {
        backupPrepared++
    }

    fun recordBackupActivation() {
        backupActivations++
    }

    fun recordBackupSuccess() {
        backupSuccesses++
    }

    fun recordBackupFailure() {
        backupFailures++
    }

    fun recordDuplicateSuppression() {
        duplicateSuppressions++
    }

    fun recordCopyBudgetExhaustion() {
        copyBudgetExhaustions++
    }


    // =====================================================
    // TRANSITIONS
    // =====================================================

    fun recordMediumToHighRecovery() {
        mediumToHighRecoveries++
    }

    fun recordMediumToLowEscalation() {
        mediumToLowEscalations++
    }

    fun recordLowToMediumRecovery() {
        lowToMediumRecoveries++
    }

    fun recordLowToHighRecovery() {
        lowToHighRecoveries++
    }


    // =====================================================
    // LOW
    // =====================================================

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

    fun recordFallbackDrop() {
        fallbackDrops++
    }


    // =====================================================
    // SNAPSHOT
    // =====================================================

    fun snapshot():
            CarbleTelemetrySnapshot {

        return CarbleTelemetrySnapshot(

            highDecisions =
                highDecisions,

            mediumDecisions =
                mediumDecisions,

            lowDecisions =
                lowDecisions,

            m1Decisions =
                m1Decisions,

            m2Decisions =
                m2Decisions,

            m3Decisions =
                m3Decisions,

            downstreamWarnings =
                downstreamWarnings,

            backupPrepared =
                backupPrepared,

            backupActivations =
                backupActivations,

            backupSuccesses =
                backupSuccesses,

            backupFailures =
                backupFailures,

            duplicateSuppressions =
                duplicateSuppressions,

            mediumToHighRecoveries =
                mediumToHighRecoveries,

            mediumToLowEscalations =
                mediumToLowEscalations,

            lowToMediumRecoveries =
                lowToMediumRecoveries,

            lowToHighRecoveries =
                lowToHighRecoveries,

            carryDecisions =
                carryDecisions,

            probeDecisions =
                probeDecisions,

            probeSuccesses =
                probeSuccesses,

            probeFailures =
                probeFailures,

            copyBudgetExhaustions =
                copyBudgetExhaustions,

            fallbackDrops =
                fallbackDrops
        )
    }
}