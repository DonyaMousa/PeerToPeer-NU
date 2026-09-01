package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.CarbleExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbleStochasticSmokeTest {

    private val runner =
        CarbleExperimentRunner()

    // =====================================================
    // S01 — RELIABILITY
    // =====================================================

    @Test
    fun carble_s01_reliability_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "CARBLE-SMOKE",
                runId = "CARBLE-S01-SEED-1",
                protocol = "CARBLE",
                protocolVersion = "CARBLE-v1.0-CANDIDATE",
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
            config,
            output.summary.generatedPackets,
            output.summary.deliveredPackets,
            output.summary.droppedPackets
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

        assertAdaptationAccounting(
            output
        )

        printRun(
            scenario = "S01",
            output = output
        )
    }


    // =====================================================
    // S02 — TOPOLOGY
    // =====================================================

    @Test
    fun carble_s02_topology_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "CARBLE-SMOKE",
                runId = "CARBLE-S02-SEED-1",
                protocol = "CARBLE",
                protocolVersion = "CARBLE-v1.0-CANDIDATE",
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
            config,
            output.summary.generatedPackets,
            output.summary.deliveredPackets,
            output.summary.droppedPackets
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
         * Do not require topologyEvents > 0 for a single
         * seed. A probabilistic topology schedule may
         * produce no actual state transition for seed 1.
         */
        assertAdaptationAccounting(
            output
        )

        printRun(
            scenario = "S02",
            output = output
        )
    }


    // =====================================================
    // S03 — CONGESTION
    // =====================================================

    @Test
    fun carble_s03_congestion_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "CARBLE-SMOKE",
                runId = "CARBLE-S03-SEED-1",
                protocol = "CARBLE",
                protocolVersion = "CARBLE-v1.0-CANDIDATE",
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
            config,
            output.summary.generatedPackets,
            output.summary.deliveredPackets,
            output.summary.droppedPackets
        )

        assertTrue(
            output.queueEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        assertAdaptationAccounting(
            output
        )

        printRun(
            scenario = "S03",
            output = output
        )
    }


    // =====================================================
    // S04 — RELIABILITY + TOPOLOGY
    // =====================================================

    @Test
    fun carble_s04_reliability_topology_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "CARBLE-SMOKE",
                runId = "CARBLE-S04-SEED-1",
                protocol = "CARBLE",
                protocolVersion = "CARBLE-v1.0-CANDIDATE",
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
            config,
            output.summary.generatedPackets,
            output.summary.deliveredPackets,
            output.summary.droppedPackets
        )

        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        assertAdaptationAccounting(
            output
        )

        printRun(
            scenario = "S04",
            output = output
        )
    }


    // =====================================================
    // S05 — COMBINED
    // =====================================================

    @Test
    fun carble_s05_combined_runs_successfully() {

        val config =
            ExperimentConfig(
                experimentSetId = "CARBLE-SMOKE",
                runId = "CARBLE-S05-SEED-1",
                protocol = "CARBLE",
                protocolVersion = "CARBLE-v1.0-CANDIDATE",
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
            config,
            output.summary.generatedPackets,
            output.summary.deliveredPackets,
            output.summary.droppedPackets
        )

        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.queueEvents.isNotEmpty()
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            output.summary.cacheHits
        )

        assertAdaptationAccounting(
            output
        )

        printRun(
            scenario = "S05",
            output = output
        )
    }


    // =====================================================
    // COMMON PACKET ACCOUNTING
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


    // =====================================================
    // CARBLE ADAPTATION ACCOUNTING
    // =====================================================

    private fun assertAdaptationAccounting(
        output:
        CarbleExperimentRunner.RunOutput
    ) {

        val a =
            output.adaptation

        /*
         * Every MEDIUM decision must belong to exactly one
         * of M1, M2 or M3.
         */
        assertEquals(
            a.mediumDecisions,
            a.m1Decisions +
                    a.m2Decisions +
                    a.m3Decisions
        )

        /*
         * Adaptation counters are event counts and must
         * never become negative.
         */
        val counters =
            listOf(
                a.highDecisions,
                a.mediumDecisions,
                a.lowDecisions,
                a.m1Decisions,
                a.m2Decisions,
                a.m3Decisions,
                a.downstreamWarnings,
                a.backupPrepared,
                a.backupActivations,
                a.backupSuccesses,
                a.backupFailures,
                a.duplicateSuppressions,
                a.mediumToHighRecoveries,
                a.mediumToLowEscalations,
                a.lowToMediumRecoveries,
                a.lowToHighRecoveries,
                a.carryDecisions,
                a.probeDecisions,
                a.probeSuccesses,
                a.probeFailures,
                a.copyBudgetExhaustions,
                a.fallbackDrops
            )

        assertTrue(
            counters.all {
                it >= 0L
            }
        )

        /*
         * A backup cannot be physically activated more
         * times than CARBLE prepared one.
         */
        assertTrue(
            a.backupActivations <=
                    a.backupPrepared
        )

        /*
         * Successful/failed backup outcomes cannot exceed
         * the number of actual backup activations.
         */
        assertTrue(
            a.backupSuccesses <=
                    a.backupActivations
        )

        assertTrue(
            a.backupFailures <=
                    a.backupActivations
        )

        /*
         * Probe outcomes likewise cannot exceed the number
         * of probe actions.
         */
        assertTrue(
            a.probeSuccesses <=
                    a.probeDecisions
        )

        assertTrue(
            a.probeFailures <=
                    a.probeDecisions
        )
    }


    // =====================================================
    // DIAGNOSTIC PRINT
    // =====================================================

    private fun printRun(
        scenario: String,
        output:
        CarbleExperimentRunner.RunOutput
    ) {

        val s =
            output.summary

        val a =
            output.adaptation

        println()
        println(
            "===== CARBLE $scenario SMOKE ====="
        )

        println(
            "PDR=${s.packetDeliveryRatio}, " +
                    "meanLatency=${s.meanLatency}, " +
                    "physicalAttempts=${s.physicalAttempts}, " +
                    "retransmissions=${s.retransmissions}"
        )

        println(
            "HIGH=${a.highDecisions}, " +
                    "MEDIUM=${a.mediumDecisions}, " +
                    "LOW=${a.lowDecisions}"
        )

        println(
            "M1=${a.m1Decisions}, " +
                    "M2=${a.m2Decisions}, " +
                    "M3=${a.m3Decisions}"
        )

        println(
            "warnings=${a.downstreamWarnings}, " +
                    "backupPrepared=${a.backupPrepared}, " +
                    "backupActivations=${a.backupActivations}, " +
                    "backupSuccesses=${a.backupSuccesses}, " +
                    "backupFailures=${a.backupFailures}"
        )

        println(
            "carry=${a.carryDecisions}, " +
                    "probe=${a.probeDecisions}, " +
                    "probeSuccess=${a.probeSuccesses}, " +
                    "probeFailure=${a.probeFailures}, " +
                    "fallbackDrops=${a.fallbackDrops}"
        )

        println(
            "M->H=${a.mediumToHighRecoveries}, " +
                    "M->L=${a.mediumToLowEscalations}, " +
                    "L->M=${a.lowToMediumRecoveries}, " +
                    "L->H=${a.lowToHighRecoveries}, " +
                    "duplicateSuppressions=${a.duplicateSuppressions}"
        )
    }
}
