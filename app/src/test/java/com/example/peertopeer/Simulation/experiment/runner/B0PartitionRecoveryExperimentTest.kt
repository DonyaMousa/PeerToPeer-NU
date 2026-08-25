package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class B0PartitionRecoveryExperimentTest {

    @Test
    fun partition_causes_no_route_drops_and_recovery_restores_delivery() {

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-DAY06-V1",

                runId =
                    "B0-PARTITION-RECOVERY-R001",

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
                        packetInterval = 10,
                        packetTtl = 30,
                        payloadBytes = 32,
                        sourceCount = 1
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1,
                        modelName =
                            "deterministic-partition-recovery"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-E05-PARTITION-RECOVERY",

                        scenarioName =
                            "Four-node line with temporary partition",

                        topologyType =
                            "line",

                        nodeCount =
                            4,

                        queueCapacity =
                            10,

                        serviceTime =
                            1,

                        conditionName =
                            "partition-recovery",

                        notes =
                            "N2-N3 removed at t=20 and restored at t=50."
                    ),

                notes =
                    "E05 deterministic partition and recovery characterization."
            )

        val output =
            B0ExperimentRunner()
                .runPartitionRecovery(
                    config
                )

        val summary =
            output.summary

        println()
        println(
            "===== B0 E05 PARTITION + RECOVERY ====="
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
            "NO_ROUTE drops: ${summary.noRouteDrops}"
        )

        println(
            "No-route events: ${summary.noRouteEvents}"
        )

        println(
            "Mean failure termination: " +
                    "${summary.meanFailureTerminationTime}"
        )

        println(
            "Max failure termination: " +
                    "${summary.maxFailureTerminationTime}"
        )

        println()

        println(
            "Route requests: ${summary.routeRequests}"
        )

        println(
            "Routes found: ${summary.routesFound}"
        )

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
            "Successful calculations: " +
                    "${summary.successfulRouteCalculations}"
        )

        println(
            "Unreachable calculations: " +
                    "${summary.unreachableRouteCalculations}"
        )

        println()

        println(
            "Topology events: ${summary.topologyEvents}"
        )

        println(
            "Link-down events: ${summary.linkDownEvents}"
        )

        println(
            "Link-up events: ${summary.linkUpEvents}"
        )

        println()

        println("Topology records:")

        output.topologyEvents.forEach {
            println(it)
        }

        println(
            "=========================================="
        )

        /*
         * Traffic times:
         *
         * t=0,10       connected
         * t=20,30,40   partitioned
         * t=50..190    recovered
         *
         * Therefore:
         *
         * delivered = 17
         * dropped   = 3
         */
        assertEquals(
            20,
            summary.generatedPackets
        )

        assertEquals(
            17,
            summary.deliveredPackets
        )

        assertEquals(
            3,
            summary.droppedPackets
        )

        assertEquals(
            0.85,
            summary.packetDeliveryRatio,
            0.000001
        )

        // -------------------------------------------------
        // PARTITION FAILURE REASON
        // -------------------------------------------------

        assertEquals(
            3,
            summary.noRouteDrops
        )

        assertEquals(
            3L,
            summary.noRouteEvents
        )

        // -------------------------------------------------
        // TOPOLOGY EVENT ACCOUNTING
        // -------------------------------------------------

        assertEquals(
            2L,
            summary.topologyEvents
        )

        assertEquals(
            1L,
            summary.linkDownEvents
        )

        assertEquals(
            1L,
            summary.linkUpEvents
        )

        assertEquals(
            2,
            output.topologyEvents.size
        )

        // -------------------------------------------------
        // TWO TOPOLOGY VERSIONS CHANGED
        // -------------------------------------------------

        assertEquals(
            2L,
            summary.cacheInvalidations
        )

        /*
         * Initial connected state:
         *   3 calculations
         *
         * Partition:
         *   t20, t30, t40 each calculates N0->N3
         *   and discovers no route = 3
         *
         * Recovery:
         *   3 fresh calculations
         *
         * Total = 9.
         */
        assertEquals(
            9L,
            summary.routeCalculations
        )

        assertEquals(
            9L,
            summary.cacheMisses
        )

        assertEquals(
            6L,
            summary.successfulRouteCalculations
        )

        assertEquals(
            3L,
            summary.unreachableRouteCalculations
        )

        // Links themselves are otherwise healthy.
        assertEquals(
            0L,
            summary.retransmissions
        )

        // Recovery should allow substantial delivery again.
        assertTrue(
            summary.deliveredPackets >
                    summary.droppedPackets
        )
    }
}
