package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.export.CarbleCsvExporter
import com.example.peertopeer.simulation.experiment.runner.CarbleExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Carble150RunExperimentTest {

    companion object {

        private const val EXPERIMENT_SET =
            "CARBLE-V1-FINAL-150"

        private const val PROTOCOL_VERSION =
            "CARBLE-v1.0-CANDIDATE"

        private val TOPOLOGY_TIMES =
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
    }

    @Test
    fun generate_carble_150_run_dataset() {

        val outputDirectory =
            File(
                "build/research/CARBLE-V1-FINAL-150"
            )

        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }

        val runner =
            CarbleExperimentRunner(
                hysteresisFraction = 0.05,
                maxFallbackReevaluations = 3,
                fallbackReevaluationDelay = 5L
            )

        val exporter =
            CarbleCsvExporter(
                outputDirectory
            )

        var completedRuns = 0

        runScenario(
            scenarioId = "S01",
            exporter = exporter
        ) { seed ->
            val config = createS01Config(seed)
            config to
                    runner.runSeededRetryScenario(
                        config
                    )
        }
        completedRuns += 30

        runScenario(
            scenarioId = "S02",
            exporter = exporter
        ) { seed ->
            val config = createS02Config(seed)
            config to
                    runner.runSeededTopologyScenario(
                        config
                    )
        }
        completedRuns += 30

        runScenario(
            scenarioId = "S03",
            exporter = exporter
        ) { seed ->
            val config = createS03Config(seed)
            config to
                    runner.runSeededCongestionScenario(
                        config
                    )
        }
        completedRuns += 30

        runScenario(
            scenarioId = "S04",
            exporter = exporter
        ) { seed ->
            val config = createS04Config(seed)
            config to
                    runner
                        .runSeededReliabilityTopologyScenario(
                            config
                        )
        }
        completedRuns += 30

        runScenario(
            scenarioId = "S05",
            exporter = exporter
        ) { seed ->
            val config = createS05Config(seed)
            config to
                    runner.runSeededCombinedScenario(
                        config
                    )
        }
        completedRuns += 30

        assertEquals(
            150,
            completedRuns
        )

        val runsFile =
            File(outputDirectory, "runs.csv")

        val summaryFile =
            File(outputDirectory, "run_summary.csv")

        val adaptationFile =
            File(outputDirectory, "carble_adaptation.csv")

        val packetFile =
            File(outputDirectory, "packet_results.csv")

        val resourceFile =
            File(outputDirectory, "resource_samples.csv")
        val regimeEventsFile =
            File(
                outputDirectory,
                "carble_regime_events.csv"
            )

        assertTrue(runsFile.exists())
        assertTrue(summaryFile.exists())
        assertTrue(adaptationFile.exists())
        assertTrue(packetFile.exists())
        assertTrue(resourceFile.exists())
        assertTrue(
            regimeEventsFile.exists()
        )

        assertTrue(
            regimeEventsFile.readLines().size > 1
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
        assertTrue(
            regimeEventsFile.exists()
        )

        assertTrue(
            regimeEventsFile.readLines().size > 1
        )

        /*
         * Total terminal packets:
         *
         * S01 = 30 * 100 = 3000
         * S02 = 30 *  50 = 1500
         * S03 = 30 * 100 = 3000
         * S04 = 30 * 100 = 3000
         * S05 = 30 * 100 = 3000
         *
         * total 13,500 + header.
         */
        assertEquals(
            13_501,
            packetFile.readLines().size
        )

        /*
         * Five resource rows/run:
         *
         * 150 * 5 = 750 + header.
         */
        assertEquals(
            751,
            resourceFile.readLines().size
        )

        println()
        println("============================================")
        println("CARBLE 150-RUN DATASET COMPLETE")
        println("Output: ${outputDirectory.absolutePath}")
        println("Runs: 150")
        println("S01/S02/S03/S04/S05: 30 each")
        println("============================================")
    }


    // =====================================================
    // SCENARIO LOOP
    // =====================================================

    private fun runScenario(
        scenarioId: String,
        exporter: CarbleCsvExporter,
        execute: (
            seed: Long
        ) -> Pair<
                ExperimentConfig,
                CarbleExperimentRunner.RunOutput
                >
    ) {

        println()
        println(
            "===== CARBLE $scenarioId — 30 SEEDS ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,HIGH,MEDIUM,LOW,M1,M2,M3," +
                    "warnings,backupActivations,carry,probe,fallbackDrops"
        )

        for (seed in 1L..30L) {

            val (
                config,
                output
            ) =
                execute(seed)

            assertRunIntegrity(
                config = config,
                output = output
            )

            exporter.exportRun(
                config = config,
                output = output
            )

            val s = output.summary
            val a = output.adaptation

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}," +
                        "${a.highDecisions}," +
                        "${a.mediumDecisions}," +
                        "${a.lowDecisions}," +
                        "${a.m1Decisions}," +
                        "${a.m2Decisions}," +
                        "${a.m3Decisions}," +
                        "${a.downstreamWarnings}," +
                        "${a.backupActivations}," +
                        "${a.carryDecisions}," +
                        "${a.probeDecisions}," +
                        "${a.fallbackDrops}"
            )
        }
    }


    // =====================================================
    // PER-RUN AUDIT
    // =====================================================

    private fun assertRunIntegrity(
        config: ExperimentConfig,
        output: CarbleExperimentRunner.RunOutput
    ) {

        val s = output.summary
        val a = output.adaptation

        assertEquals(
            config.traffic.packetCount,
            s.generatedPackets
        )

        assertEquals(
            s.generatedPackets,
            s.deliveredPackets +
                    s.droppedPackets
        )

        assertEquals(
            a.mediumDecisions,
            a.m1Decisions +
                    a.m2Decisions +
                    a.m3Decisions
        )

        assertTrue(
            a.backupActivations <=
                    a.backupPrepared
        )

        assertTrue(
            a.backupSuccesses <=
                    a.backupActivations
        )

        assertTrue(
            a.backupFailures <=
                    a.backupActivations
        )

        assertTrue(
            a.probeSuccesses <=
                    a.probeDecisions
        )

        assertTrue(
            a.probeFailures <=
                    a.probeDecisions
        )

        assertEquals(
            s.physicalAttempts,
            output.transmissions.size.toLong()
        )

        assertEquals(
            s.retransmissions,
            output.transmissions
                .count {
                    it.attemptNumber > 1
                }
                .toLong()
        )
    }


    // =====================================================
    // S01 — RELIABILITY
    // =====================================================

    private fun createS01Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(
            experimentSetId = EXPERIMENT_SET,
            runId =
                "CARBLE-S01-R%03d"
                    .format(seed),
            protocol = "CARBLE",
            protocolVersion = PROTOCOL_VERSION,
            runIndex = seed.toInt(),
            seed = seed,

            scenario =
                ScenarioConfig(
                    scenarioId = "S01",
                    scenarioName = "Reliability only",
                    topologyType = "line",
                    nodeCount = 5,
                    queueCapacity = 20,
                    serviceTime = 1L,
                    conditionName = "reliability",
                    notes =
                        "Frozen S01 reliability scenario."
                ),

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
                    modelName = "seeded-bernoulli",
                    successProbability = 0.80
                ),

            gitCommit = "",
            notes =
                "CARBLE-v1 candidate; frozen S01."
        )
    }


    // =====================================================
    // S02 — TOPOLOGY
    // =====================================================

    private fun createS02Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(
            experimentSetId = EXPERIMENT_SET,
            runId =
                "CARBLE-S02-R%03d"
                    .format(seed),
            protocol = "CARBLE",
            protocolVersion = PROTOCOL_VERSION,
            runIndex = seed.toInt(),
            seed = seed,

            scenario =
                ScenarioConfig(
                    scenarioId = "S02",
                    scenarioName = "Topology only",
                    topologyType = "dual-path",
                    nodeCount = 5,
                    queueCapacity = 20,
                    serviceTime = 1L,
                    conditionName = "topology",
                    notes =
                        "Frozen S02 topology scenario.",
                    topologyFailureProbability = 0.30,
                    topologyDecisionTimes = TOPOLOGY_TIMES
                ),

            traffic =
                TrafficConfig(
                    packetCount = 50,
                    packetInterval = 10L,
                    packetTtl = 30,
                    payloadBytes = 32,
                    sourceCount = 1
                ),

            link =
                LinkConfig(
                    maxAttempts = 3,
                    retryDelay = 1L,
                    modelName = "perfect",
                    successProbability = null
                ),

            gitCommit = "",
            notes =
                "CARBLE-v1 candidate; frozen S02."
        )
    }


    // =====================================================
    // S03 — CONGESTION
    // =====================================================

    private fun createS03Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(
            experimentSetId = EXPERIMENT_SET,
            runId =
                "CARBLE-S03-R%03d"
                    .format(seed),
            protocol = "CARBLE",
            protocolVersion = PROTOCOL_VERSION,
            runIndex = seed.toInt(),
            seed = seed,

            scenario =
                ScenarioConfig(
                    scenarioId = "S03",
                    scenarioName = "Congestion only",
                    topologyType = "line",
                    nodeCount = 5,
                    queueCapacity = 5,
                    serviceTime = 3L,
                    conditionName = "congestion",
                    notes =
                        "Frozen S03 congestion scenario."
                ),

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
                    modelName = "perfect",
                    successProbability = null
                ),

            gitCommit = "",
            notes =
                "CARBLE-v1 candidate; frozen S03."
        )
    }


    // =====================================================
    // S04 — RELIABILITY + TOPOLOGY
    // =====================================================

    private fun createS04Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(
            experimentSetId = EXPERIMENT_SET,
            runId =
                "CARBLE-S04-R%03d"
                    .format(seed),
            protocol = "CARBLE",
            protocolVersion = PROTOCOL_VERSION,
            runIndex = seed.toInt(),
            seed = seed,

            scenario =
                ScenarioConfig(
                    scenarioId = "S04",
                    scenarioName = "Reliability + topology",
                    topologyType = "dual-path",
                    nodeCount = 5,
                    queueCapacity = 20,
                    serviceTime = 1L,
                    conditionName = "reliability-topology",
                    notes =
                        "Frozen S04 reliability + topology scenario.",
                    topologyFailureProbability = 0.30,
                    topologyDecisionTimes = TOPOLOGY_TIMES
                ),

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
                    modelName = "seeded-bernoulli",
                    successProbability = 0.80
                ),

            gitCommit = "",
            notes =
                "CARBLE-v1 candidate; frozen S04."
        )
    }


    // =====================================================
    // S05 — COMBINED STRESS
    // =====================================================

    private fun createS05Config(
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(
            experimentSetId = EXPERIMENT_SET,
            runId =
                "CARBLE-S05-R%03d"
                    .format(seed),
            protocol = "CARBLE",
            protocolVersion = PROTOCOL_VERSION,
            runIndex = seed.toInt(),
            seed = seed,

            scenario =
                ScenarioConfig(
                    scenarioId = "S05",
                    scenarioName = "Combined stress",
                    topologyType = "dual-path",
                    nodeCount = 5,
                    queueCapacity = 5,
                    serviceTime = 3L,
                    conditionName = "combined",
                    notes =
                        "Frozen S05 combined-stress scenario.",
                    topologyFailureProbability = 0.30,
                    topologyDecisionTimes = TOPOLOGY_TIMES
                ),

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
                    modelName = "seeded-bernoulli",
                    successProbability = 0.80
                ),

            gitCommit = "",
            notes =
                "CARBLE-v1 candidate; frozen S05."
        )
    }
}
