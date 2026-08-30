package com.example.peertopeer.routing.hybrid

data class TwoRegimeTelemetrySnapshot(

    val highDecisions: Long,

    val lowDecisions: Long,

    val carryDecisions: Long,

    val probeDecisions: Long,

    val probeSuccesses: Long,

    val probeFailures: Long,

    val lowToHighRecoveries: Long,

    val fallbackDrops: Long
)