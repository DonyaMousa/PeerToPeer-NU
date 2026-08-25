package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class B0RetryDegradationExperimentTest {

    @Test
    fun controlled_retry_degradation_increases_transmission_cost_without_route_failure() {

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-DAY06-V1",

                runId =
                    "B0-RETRY-DEGRADATION-R001",

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
                            10,
                        packetTtl =
                            20,
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
                            "controlled-alternating-retry"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-RETRY-DEGRADATION-05",
                        scenarioName =
                            "Five-node line with controlled degraded link",
                        topologyType =
                            "line",
                        nodeCount =
                            5,
                        queueCapacity =
                            10,
                        serviceTime =
                            1,
                        conditionName =
                            "retry-degradation",
                        notes =
                            "N2-N3 first attempt fails for every even-index packet."
                    ),

                notes =
                    "Controlled retry-only characterization."
            )

        val runner =
            B0ExperimentRunner()

        val output =
            runner.runControlledRetryDegradation(
                config
            )

        val summary =
            output.summary

        println()
        println(
            "===== B0 RETRY DEGRADATION ====="
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

        println(
            "Attempts / delivered packet: " +
                    summary.physicalAttemptsPerDeliveredPacket
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

        println(
            "===================================="
        )


        // -------------------------------------------------
        // DELIVERY
        // -------------------------------------------------

        assertEquals(
            20,
            summary.generatedPackets
        )

        assertEquals(
            20,
            summary.deliveredPackets
        )

        assertEquals(
            0,
            summary.droppedPackets
        )

        assertEquals(
            1.0,
            summary.packetDeliveryRatio,
            0.000001
        )


        // -------------------------------------------------
        // ROUTE REMAINS CONNECTED
        // -------------------------------------------------

        assertEquals(
            0L,
            summary.noRouteEvents
        )

        assertEquals(
            0L,
            summary.cacheInvalidations
        )


        // -------------------------------------------------
        // LOGICAL HOPS
        //
        // 20 packets × 4 hops = 80
        // -------------------------------------------------

        assertEquals(
            80L,
            summary.logicalHopAttempts
        )


        // -------------------------------------------------
        // CONTROLLED RETRIES
        //
        // 10 even-index packets receive exactly
        // one additional physical attempt.
        //
        // Healthy baseline:
        // 80 physical attempts
        //
        // Degraded:
        // 80 + 10 = 90
        // -------------------------------------------------

        assertEquals(
            90L,
            summary.physicalAttempts
        )

        assertEquals(
            10L,
            summary.failedPhysicalAttempts
        )

        assertEquals(
            10L,
            summary.retransmissions
        )

        assertEquals(
            4.5,
            summary.physicalAttemptsPerDeliveredPacket!!,
            0.000001
        )


        // -------------------------------------------------
        // CACHE BEHAVIOR SHOULD REMAIN IDENTICAL
        // TO HEALTHY RUN BECAUSE TOPOLOGY DID NOT CHANGE.
        // -------------------------------------------------

        assertEquals(
            76L,
            summary.cacheHits
        )

        assertEquals(
            4L,
            summary.cacheMisses
        )

        assertEquals(
            4L,
            summary.routeCalculations
        )


        // -------------------------------------------------
        // RETRIES MUST INCREASE LATENCY ABOVE
        // THE HEALTHY REFERENCE OF 8 FOR AT LEAST
        // SOME PACKETS.
        // -------------------------------------------------

        assertTrue(
            summary.maxLatency!! > 8L
        )
    }
}
