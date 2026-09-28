package com.example.peertopeer.routing.mm

/** Shared queue normalization for MM cost and CARBLE timeliness. */
object QueuePressure {
    fun normalized(state: MultiMetricLinkState): Double {
        val depth = state.queueOccupancy.coerceAtLeast(0).toDouble()
        val adaptiveK = state.adaptiveQueueScaleK
        return if (adaptiveK != null) {
            if (depth == 0.0) 0.0 else depth / (depth + adaptiveK)
        } else {
            depth / state.queueCapacity.toDouble()
        }.coerceIn(0.0, 1.0)
    }
}
