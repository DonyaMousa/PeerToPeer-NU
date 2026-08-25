package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class B0CongestionExperimentTest {

    @Test
    fun controlled_congestion_causes_queue_growth_and_queue_pressure() {

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-DAY06-V1",

                runId =
                    "B0-CONGESTION-R001",

                protocol =
                    "B0",

                protocolVersion =
                    "B0-FREEZE-CANDIDATE",

                runIndex =
                    1,

                seed =
                    1L,

                traffic =
                    TrafficConfig(
                        packetCount =
                            20,

                        packetInterval =
                            1,

                        packetTtl =
                            50,

                        payloadBytes =
                            32,

                        sourceCount =
                            1
                    ),

                link =
                    LinkConfig(
                        maxAttempts =
                            3,

                        retryDelay =
                            1,

                        modelName =
                            "deterministic-healthy"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-CONGESTION-05",

                        scenarioName =
                            "Five-node line under high offered load",

                        topologyType =
                            "line",

                        nodeCount =
                            5,

                        queueCapacity =
                            5,

                        serviceTime =
                            3,

                        conditionName =
                            "controlled-congestion",

                        notes =
                            "Healthy links and static topology. " +
                                    "Traffic interval 1 with service time 3."
                    ),

                notes =
                    "Controlled congestion-only characterization."
            )

        val runner =
            B0ExperimentRunner()

        val output =
            runner.runControlledCongestion(
                config
            )

        val summary =
            output.summary

        println()
        println(
            "===== B0 CONTROLLED CONGESTION ====="
        )

        println(
            "Generated: ${summary.generatedPackets}"
        )

        println(
            "Delivered: ${summary.deliveredPackets}"
        )

        println(
            "Dropped: ${summary.droppedPackets}"
        )

        println(
            "PDR: ${summary.packetDeliveryRatio}"
        )

        println()

        println(
            "Mean latency: ${summary.meanLatency}"
        )

        println(
            "P50 latency: ${summary.p50Latency}"
        )

        println(
            "P95 latency: ${summary.p95Latency}"
        )

        println(
            "P99 latency: ${summary.p99Latency}"
        )

        println(
            "Max latency: ${summary.maxLatency}"
        )

        println()

        println(
            "Logical hops: ${summary.logicalHopAttempts}"
        )

        println(
            "Physical attempts: ${summary.physicalAttempts}"
        )

        println(
            "Failed physical attempts: ${summary.failedPhysicalAttempts}"
        )

        println(
            "Retransmissions: ${summary.retransmissions}"
        )

        println()

        println(
            "Queue enqueue events: ${summary.queueEnqueueEvents}"
        )

        println(
            "Queue dequeue events: ${summary.queueDequeueEvents}"
        )

        println(
            "Queue full events: ${summary.queueFullEvents}"
        )

        println(
            "Maximum queue occupancy: ${summary.maximumQueueOccupancy}"
        )

        println(
            "Mean queue wait: ${summary.meanQueueWait}"
        )

        println(
            "P95 queue wait: ${summary.p95QueueWait}"
        )

        println(
            "Max queue wait: ${summary.maxQueueWait}"
        )

        println(
            "Raw queue records: ${output.queueEvents.size}"
        )

        println()

        println(
            "Cache hits: ${summary.cacheHits}"
        )

        println(
            "Cache misses: ${summary.cacheMisses}"
        )

        println(
            "Route calculations: ${summary.routeCalculations}"
        )

        println(
            "Cache invalidations: ${summary.cacheInvalidations}"
        )

        println(
            "No-route events: ${summary.noRouteEvents}"
        )

        println(
            "======================================"
        )


        // Healthy topology remains available.
        assertEquals(
            0L,
            summary.noRouteEvents
        )

        assertEquals(
            0L,
            summary.cacheInvalidations
        )

        // Healthy links mean there should be no retry cost.
        assertEquals(
            0L,
            summary.failedPhysicalAttempts
        )

        assertEquals(
            0L,
            summary.retransmissions
        )

        // Congestion must actually be visible.
        assertTrue(
            (summary.maximumQueueOccupancy ?: 0) > 1
        )

        assertTrue(
            (summary.maxQueueWait ?: 0L) > 0L
        )

        assertTrue(
            (summary.meanQueueWait ?: 0.0) > 0.0
        )
        // We deliberately use a bounded queue under overload.
        assertTrue(
            summary.queueFullEvents > 0L
        )

        assertTrue(
            summary.droppedPackets > 0
        )

        assertTrue(
            summary.packetDeliveryRatio < 1.0
        )
    }
}
