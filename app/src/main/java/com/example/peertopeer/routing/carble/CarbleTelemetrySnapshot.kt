package com.example.peertopeer.routing.carble

data class CarbleTelemetrySnapshot(

    val highDecisions: Long,

    val mediumDecisions: Long,

    val lowDecisions: Long,

    val m1Decisions: Long,

    val m2Decisions: Long,

    val m3Decisions: Long,

    val downstreamWarnings: Long,

    val backupPrepared: Long,

    val backupActivations: Long,

    val backupSuccesses: Long,

    val backupFailures: Long,

    val duplicateSuppressions: Long,

    val mediumToHighRecoveries: Long,

    val mediumToLowEscalations: Long,

    val lowToMediumRecoveries: Long,

    val lowToHighRecoveries: Long,

    val carryDecisions: Long,

    val probeDecisions: Long,

    val probeSuccesses: Long,

    val probeFailures: Long,

    val copyBudgetExhaustions: Long,

    val fallbackDrops: Long
)