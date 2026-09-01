package com.example.peertopeer.simulation.experiment.record

enum class TopologyEventType {
    LINK_UP,
    LINK_DOWN,
    LINK_WEIGHT_CHANGED
}

data class TopologyEventRecord(
    val runId: String,
    val eventTime: Long,
    val fromNodeId: String,
    val toNodeId: String,
    val eventType: TopologyEventType,
    val oldWeight: Int? = null,
    val newWeight: Int? = null
)
