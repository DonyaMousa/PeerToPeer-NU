package com.example.peertopeer.simulation.experiment.record

enum class RoutingEventType {
    ROUTE_REQUEST,
    ROUTE_FOUND,
    ROUTE_CHANGED,
    NO_ROUTE
}

data class RoutingEventRecord(
    val runId: String,
    val messageId: String?,
    val eventTime: Long,
    val nodeId: String,
    val destinationId: String,
    val eventType: RoutingEventType,
    val path: List<String>? = null,
    val totalCost: Int? = null
)
