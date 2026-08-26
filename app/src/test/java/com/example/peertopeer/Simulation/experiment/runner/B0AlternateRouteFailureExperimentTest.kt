package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class B0AlternateRouteFailureExperimentTest {

    @Test
    fun alternate_route_failure_invalidates_cache_and_preserves_delivery() {

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-DAY06-V1",

                runId =
                    "B0-ALTERNATE-FAILURE-R001",

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
                            "deterministic-healthy-dynamic-topology"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-E04-ALTERNATE-ROUTE",

                        scenarioName =
                            "Primary path failure with surviving alternate route",

                        topologyType =
                            "dual-path",

                        nodeCount =
                            5,

                        queueCapacity =
                            10,

                        serviceTime =
                            1,

                        conditionName =
                            "alternate-route-failure",

                        notes =
                            "N2-N4 removed at t=27. " +
                                    "Alternate N1-N3-N4 remains available."
                    ),

                notes =
                    "E04 deterministic topology-change characterization."
            )

        val output =
            B0ExperimentRunner()
                .runAlternateRouteFailure(
                    config
                )

        val summary =
            output.summary

        println()
        println(
            "===== B0 E04 ALTERNATE-ROUTE FAILURE ====="
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
            "P95 latency: ${summary.p95Latency}"
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
            "Retransmissions: ${summary.retransmissions}"
        )

        println()

        println(
            "Route requests: ${summary.routeRequests}"
        )

        println(
            "Routes found: ${summary.routesFound}"
        )

        println(
            "No-route events: ${summary.noRouteEvents}"
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
            "Route changes: ${summary.routeChanges}"
        )

        println()

        println(
            "Topology events: ${summary.topologyEvents}"
        )

        println(
            "Link-down events: ${summary.linkDownEvents}"
        )

        println(
            "Raw topology records: ${output.topologyEvents.size}"
        )

        println()

        println("Topology records:")

        output.topologyEvents.forEach {
            println(it)
        }

        println(
            "============================================="
        )

        // -------------------------------------------------
        // DELIVERY SHOULD SURVIVE THE FAILURE
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
        // FAILURE IS TOPOLOGICAL, NOT RADIO RETRY FAILURE
        // -------------------------------------------------

        assertEquals(
            0L,
            summary.retransmissions
        )

        assertEquals(
            0L,
            summary.failedPhysicalAttempts
        )

        assertEquals(
            0L,
            summary.noRouteEvents
        )

        // -------------------------------------------------
        // ONE LINK-DOWN EVENT
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
            1,
            output.topologyEvents.size
        )

        // -------------------------------------------------
        // TOPOLOGY CHANGE MUST INVALIDATE CACHE
        // -------------------------------------------------

        assertEquals(
            1L,
            summary.cacheInvalidations
        )

        /*
         * Three route calculations are needed for the
         * original three-hop path.
         *
         * After topology invalidation, the new three-hop
         * path requires fresh calculations again.
         */
        assertEquals(
            6L,
            summary.routeCalculations
        )

        assertEquals(
            6L,
            summary.cacheMisses
        )

        assertTrue(
            summary.cacheHits > 0L
        )

        // -------------------------------------------------
        // VERIFY BOTH PRIMARY AND BACKUP LINKS WERE USED
        // -------------------------------------------------

        val usedPrimaryFinalHop =
            output.transmissions.any {
                it.fromNodeId == "N2" &&
                        it.toNodeId == "N4" &&
                        it.success
            }

        val usedBackupFinalHop =
            output.transmissions.any {
                it.fromNodeId == "N3" &&
                        it.toNodeId == "N4" &&
                        it.success
            }

        assertTrue(
            usedPrimaryFinalHop
        )

        assertTrue(
            usedBackupFinalHop
        )
    }
}