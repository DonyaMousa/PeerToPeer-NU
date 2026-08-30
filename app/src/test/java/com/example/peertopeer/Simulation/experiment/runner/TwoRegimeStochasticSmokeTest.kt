package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.TwoRegimeExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoRegimeStochasticSmokeTest {

    private val runner =
        TwoRegimeExperimentRunner()

    // =====================================================
    // S01 — RELIABILITY ONLY
    // =====================================================

    @Test
    fun s01_reliability_smoke() {

        val config =
            createS01Config(
                seed = 1L
            )

        val output =
            runner.runSeededRetryScenario(
                config
            )

        verifyCommonRun(
            config = config,
            output = output

        )

        /*
         * S01 must contain no topology changes.
         */
        assertTrue(
            output.topologyEvents.isEmpty()
        )

        assertEquals(
            0L,
            output.summary.topologyEvents
        )

        /*
         * Reliability stress should produce actual
         * transmission activity.
         */
        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.summary.physicalAttempts > 0L
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )
    }


    // =====================================================
    // S02 — TOPOLOGY ONLY
    // =====================================================

    @Test
    fun s02_topology_smoke() {

        val config =
            createS02Config(
                seed = 1L
            )

        val output =
            runner.runSeededTopologyScenario(
                config
            )

        verifyCommonRun(
            config = config,
            output = output
        )

        /*
         * Perfect physical links:
         * no retransmission behavior should be required.
         */
        assertEquals(
            0L,
            output.summary.retransmissions
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        /*
         * Raw topology stream must reconcile with summary.
         */
        assertEquals(
            output.topologyEvents.size.toLong(),
            output.summary.topologyEvents
        )
    }


    // =====================================================
    // S03 — CONGESTION ONLY
    // =====================================================

    @Test
    fun s03_congestion_smoke() {

        val config =
            createS03Config(
                seed = 1L
            )

        val output =
            runner.runSeededCongestionScenario(
                config
            )

        verifyCommonRun(
            config = config,
            output = output
        )

        /*
         * S03 has static topology.
         */
        assertTrue(
            output.topologyEvents.isEmpty()
        )

        assertEquals(
            0L,
            output.summary.topologyEvents
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        /*
         * Queue evidence must exist because every relay
         * packet passes through TimedNetworkNode queues.
         */
        assertTrue(
            output.queueEvents.isNotEmpty()
        )
    }


    // =====================================================
    // S04 — RELIABILITY + TOPOLOGY
    // =====================================================

    @Test
    fun s04_reliability_topology_smoke() {

        val config =
            createS04Config(
                seed = 1L
            )

        val output =
            runner
                .runSeededReliabilityTopologyScenario(
                    config
                )

        verifyCommonRun(
            config = config,
            output = output
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            output.topologyEvents.size.toLong(),
            output.summary.topologyEvents
        )

        /*
         * Reliability stress should create actual physical
         * transmission evidence.
         */
        assertTrue(
            output.transmissions.isNotEmpty()
        )
    }


    // =====================================================
    // S05 — COMBINED STRESS
    // =====================================================

    @Test
    fun s05_combined_smoke() {

        val config =
            createS05Config(
                seed = 1L
            )

        val output =
            runner.runSeededCombinedScenario(
                config
            )

        verifyCommonRun(
            config = config,
            output = output
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        assertEquals(
            output.topologyEvents.size.toLong(),
            output.summary.topologyEvents
        )

        assertTrue(
            output.transmissions.isNotEmpty()
        )

        assertTrue(
            output.queueEvents.isNotEmpty()
        )
    }


    // =====================================================
    // COMMON VALIDATION
    // =====================================================

    private fun verifyCommonRun(
        config: ExperimentConfig,
        output: TwoRegimeExperimentRunner.RunOutput
    ) {

        /*
         * Exactly one terminal result per generated packet.
         */
        assertEquals(
            config.traffic.packetCount,
            output.packets.size
        )

        assertEquals(
            config.traffic.packetCount,
            output.summary.generatedPackets
        )

        /*
         * No duplicate terminal packet rows.
         */
        assertEquals(
            output.packets.size,
            output.packets
                .map {
                    it.messageId
                }
                .distinct()
                .size
        )

        /*
         * Hard packet-accounting invariant.
         */
        assertEquals(
            output.summary.generatedPackets,
            output.summary.deliveredPackets +
                    output.summary.droppedPackets
        )

        /*
         * Every terminal packet must be exactly one of:
         *
         * delivered
         * dropped
         */
        assertTrue(
            output.packets.all {
                it.delivered.xor(
                    it.dropped
                )
            }
        )

        /*
         * 2RH still uses MM's fresh deterministic route
         * calculations while in HIGH.
         *
         * No MM routing cache is used.
         */
        assertEquals(
            0L,
            output.summary.cacheHits
        )

        assertTrue(
            output.summary.routeCalculations > 0L
        )

        /*
         * Resource output contract:
         * one final sample per simulated node.
         */
        assertEquals(
            config.scenario.nodeCount,
            output.resourceSamples.size
        )

        assertEquals(
            config.scenario.nodeCount,
            output.resourceSamples
                .map {
                    it.nodeId
                }
                .distinct()
                .size
        )

        /*
         * Transmission/resource reconciliation.
         */
        assertEquals(
            output.transmissions.size.toLong(),
            output.resourceSamples.sumOf {
                it.physicalAttempts
            }
        )

        val rawRetransmissions =
            output.transmissions
                .count {
                    it.attemptNumber > 1
                }
                .toLong()

        assertEquals(
            rawRetransmissions,
            output.resourceSamples.sumOf {
                it.retransmissions
            }
        )

        /*
         * Every record belongs to the same run.
         */
        assertTrue(
            output.packets.all {
                it.runId ==
                        config.runId
            }
        )

        assertTrue(
            output.transmissions.all {
                it.runId ==
                        config.runId
            }
        )

        assertTrue(
            output.routingEvents.all {
                it.runId ==
                        config.runId
            }
        )

        assertTrue(
            output.topologyEvents.all {
                it.runId ==
                        config.runId
            }
        )

        assertTrue(
            output.queueEvents.all {
                it.runId ==
                        config.runId
            }
        )

        assertTrue(
            output.resourceSamples.all {
                it.runId ==
                        config.runId
            }

        )
        assertTrue(
            output.adaptation.probeSuccesses +
                    output.adaptation.probeFailures <=
                    output.adaptation.probeDecisions
        )

        /*
         * A LOW -> HIGH recovery is also a HIGH decision.
         */
        assertTrue(
            output.adaptation.lowToHighRecoveries <=
                    output.adaptation.highDecisions
        )

        /*
         * Fallback drops cannot exceed generated packets.
         */
        assertTrue(
            output.adaptation.fallbackDrops <=
                    output.summary.generatedPackets.toLong()
        )

        /*
         * Print useful behavior so we can inspect whether
         * the current Q=0.75 threshold is too aggressive.
         */
        println()
        println(
            "===== 2RH SMOKE ${config.scenario.scenarioId} ====="
        )

        println(
            "generated=${output.summary.generatedPackets}"
        )

        println(
            "delivered=${output.summary.deliveredPackets}"
        )

        println(
            "dropped=${output.summary.droppedPackets}"
        )

        println(
            "pdr=${output.summary.packetDeliveryRatio}"
        )

        println(
            "meanLatency=${output.summary.meanLatency}"
        )

        println(
            "physicalAttempts=${output.summary.physicalAttempts}"
        )

        println(
            "retransmissions=${output.summary.retransmissions}"
        )

        println(
            "routeRequests=${output.summary.routeRequests}"
        )

        println(
            "routeChanges=${output.summary.routeChanges}"
        )

        println(
            "routeCalculations=${output.summary.routeCalculations}"
        )

        println(
            "noRouteEvents=${output.summary.noRouteEvents}"
        )

        println(
            "topologyEvents=${output.summary.topologyEvents}"
        )

        println(
            "queueFullDrops=${output.summary.queueFullDrops}"
        )

        println(
            "retryExhaustedDrops=${output.summary.retryExhaustedDrops}"
        )

        println(
            "noRouteDrops=${output.summary.noRouteDrops}"
        )
        println(
            "----- 2RH ADAPTATION -----"
        )

        println(
            "highDecisions=${output.adaptation.highDecisions}"
        )

        println(
            "lowDecisions=${output.adaptation.lowDecisions}"
        )

        println(
            "carryDecisions=${output.adaptation.carryDecisions}"
        )

        println(
            "probeDecisions=${output.adaptation.probeDecisions}"
        )

        println(
            "probeSuccesses=${output.adaptation.probeSuccesses}"
        )

        println(
            "probeFailures=${output.adaptation.probeFailures}"
        )

        println(
            "lowToHighRecoveries=${output.adaptation.lowToHighRecoveries}"
        )

        println(
            "fallbackDrops=${output.adaptation.fallbackDrops}"
        )

        println(
            "--------------------------"
        )

        println(
            "===================================="
        )
    }


    // =====================================================
    // S01 CONFIG
    // =====================================================

    private fun createS01Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "2RH-SMOKE",

            runId =
                "2RH-S01-SMOKE-$seed",

            protocol =
                "2RH",

            protocolVersion =
                "2RH-CANDIDATE",

            runIndex =
                seed.toInt(),

            seed =
                seed,

            traffic =
                TrafficConfig(
                    packetCount = 100,
                    packetInterval = 10L,
                    packetTtl = 30,
                    payloadBytes = 32,
                    sourceCount = 1
                ),

            link =
                LinkConfig(
                    maxAttempts = 3,
                    retryDelay = 1L,
                    modelName =
                        "seeded-bernoulli-link-p080",
                    successProbability = 0.80
                ),

            scenario =
                ScenarioConfig(
                    scenarioId =
                        "S01",

                    scenarioName =
                        "2RH seeded reliability",

                    topologyType =
                        "line",

                    nodeCount =
                        5,

                    queueCapacity =
                        20,

                    serviceTime =
                        1L,

                    conditionName =
                        "reliability",

                    notes =
                        "2RH stochastic smoke test."
                ),

            gitCommit =
                "",

            notes =
                "2RH smoke only."
        )
    }


    // =====================================================
    // S02 CONFIG
    // =====================================================

    private fun createS02Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "2RH-SMOKE",

            runId =
                "2RH-S02-SMOKE-$seed",

            protocol =
                "2RH",

            protocolVersion =
                "2RH-CANDIDATE",

            runIndex =
                seed.toInt(),

            seed =
                seed,

            traffic =
                TrafficConfig(
                    packetCount = 50,
                    packetInterval = 10L,
                    packetTtl = 30,
                    payloadBytes = 32,
                    sourceCount = 1
                ),

            /*
             * Perfect links.
             */
            link =
                LinkConfig(
                    maxAttempts = 1,
                    retryDelay = 1L,
                    modelName =
                        "deterministic",
                    successProbability =
                        null
                ),

            scenario =
                ScenarioConfig(
                    scenarioId =
                        "S02",

                    scenarioName =
                        "2RH seeded topology",

                    topologyType =
                        "dual-path",

                    nodeCount =
                        5,

                    queueCapacity =
                        20,

                    serviceTime =
                        1L,

                    conditionName =
                        "topology",

                    topologyFailureProbability =
                        0.30,

                    topologyDecisionTimes =
                        topologyTimes(),

                    notes =
                        "2RH stochastic smoke test."
                ),

            gitCommit =
                "",

            notes =
                "2RH smoke only."
        )
    }


    // =====================================================
    // S03 CONFIG
    // =====================================================

    private fun createS03Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "2RH-SMOKE",

            runId =
                "2RH-S03-SMOKE-$seed",

            protocol =
                "2RH",

            protocolVersion =
                "2RH-CANDIDATE",

            runIndex =
                seed.toInt(),

            seed =
                seed,

            traffic =
                TrafficConfig(
                    packetCount = 100,
                    packetInterval = 10L,
                    packetTtl = 30,
                    payloadBytes = 32,
                    sourceCount = 1,
                    burstProbability = 0.30,
                    burstSize = 5,
                    burstSpacing = 0L
                ),

            link =
                LinkConfig(
                    maxAttempts = 1,
                    retryDelay = 1L,
                    modelName =
                        "deterministic",
                    successProbability =
                        null
                ),

            scenario =
                ScenarioConfig(
                    scenarioId =
                        "S03",

                    scenarioName =
                        "2RH seeded congestion",

                    topologyType =
                        "line",

                    nodeCount =
                        5,

                    queueCapacity =
                        5,

                    serviceTime =
                        3L,

                    conditionName =
                        "congestion",

                    notes =
                        "2RH stochastic smoke test."
                ),

            gitCommit =
                "",

            notes =
                "2RH smoke only."
        )
    }


    // =====================================================
    // S04 CONFIG
    // =====================================================

    private fun createS04Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "2RH-SMOKE",

            runId =
                "2RH-S04-SMOKE-$seed",

            protocol =
                "2RH",

            protocolVersion =
                "2RH-CANDIDATE",

            runIndex =
                seed.toInt(),

            seed =
                seed,

            traffic =
                TrafficConfig(
                    packetCount = 100,
                    packetInterval = 10L,
                    packetTtl = 30,
                    payloadBytes = 32,
                    sourceCount = 1
                ),

            link =
                LinkConfig(
                    maxAttempts = 3,
                    retryDelay = 1L,
                    modelName =
                        "seeded-bernoulli-link-p080",
                    successProbability =
                        0.80
                ),

            scenario =
                ScenarioConfig(
                    scenarioId =
                        "S04",

                    scenarioName =
                        "2RH reliability plus topology",

                    topologyType =
                        "dual-path",

                    nodeCount =
                        5,

                    queueCapacity =
                        20,

                    serviceTime =
                        1L,

                    conditionName =
                        "reliability-topology",

                    topologyFailureProbability =
                        0.30,

                    topologyDecisionTimes =
                        topologyTimes(),

                    notes =
                        "2RH stochastic smoke test."
                ),

            gitCommit =
                "",

            notes =
                "2RH smoke only."
        )
    }


    // =====================================================
    // S05 CONFIG
    // =====================================================

    private fun createS05Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "2RH-SMOKE",

            runId =
                "2RH-S05-SMOKE-$seed",

            protocol =
                "2RH",

            protocolVersion =
                "2RH-CANDIDATE",

            runIndex =
                seed.toInt(),

            seed =
                seed,

            traffic =
                TrafficConfig(
                    packetCount = 100,
                    packetInterval = 10L,
                    packetTtl = 30,
                    payloadBytes = 32,
                    sourceCount = 1,
                    burstProbability = 0.30,
                    burstSize = 5,
                    burstSpacing = 0L
                ),

            link =
                LinkConfig(
                    maxAttempts = 3,
                    retryDelay = 1L,
                    modelName =
                        "seeded-bernoulli-link-p080",
                    successProbability =
                        0.80
                ),

            scenario =
                ScenarioConfig(
                    scenarioId =
                        "S05",

                    scenarioName =
                        "2RH combined stochastic stress",

                    topologyType =
                        "dual-path",

                    nodeCount =
                        5,

                    queueCapacity =
                        5,

                    serviceTime =
                        3L,

                    conditionName =
                        "combined",

                    topologyFailureProbability =
                        0.30,

                    topologyDecisionTimes =
                        topologyTimes(),

                    notes =
                        "2RH stochastic smoke test."
                ),

            gitCommit =
                "",

            notes =
                "2RH smoke only."
        )
    }


    // =====================================================
    // HELPERS
    // =====================================================

    private fun topologyTimes():
            List<Long> {

        return listOf(
            50L,
            100L,
            150L,
            200L,
            250L,
            300L,
            350L,
            400L
        )
    }
}