package com.example.peertopeer.simulation.experiment.record

data class RunRecord(
    val experimentSetId: String,
    val runId: String,
    val protocol: String,
    val protocolVersion: String,
    val runIndex: Int,
    val seed: Long,
    val scenarioId: String,
    val topologyType: String,
    val nodeCount: Int,
    val packetCount: Int,
    val packetInterval: Long,
    val payloadBytes: Int,
    val packetTtl: Int,
    val queueCapacity: Int,
    val serviceTime: Long,
    val maxLinkAttempts: Int,
    val retryDelay: Long,
    val linkModel: String,
    val gitCommit: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val notes: String = ""
)
