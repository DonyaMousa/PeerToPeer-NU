package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class B0CombinedStressExperimentTest {

    @Test
    fun combined_stress_exposes_retry_queue_and_topology_pressure_together() {

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-DAY06-V1",

                runId =
                    "B0-COMBINED-STRESS-R001",

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
                        packetCount = 20,

                        /*
                         * Faster than service capacity.
                         */
                        packetInterval = 1,

                        packetTtl = 50,
                        payloadBytes = 32,
                        sourceCount = 1
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1,
                        modelName =
                            "controlled-retry-plus-topology-change"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-E06-COMBINED-STRESS",

                        scenarioName =
                            "Retry degradation, congestion and alternate-route failure",

                        topologyType =
                            "dual-path",

                        nodeCount =
                            5,

                        /*
                         * Deliberately bounded so queue
                         * pressure can become visible.
                         */
                        queueCapacity =
                            5,

                        serviceTime =
                            3,

                        conditionName =
                            "combined-stress",

                        notes =
                            "High offered load; controlled first-attempt " +
                                    "failures on N1-N2; N2-N4 removed at t=15."
                    ),

                notes =
                    "E06 deterministic combined-stress characterization."
            )

        val output =
            B0ExperimentRunner()
                .runCombinedStress(
                    config
                )

        val summary =
            output.summary

        println()
        println(
            "===== B0 E06 COMBINED STRESS ====="
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
            "Failed physical attempts: " +
                    "${summary.failedPhysicalAttempts}"
        )

        println(
            "Retransmissions: ${summary.retransmissions}"
        )

        println()

        println(
            "Queue full events: ${summary.queueFullEvents}"
        )

        println(
            "Maximum queue occupancy: " +
                    "${summary.maximumQueueOccupancy}"
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

        println()

        println(
            "Topology events: ${summary.topologyEvents}"
        )

        println(
            "Link-down events: ${summary.linkDownEvents}"
        )

        println(
            "Cache invalidations: ${summary.cacheInvalidations}"
        )

        println(
            "Route calculations: ${summary.routeCalculations}"
        )

        println(
            "No-route events: ${summary.noRouteEvents}"
        )

        println()

        println(
            "Raw packet records: ${output.packets.size}"
        )

        println(
            "Raw transmission records: " +
                    "${output.transmissions.size}"
        )

        println(
            "Raw queue records: ${output.queueEvents.size}"
        )

        println(
            "Raw topology records: " +
                    "${output.topologyEvents.size}"
        )


        println(
            "======================================="
        )

        // -------------------------------------------------
        // PACKET ACCOUNTING
        // -------------------------------------------------

        assertEquals(
            20,
            summary.generatedPackets
        )

        assertEquals(
            20,
            summary.deliveredPackets +
                    summary.droppedPackets
        )

        // -------------------------------------------------
        // RETRY PRESSURE MUST BE PRESENT
        // -------------------------------------------------

        assertTrue(
            summary.failedPhysicalAttempts > 0L
        )

        assertTrue(
            summary.retransmissions > 0L
        )

        // -------------------------------------------------
        // CONGESTION PRESSURE MUST BE PRESENT
        // -------------------------------------------------

        assertTrue(
            (summary.maximumQueueOccupancy ?: 0) > 1
        )

        assertTrue(
            (summary.maxQueueWait ?: 0L) > 0L
        )

        assertTrue(
            (summary.meanQueueWait ?: 0.0) > 0.0
        )

        assertTrue(
            summary.queueFullEvents > 0L
        )

        // -------------------------------------------------
        // TOPOLOGY PRESSURE MUST BE PRESENT
        // -------------------------------------------------

        assertEquals(
            1L,
            summary.topologyEvents
        )

        assertEquals(
            1L,
            summary.linkDownEvents
        )

        assertEquals(
            1L,
            summary.cacheInvalidations
        )

        /*
         * An alternate route survives, so B0 should not
         * be forced into complete partition behavior.
         */
        assertEquals(
            0L,
            summary.noRouteEvents
        )

        /*
         * E06 is intentionally interaction-heavy.
         *
         * Do NOT freeze exact PDR/latency/count values
         * before observing the simulator output.
         */
        assertTrue(
            summary.deliveredPackets > 0
        )
        println()

        println("Resource samples:")

        output.resourceSamples.forEach {
            println(it)
        }
    }
}
