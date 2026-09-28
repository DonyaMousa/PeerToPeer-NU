package com.example.peertopeer.network.forwarding

sealed class ForwardingDecision {
    data class Forward(
        val nextHopId: String,
        val stage: String,
        val confidence: Double? = null,
        val route: List<String> = emptyList(),
        val routeConfidence: Double? = null,
        val routeCost: Double? = null,
        val routeReason: String? = null,
        val rawStage: String? = null,
        val hysteresisStatus: String? = null
    ) : ForwardingDecision()

    data class ForwardWithBackup(
        val primaryHopId: String,
        val backupHopId: String?,
        val backupDelayMs: Long,
        val stage: String,
        val confidence: Double? = null,
        val route: List<String> = emptyList(),
        val backupRoute: List<String> = emptyList(),
        val routeConfidence: Double? = null,
        val routeCost: Double? = null,
        val routeReason: String? = null,
        val rawStage: String? = null,
        val hysteresisStatus: String? = null
    ) : ForwardingDecision()

    data class Carry(
        val delayMs: Long,
        val stage: String,
        val confidence: Double? = null,
        val route: List<String> = emptyList(),
        val routeConfidence: Double? = null,
        val routeCost: Double? = null,
        val routeReason: String? = null,
        val rawStage: String? = null,
        val hysteresisStatus: String? = null
    ) : ForwardingDecision()

    data class Drop(val reason: String, val stage: String = "DROP") : ForwardingDecision()
}
