package com.example.peertopeer.simulation.experiment.record

enum class QueueEventType {
    ENQUEUED,
    DEQUEUED,
    DROPPED_FULL
}

data class QueueEventRecord(
    val runId: String,
    val messageId: String,
    val nodeId: String,
    val eventTime: Long,
    val eventType: QueueEventType,
    val queueSizeAfterEvent: Int,
    val waitTime: Long? = null
)
