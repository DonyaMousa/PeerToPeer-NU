package com.example.peertopeer.experiment

data class ExperimentConfig(
    val experimentId: String,
    val baselineId: String,
    val topologyType: String,
    val nodeCount: Int,
    val routeRequestCount: Int,
    val topologyChangeCount: Int,
    val randomSeed: Long? = null,
    val notes: String = ""
)