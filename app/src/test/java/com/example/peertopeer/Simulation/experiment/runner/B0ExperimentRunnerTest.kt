package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class B0ExperimentRunnerTest {

    @Test
    fun healthy_line_run_produces_valid_research_summary() {

        // =====================================================
        // 1. EXPERIMENT CONFIGURATION
        // =====================================================

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-DAY06-V1",

                runId =
                    "B0-HEALTHY-LINE-R001",

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

                        /*
                         * Deliberately light load.
                         *
                         * We first want a healthy reference
                         * run before introducing congestion.
                         */
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
                            "deterministic-healthy"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-HEALTHY-LINE-05",

                        scenarioName =
                            "Healthy five-node line",

                        topologyType =
                            "line",

                        nodeCount =
                            5,

                        queueCapacity =
                            10,

                        serviceTime =
                            1,

                        conditionName =
                            "healthy",

                        notes =
                            "Five-node deterministic healthy reference topology."
                    ),

                gitCommit =
                    null,

                notes =
                    "Day 6 first research-configured B0 run."
            )


        // =====================================================
        // 2. RUN EXPERIMENT
        // =====================================================

        val runner =
            B0ExperimentRunner()

        val output =
            runner.runHealthyLine(
                config
            )

        val summary =
            output.summary


        // =====================================================
        // 3. PRINT RAW RESEARCH SUMMARY
        // =====================================================

        println()
        println(
            "===== B0 HEALTHY LINE RUN ====="
        )

        println(
            "Run ID: ${summary.runId}"
        )

        println(
            "Protocol: ${summary.protocol}"
        )

        println(
            "Scenario: ${summary.scenarioId}"
        )

        println(
            "Seed: ${summary.seed}"
        )

        println()

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
            "Logical hop attempts: " +
                    summary.logicalHopAttempts
        )

        println(
            "Physical attempts: " +
                    summary.physicalAttempts
        )

        println(
            "Successful physical attempts: " +
                    summary.successfulPhysicalAttempts
        )

        println(
            "Failed physical attempts: " +
                    summary.failedPhysicalAttempts
        )

        println(
            "Retransmissions: " +
                    summary.retransmissions
        )

        println(
            "Physical attempts / delivered packet: " +
                    summary.physicalAttemptsPerDeliveredPacket
        )

        println()

        println(
            "Useful delivered bytes: " +
                    summary.usefulDeliveredBytes
        )

        println(
            "Attempts / useful delivered byte: " +
                    summary.physicalAttemptsPerUsefulDeliveredByte
        )

        println()

        println(
            "Route requests: " +
                    summary.routeRequests
        )

        println(
            "Routes found: " +
                    summary.routesFound
        )

        println(
            "No-route events: " +
                    summary.noRouteEvents
        )
        println()

        println(
            "Cache hits: " +
                    summary.cacheHits
        )

        println(
            "Cache misses: " +
                    summary.cacheMisses
        )

        println(
            "Route calculations: " +
                    summary.routeCalculations
        )

        println(
            "Cache invalidations: " +
                    summary.cacheInvalidations
        )

        println(
            "Successful route calculations: " +
                    summary.successfulRouteCalculations
        )

        println(
            "Unreachable route calculations: " +
                    summary.unreachableRouteCalculations
        )

        println()

        println(
            "Packet records: " +
                    output.packets.size
        )

        println(
            "Transmission records: " +
                    output.transmissions.size
        )

        println(
            "Routing records: " +
                    output.routingEvents.size
        )

        println(
            "================================="
        )


        // =====================================================
        // 4. HARD EXPECTATIONS
        // =====================================================
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

        assertEquals(
            0L,
            summary.cacheInvalidations
        )

        assertEquals(
            4L,
            summary.successfulRouteCalculations
        )

        assertEquals(
            0L,
            summary.unreachableRouteCalculations
        )

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


        // =====================================================
        // 5. FIVE-NODE LINE EXPECTATION
        //
        // N0 -> N1 -> N2 -> N3 -> N4
        //
        // = 4 logical hops per delivered packet
        //
        // 20 packets × 4 hops = 80 logical hops.
        // =====================================================

        assertEquals(
            80L,
            summary.logicalHopAttempts
        )

        /*
         * Healthy deterministic links:
         *
         * one physical attempt per logical hop.
         */
        assertEquals(
            80L,
            summary.physicalAttempts
        )

        assertEquals(
            80L,
            summary.successfulPhysicalAttempts
        )

        assertEquals(
            0L,
            summary.failedPhysicalAttempts
        )

        assertEquals(
            0L,
            summary.retransmissions
        )


        // =====================================================
        // 6. RESOURCE PROXY
        // =====================================================

        assertEquals(
            4.0,
            summary.physicalAttemptsPerDeliveredPacket!!,
            0.000001
        )

        /*
         * 20 delivered packets × 32 payload bytes.
         */
        assertEquals(
            640L,
            summary.usefulDeliveredBytes
        )


        // =====================================================
        // 7. PACKET ACCOUNTING
        // =====================================================

        assertEquals(
            20,
            output.packets.size
        )

        assertTrue(
            output.packets.all {
                it.delivered
            }
        )

        assertTrue(
            output.packets.none {
                it.dropped
            }
        )


        // =====================================================
        // 8. TIMING
        // =====================================================

        assertNotNull(
            summary.meanLatency
        )

        assertNotNull(
            summary.p50Latency
        )

        assertNotNull(
            summary.p95Latency
        )

        assertNotNull(
            summary.p99Latency
        )

        assertNotNull(
            summary.maxLatency
        )


        // =====================================================
        // 9. HEALTHY CONDITION SHOULD HAVE NO ROUTE FAILURE
        // =====================================================

        assertEquals(
            0,
            summary.noRouteDrops
        )

        assertEquals(
            0,
            summary.retryExhaustedDrops
        )

        assertEquals(
            0,
            summary.queueFullDrops
        )

        assertEquals(
            0,
            summary.ttlExpiredDrops
        )

        assertEquals(
            0,
            summary.linkUnavailableDrops
        )

        assertEquals(
            0L,
            summary.noRouteEvents
        )
    }
}
