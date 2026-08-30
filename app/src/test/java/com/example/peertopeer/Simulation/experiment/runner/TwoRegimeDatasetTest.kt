package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.export.ExperimentCsvExporter
import com.example.peertopeer.simulation.experiment.export.TwoRegimeAdaptationCsvExporter
import com.example.peertopeer.simulation.experiment.runner.TwoRegimeExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwoRegimeDatasetTest {

    companion object {

        private const val EXPERIMENT_SET_ID =
            "2RH-FINAL"

        private const val PROTOCOL_VERSION =
            "2RH-v1.0-CANDIDATE"
    }

    private val topologyDecisionTimes =
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


    // =====================================================
    // FULL DATASET
    // =====================================================

    @Test
    fun generate_final_two_regime_dataset() {

        val outputDirectory =
            File(
                "build/research/2RH-FINAL"
            )

        /*
         * Never append a second experiment onto an old
         * dataset.
         */
        if (
            outputDirectory.exists()
        ) {

            outputDirectory
                .deleteRecursively()
        }

        outputDirectory.mkdirs()

        val runner =
            TwoRegimeExperimentRunner(

                hysteresisFraction =
                    0.05,

                maxFallbackReevaluations =
                    3,

                fallbackReevaluationDelay =
                    5L
            )

        /*
         * Common B0/MM/2RH evidence.
         */
        val commonExporter =
            ExperimentCsvExporter(
                outputDirectory
            )

        /*
         * 2RH-only adaptation evidence.
         *
         * Collected in memory then written as one dedicated
         * CSV at the end.
         */
        val adaptationRows =
            mutableListOf<
                    TwoRegimeAdaptationCsvExporter.Row
                    >()


        // =================================================
        // S01
        // RELIABILITY ONLY
        // =================================================

        println()
        println(
            "===== 2RH FINAL S01: RELIABILITY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,high,low,carries,probes," +
                    "probeSuccesses,probeFailures,recoveries," +
                    "fallbackDrops"
        )

        for (
        seed in 1L..30L
        ) {

            val config =
                createS01Config(
                    seed
                )

            val output =
                runner.runSeededRetryScenario(
                    config
                )

            validateRun(
                config =
                    config,

                output =
                    output
            )

            commonExporter.exportRun(
                config =
                    config,

                output =
                    output
            )

            adaptationRows.add(
                adaptationRow(
                    config =
                        config,

                    output =
                        output
                )
            )

            printRun(
                seed =
                    seed,

                output =
                    output
            )
        }


        // =================================================
        // S02
        // TOPOLOGY ONLY
        // =================================================

        println()
        println(
            "===== 2RH FINAL S02: TOPOLOGY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "topologyEvents,routeChanges,high,low," +
                    "carries,probes,recoveries,fallbackDrops"
        )

        for (
        seed in 1L..30L
        ) {

            val config =
                createS02Config(
                    seed
                )

            val output =
                runner.runSeededTopologyScenario(
                    config
                )

            validateRun(
                config =
                    config,

                output =
                    output
            )

            assertEquals(
                output.topologyEvents
                    .size
                    .toLong(),

                output.summary
                    .topologyEvents
            )

            commonExporter.exportRun(
                config =
                    config,

                output =
                    output
            )

            adaptationRows.add(
                adaptationRow(
                    config =
                        config,

                    output =
                        output
                )
            )

            printRun(
                seed =
                    seed,

                output =
                    output
            )
        }


        // =================================================
        // S03
        // CONGESTION ONLY
        // =================================================

        println()
        println(
            "===== 2RH FINAL S03: CONGESTION ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "queueFullDrops,high,low,carries,probes," +
                    "recoveries,fallbackDrops"
        )

        for (
        seed in 1L..30L
        ) {

            val config =
                createS03Config(
                    seed
                )

            val output =
                runner.runSeededCongestionScenario(
                    config
                )

            validateRun(
                config =
                    config,

                output =
                    output
            )

            assertTrue(
                output.queueEvents
                    .isNotEmpty()
            )

            assertTrue(
                output.topologyEvents
                    .isEmpty()
            )

            commonExporter.exportRun(
                config =
                    config,

                output =
                    output
            )

            adaptationRows.add(
                adaptationRow(
                    config =
                        config,

                    output =
                        output
                )
            )

            printRun(
                seed =
                    seed,

                output =
                    output
            )
        }


        // =================================================
        // S04
        // RELIABILITY + TOPOLOGY
        // =================================================

        println()
        println(
            "===== 2RH FINAL S04: RELIABILITY + TOPOLOGY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,topologyEvents,routeChanges," +
                    "high,low,carries,probes,recoveries," +
                    "fallbackDrops"
        )

        for (
        seed in 1L..30L
        ) {

            val config =
                createS04Config(
                    seed
                )

            val output =
                runner
                    .runSeededReliabilityTopologyScenario(
                        config
                    )

            validateRun(
                config =
                    config,

                output =
                    output
            )

            assertEquals(
                output.topologyEvents
                    .size
                    .toLong(),

                output.summary
                    .topologyEvents
            )

            commonExporter.exportRun(
                config =
                    config,

                output =
                    output
            )

            adaptationRows.add(
                adaptationRow(
                    config =
                        config,

                    output =
                        output
                )
            )

            printRun(
                seed =
                    seed,

                output =
                    output
            )
        }


        // =================================================
        // S05
        // COMBINED STRESS
        // =================================================

        println()
        println(
            "===== 2RH FINAL S05: COMBINED ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,queueFullDrops," +
                    "topologyEvents,routeChanges,high,low," +
                    "carries,probes,probeSuccesses," +
                    "probeFailures,recoveries,fallbackDrops"
        )

        for (
        seed in 1L..30L
        ) {

            val config =
                createS05Config(
                    seed
                )

            val output =
                runner.runSeededCombinedScenario(
                    config
                )

            validateRun(
                config =
                    config,

                output =
                    output
            )

            assertEquals(
                output.topologyEvents
                    .size
                    .toLong(),

                output.summary
                    .topologyEvents
            )

            commonExporter.exportRun(
                config =
                    config,

                output =
                    output
            )

            adaptationRows.add(
                adaptationRow(
                    config =
                        config,

                    output =
                        output
                )
            )

            printRun(
                seed =
                    seed,

                output =
                    output
            )
        }


        // =================================================
        // ADAPTATION CSV
        // =================================================

        val adaptationCsv =
            TwoRegimeAdaptationCsvExporter
                .export(
                    adaptationRows
                )

        val adaptationFile =
            File(
                outputDirectory,
                "two_regime_adaptation.csv"
            )

        adaptationFile.writeText(
            adaptationCsv
        )


        // =================================================
        // FINAL DATASET AUDIT
        // =================================================

        val runsFile =
            File(
                outputDirectory,
                "runs.csv"
            )

        val summaryFile =
            File(
                outputDirectory,
                "run_summary.csv"
            )

        assertTrue(
            runsFile.exists()
        )

        assertTrue(
            summaryFile.exists()
        )

        assertTrue(
            adaptationFile.exists()
        )

        /*
         * Header + 150 runs.
         */
        assertEquals(
            151,
            runsFile.readLines().size
        )

        assertEquals(
            151,
            summaryFile.readLines().size
        )

        assertEquals(
            151,
            adaptationFile.readLines().size
        )

        /*
         * Exactly 150 adaptation rows were created.
         */
        assertEquals(
            150,
            adaptationRows.size
        )

        /*
         * Globally unique run IDs.
         */
        assertEquals(
            150,
            adaptationRows
                .map {
                    it.runId
                }
                .distinct()
                .size
        )

        /*
         * Exactly 30 runs per scenario.
         */
        assertScenarioCount(
            adaptationRows,
            "S01",
            30
        )

        assertScenarioCount(
            adaptationRows,
            "S02",
            30
        )

        assertScenarioCount(
            adaptationRows,
            "S03",
            30
        )

        assertScenarioCount(
            adaptationRows,
            "S04",
            30
        )

        assertScenarioCount(
            adaptationRows,
            "S05",
            30
        )


        println()
        println(
            "===== 2RH DATASET COMPLETE ====="
        )

        println(
            "Directory: " +
                    outputDirectory.absolutePath
        )

        println(
            "Total independent runs: 150"
        )

        println(
            "S01: 30"
        )

        println(
            "S02: 30"
        )

        println(
            "S03: 30"
        )

        println(
            "S04: 30"
        )

        println(
            "S05: 30"
        )

        println(
            "Common CSV rows: 150"
        )

        println(
            "2RH adaptation rows: 150"
        )

        println(
            "================================"
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
                EXPERIMENT_SET_ID,

            runId =
                "2RH-S01-R%03d"
                    .format(
                        seed
                    ),

            protocol =
                "2RH",

            protocolVersion =
                PROTOCOL_VERSION,

            runIndex =
                seed.toInt(),

            seed =
                seed,

            scenario =
                ScenarioConfig(

                    scenarioId =
                        "S01",

                    scenarioName =
                        "Reliability only",

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
                        ""
                ),

            traffic =
                TrafficConfig(

                    packetCount =
                        100,

                    packetInterval =
                        10L,

                    packetTtl =
                        30,

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
                        1L,

                    modelName =
                        "seeded-bernoulli",

                    successProbability =
                        0.80
                ),

            gitCommit =
                "",

            notes =
                "2RH final S01 replication."
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
                EXPERIMENT_SET_ID,

            runId =
                "2RH-S02-R%03d"
                    .format(
                        seed
                    ),

            protocol =
                "2RH",

            protocolVersion =
                PROTOCOL_VERSION,

            runIndex =
                seed.toInt(),

            seed =
                seed,

            scenario =
                ScenarioConfig(

                    scenarioId =
                        "S02",

                    scenarioName =
                        "Topology only",

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

                    notes =
                        "",

                    topologyFailureProbability =
                        0.30,

                    topologyDecisionTimes =
                        topologyDecisionTimes
                ),

            traffic =
                TrafficConfig(

                    packetCount =
                        50,

                    packetInterval =
                        10L,

                    packetTtl =
                        30,

                    payloadBytes =
                        32,

                    sourceCount =
                        1
                ),

            /*
             * S02 isolates topology.
             *
             * Physical links are otherwise perfect.
             */
            link =
                LinkConfig(

                    maxAttempts =
                        3,

                    retryDelay =
                        1L,

                    modelName =
                        "perfect",

                    successProbability =
                        null
                ),

            gitCommit =
                "",

            notes =
                "2RH final S02 replication."
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
                EXPERIMENT_SET_ID,

            runId =
                "2RH-S03-R%03d"
                    .format(
                        seed
                    ),

            protocol =
                "2RH",

            protocolVersion =
                PROTOCOL_VERSION,

            runIndex =
                seed.toInt(),

            seed =
                seed,

            scenario =
                ScenarioConfig(

                    scenarioId =
                        "S03",

                    scenarioName =
                        "Congestion only",

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
                        ""
                ),

            traffic =
                TrafficConfig(

                    packetCount =
                        100,

                    packetInterval =
                        10L,

                    packetTtl =
                        30,

                    payloadBytes =
                        32,

                    sourceCount =
                        1,

                    burstProbability =
                        0.30,

                    burstSize =
                        5,

                    burstSpacing =
                        0L
                ),

            /*
             * S03 isolates congestion.
             */
            link =
                LinkConfig(

                    maxAttempts =
                        3,

                    retryDelay =
                        1L,

                    modelName =
                        "perfect",

                    successProbability =
                        null
                ),

            gitCommit =
                "",

            notes =
                "2RH final S03 replication."
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
                EXPERIMENT_SET_ID,

            runId =
                "2RH-S04-R%03d"
                    .format(
                        seed
                    ),

            protocol =
                "2RH",

            protocolVersion =
                PROTOCOL_VERSION,

            runIndex =
                seed.toInt(),

            seed =
                seed,

            scenario =
                ScenarioConfig(

                    scenarioId =
                        "S04",

                    scenarioName =
                        "Reliability + topology",

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

                    notes =
                        "",

                    topologyFailureProbability =
                        0.30,

                    topologyDecisionTimes =
                        topologyDecisionTimes
                ),

            traffic =
                TrafficConfig(

                    packetCount =
                        100,

                    packetInterval =
                        10L,

                    packetTtl =
                        30,

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
                        1L,

                    modelName =
                        "seeded-bernoulli",

                    successProbability =
                        0.80
                ),

            gitCommit =
                "",

            notes =
                "2RH final S04 replication."
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
                EXPERIMENT_SET_ID,

            runId =
                "2RH-S05-R%03d"
                    .format(
                        seed
                    ),

            protocol =
                "2RH",

            protocolVersion =
                PROTOCOL_VERSION,

            runIndex =
                seed.toInt(),

            seed =
                seed,

            scenario =
                ScenarioConfig(

                    scenarioId =
                        "S05",

                    scenarioName =
                        "Combined stress",

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

                    notes =
                        "",

                    topologyFailureProbability =
                        0.30,

                    topologyDecisionTimes =
                        topologyDecisionTimes
                ),

            traffic =
                TrafficConfig(

                    packetCount =
                        100,

                    packetInterval =
                        10L,

                    packetTtl =
                        30,

                    payloadBytes =
                        32,

                    sourceCount =
                        1,

                    burstProbability =
                        0.30,

                    burstSize =
                        5,

                    burstSpacing =
                        0L
                ),

            link =
                LinkConfig(

                    maxAttempts =
                        3,

                    retryDelay =
                        1L,

                    modelName =
                        "seeded-bernoulli",

                    successProbability =
                        0.80
                ),

            gitCommit =
                "",

            notes =
                "2RH final S05 replication."
        )
    }


    // =====================================================
    // COMMON RUN VALIDATION
    // =====================================================

    private fun validateRun(
        config: ExperimentConfig,
        output: TwoRegimeExperimentRunner.RunOutput
    ) {

        val summary =
            output.summary

        /*
         * Packet accounting.
         */
        assertEquals(
            config.traffic.packetCount,
            summary.generatedPackets
        )

        assertEquals(
            summary.generatedPackets,
            summary.deliveredPackets +
                    summary.droppedPackets
        )

        assertEquals(
            config.traffic.packetCount,
            output.packets.size
        )

        assertEquals(
            output.packets.size,
            output.packets
                .map {
                    it.messageId
                }
                .distinct()
                .size
        )

        assertTrue(
            output.packets.all {
                it.delivered.xor(
                    it.dropped
                )
            }
        )

        /*
         * MM-style fresh route calculation is still used
         * inside 2RH HIGH/LOW decisions.
         */
        assertTrue(
            summary.routeCalculations > 0L
        )

        assertEquals(
            0L,
            summary.cacheHits
        )

        /*
         * One final resource sample per node.
         */
        assertEquals(
            config.scenario.nodeCount,
            output.resourceSamples.size
        )

        /*
         * Adaptation telemetry invariants.
         */
        val adaptation =
            output.adaptation

        assertTrue(
            adaptation.probeSuccesses +
                    adaptation.probeFailures <=
                    adaptation.probeDecisions
        )

        assertTrue(
            adaptation.lowToHighRecoveries <=
                    adaptation.highDecisions
        )

        assertTrue(
            adaptation.fallbackDrops <=
                    summary.generatedPackets.toLong()
        )

        assertTrue(
            adaptation.highDecisions >= 0L
        )

        assertTrue(
            adaptation.lowDecisions >= 0L
        )

        /*
         * Every regime evaluation is ultimately based on a
         * route request.
         *
         * For the current provider, HIGH + LOW decision
         * counts should therefore equal the common MM route
         * request count.
         */
        assertEquals(
            summary.routeRequests,
            adaptation.highDecisions +
                    adaptation.lowDecisions
        )
    }


    // =====================================================
    // ADAPTATION ROW
    // =====================================================

    private fun adaptationRow(
        config: ExperimentConfig,
        output: TwoRegimeExperimentRunner.RunOutput
    ):
            TwoRegimeAdaptationCsvExporter.Row {

        return TwoRegimeAdaptationCsvExporter.Row(

            runId =
                config.runId,

            scenarioId =
                config.scenario
                    .scenarioId,

            seed =
                config.seed,

            output =
                output
        )
    }


    // =====================================================
    // SCENARIO COUNT
    // =====================================================

    private fun assertScenarioCount(
        rows:
        List<
                TwoRegimeAdaptationCsvExporter.Row
                >,
        scenarioId: String,
        expected: Int
    ) {

        assertEquals(
            expected,
            rows.count {
                it.scenarioId ==
                        scenarioId
            }
        )
    }


    // =====================================================
    // CONSOLE OUTPUT
    // =====================================================

    private fun printRun(
        seed: Long,
        output:
        TwoRegimeExperimentRunner.RunOutput
    ) {

        val s =
            output.summary

        val a =
            output.adaptation

        println(

            "$seed," +

                    "${s.packetDeliveryRatio}," +

                    "${s.meanLatency}," +

                    "${s.physicalAttempts}," +

                    "${s.retransmissions}," +

                    "${s.topologyEvents}," +

                    "${s.queueFullDrops}," +

                    "${s.routeChanges}," +

                    "${a.highDecisions}," +

                    "${a.lowDecisions}," +

                    "${a.carryDecisions}," +

                    "${a.probeDecisions}," +

                    "${a.probeSuccesses}," +

                    "${a.probeFailures}," +

                    "${a.lowToHighRecoveries}," +

                    "${a.fallbackDrops}"
        )
    }
}