package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.MMExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MMStochasticSmokeTest {

    private val runner =
        MMExperimentRunner()

    // =====================================================
    // S01 — RELIABILITY
    // =====================================================

    @Test
    fun mm_s01_reliability_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "MM-SMOKE",
                runId = "MM-S01-SEED-1",
                protocol = "MM",
                protocolVersion = "MM-CANDIDATE",
                runIndex = 1,
                seed = 1L,

                scenario =
                    ScenarioConfig(
                        scenarioId = "S01",
                        scenarioName = "Reliability only",
                        topologyType = "line",
                        nodeCount = 5,
                        queueCapacity = 20,
                        serviceTime = 1L,
                        conditionName = "reliability",
                        notes = ""
                    ),

                traffic =
                    TrafficConfig(
                        packetCount = 100,
                        packetInterval = 10L,
                        packetTtl = 30,
                        payloadBytes = 32
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1L,
                        modelName = "seeded-bernoulli",
                        successProbability = 0.80
                    ),

                gitCommit = ""
            )

        val output =
            runner.runSeededRetryScenario(
                config
            )

        assertBasicAccounting(
            config = config,
            generated = output.summary.generatedPackets,
            delivered = output.summary.deliveredPackets,
            dropped = output.summary.droppedPackets
        )

        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.routingEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        /*
         * S01 has no topology dynamics.
         */
        assertEquals(
            0L,
            output.summary.topologyEvents
        )

        assertTrue(
            output.topologyEvents.isEmpty()
        )
    }

    // =====================================================
    // S02 — TOPOLOGY
    // =====================================================

    @Test
    fun mm_s02_topology_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "MM-SMOKE",
                runId = "MM-S02-SEED-1",
                protocol = "MM",
                protocolVersion = "MM-CANDIDATE",
                runIndex = 1,
                seed = 1L,

                scenario =
                    ScenarioConfig(
                        scenarioId = "S02",
                        scenarioName = "Topology only",
                        topologyType = "dual-path",
                        nodeCount = 5,
                        queueCapacity = 20,
                        serviceTime = 1L,
                        conditionName = "topology",
                        notes = "",
                        topologyFailureProbability = 0.30,
                        topologyDecisionTimes =
                            listOf(
                                50L,
                                100L,
                                150L,
                                200L,
                                250L,
                                300L,
                                350L,
                                400L
                            )
                    ),

                traffic =
                    TrafficConfig(
                        packetCount = 50,
                        packetInterval = 10L,
                        packetTtl = 30,
                        payloadBytes = 32
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1L,
                        modelName = "perfect",
                        successProbability = null
                    ),

                gitCommit = ""
            )

        val output =
            runner.runSeededTopologyScenario(
                config
            )

        assertBasicAccounting(
            config = config,
            generated = output.summary.generatedPackets,
            delivered = output.summary.deliveredPackets,
            dropped = output.summary.droppedPackets
        )

        assertTrue(
            output.routingEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        /*
         * A single stochastic seed may legitimately produce
         * zero topology state changes.
         *
         * Therefore we do not require topologyEvents > 0.
         *
         * Instead, verify that raw and aggregated topology
         * accounting agree.
         */
        assertEquals(
            output.topologyEvents.size.toLong(),
            output.summary.topologyEvents
        )
    }

    // =====================================================
    // S03 — CONGESTION
    // =====================================================

    @Test
    fun mm_s03_congestion_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "MM-SMOKE",
                runId = "MM-S03-SEED-1",
                protocol = "MM",
                protocolVersion = "MM-CANDIDATE",
                runIndex = 1,
                seed = 1L,

                scenario =
                    ScenarioConfig(
                        scenarioId = "S03",
                        scenarioName = "Congestion only",
                        topologyType = "line",
                        nodeCount = 5,
                        queueCapacity = 5,
                        serviceTime = 3L,
                        conditionName = "congestion",
                        notes = ""
                    ),

                traffic =
                    TrafficConfig(
                        packetCount = 100,
                        packetInterval = 10L,
                        packetTtl = 30,
                        payloadBytes = 32,
                        burstProbability = 0.30,
                        burstSize = 5,
                        burstSpacing = 0L
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1L,
                        modelName = "perfect",
                        successProbability = null
                    ),

                gitCommit = ""
            )

        val output =
            runner.runSeededCongestionScenario(
                config
            )

        assertBasicAccounting(
            config = config,
            generated = output.summary.generatedPackets,
            delivered = output.summary.deliveredPackets,
            dropped = output.summary.droppedPackets
        )

        assertTrue(
            output.queueEvents.isNotEmpty()
        )

        assertTrue(
            output.routingEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        /*
         * S03 has no topology dynamics.
         */
        assertEquals(
            0L,
            output.summary.topologyEvents
        )

        assertTrue(
            output.topologyEvents.isEmpty()
        )
    }

    // =====================================================
    // S04 — RELIABILITY + TOPOLOGY
    // =====================================================

    @Test
    fun mm_s04_reliability_topology_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "MM-SMOKE",
                runId = "MM-S04-SEED-1",
                protocol = "MM",
                protocolVersion = "MM-CANDIDATE",
                runIndex = 1,
                seed = 1L,

                scenario =
                    ScenarioConfig(
                        scenarioId = "S04",
                        scenarioName = "Reliability + topology",
                        topologyType = "dual-path",
                        nodeCount = 5,
                        queueCapacity = 20,
                        serviceTime = 1L,
                        conditionName = "reliability-topology",
                        notes = "",
                        topologyFailureProbability = 0.30,
                        topologyDecisionTimes =
                            listOf(
                                50L,
                                100L,
                                150L,
                                200L,
                                250L,
                                300L,
                                350L,
                                400L
                            )
                    ),

                traffic =
                    TrafficConfig(
                        packetCount = 100,
                        packetInterval = 10L,
                        packetTtl = 30,
                        payloadBytes = 32
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1L,
                        modelName = "seeded-bernoulli",
                        successProbability = 0.80
                    ),

                gitCommit = ""
            )

        val output =
            runner.runSeededReliabilityTopologyScenario(
                config
            )

        assertBasicAccounting(
            config = config,
            generated = output.summary.generatedPackets,
            delivered = output.summary.deliveredPackets,
            dropped = output.summary.droppedPackets
        )

        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.routingEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        assertEquals(
            output.topologyEvents.size.toLong(),
            output.summary.topologyEvents
        )
    }

    // =====================================================
    // S05 — COMBINED
    // =====================================================

    @Test
    fun mm_s05_combined_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "MM-SMOKE",
                runId = "MM-S05-SEED-1",
                protocol = "MM",
                protocolVersion = "MM-CANDIDATE",
                runIndex = 1,
                seed = 1L,

                scenario =
                    ScenarioConfig(
                        scenarioId = "S05",
                        scenarioName = "Combined stress",
                        topologyType = "dual-path",
                        nodeCount = 5,
                        queueCapacity = 5,
                        serviceTime = 3L,
                        conditionName = "combined",
                        notes = "",
                        topologyFailureProbability = 0.30,
                        topologyDecisionTimes =
                            listOf(
                                50L,
                                100L,
                                150L,
                                200L,
                                250L,
                                300L,
                                350L,
                                400L
                            )
                    ),

                traffic =
                    TrafficConfig(
                        packetCount = 100,
                        packetInterval = 10L,
                        packetTtl = 30,
                        payloadBytes = 32,
                        burstProbability = 0.30,
                        burstSize = 5,
                        burstSpacing = 0L
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1L,
                        modelName = "seeded-bernoulli",
                        successProbability = 0.80
                    ),

                gitCommit = ""
            )

        val output =
            runner.runSeededCombinedScenario(
                config
            )

        assertBasicAccounting(
            config = config,
            generated = output.summary.generatedPackets,
            delivered = output.summary.deliveredPackets,
            dropped = output.summary.droppedPackets
        )

        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.queueEvents.isNotEmpty()
        )

        assertTrue(
            output.routingEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        assertEquals(
            output.topologyEvents.size.toLong(),
            output.summary.topologyEvents
        )
    }

    // =====================================================
    // COMMON CHECK
    // =====================================================

    private fun assertBasicAccounting(
        config: ExperimentConfig,
        generated: Int,
        delivered: Int,
        dropped: Int
    ) {

        assertEquals(
            config.traffic.packetCount,
            generated
        )

        assertEquals(
            generated,
            delivered + dropped
        )
    }
}