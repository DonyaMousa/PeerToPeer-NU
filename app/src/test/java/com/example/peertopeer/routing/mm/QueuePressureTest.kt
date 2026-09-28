package com.example.peertopeer.routing.mm

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePressureTest {
    @Test
    fun physicalRuntimeUsesAdaptiveTrafficScale() {
        val state = link(queueDepth = 4, capacity = 200, adaptiveK = 4.0)

        assertEquals(0.5, QueuePressure.normalized(state), 0.000001)
    }

    @Test
    fun simulationKeepsFrozenCapacityNormalization() {
        val state = link(queueDepth = 4, capacity = 10, adaptiveK = null)

        assertEquals(0.4, QueuePressure.normalized(state), 0.000001)
    }

    private fun link(queueDepth: Int, capacity: Int, adaptiveK: Double?) = MultiMetricLinkState(
        fromNodeId = "AAAAAAAA",
        toNodeId = "BBBBBBBB",
        successRate = 1.0,
        observedDelay = 1.0,
        delayReference = 10.0,
        queueOccupancy = queueDepth,
        queueCapacity = capacity,
        recentLinkChanges = 0,
        instabilityReference = 5,
        adaptiveQueueScaleK = adaptiveK
    )
}
