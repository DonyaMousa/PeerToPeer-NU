package com.example.peertopeer.simulation.experiment.record

data class TransmissionRecord(
    val runId: String,
    val messageId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val attemptNumber: Int,
    val attemptTime: Long,
    val success: Boolean,
    val logicalHopIndex: Int? = null
)
