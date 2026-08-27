package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.export.ExperimentCsvExporter
import com.example.peertopeer.simulation.experiment.runner.MMExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MMFinalStochasticDatasetTest {

    @Test
    fun generate_final_mm_stochastic_dataset() {

        val outputDirectory =
            File(
                "build/research/MM-V1-CANDIDATE"
            )

        /*
         * Always start clean.
         *
         * Otherwise rerunning this test would append
         * duplicate rows to the CSV files.
         */
        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }

        val runner =
            MMExperimentRunner()

        val exporter =
            ExperimentCsvExporter(
                outputDirectory
            )

        var independentRuns =
            0

        // =====================================================
        // S01 — RELIABILITY ONLY
        // =====================================================

        println()
        println(
            "===== MM S01: RELIABILITY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(

                    experimentSetId =
                        "MM-FINAL",

                    runId =
                        "MM-S01-R%03d".format(seed),

                    protocol =
                        "MM",

                    protocolVersion =
                        "MM-v1.0-candidate",

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
                                "MM-S01-SEEDED-RETRY",

                            scenarioName =
                                "MM seeded reliability line",

                            topologyType =
                                "line",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1L,

                            conditionName =
                                "stochastic-retry",

                            notes =
                                "Reliability-only condition; physical-attempt success probability = 0.80."
                        ),

                    gitCommit =
                        "",

                    notes =
                        "MM S01 stochastic replication."
                )

            val output =
                runner.runSeededRetryScenario(
                    config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            independentRuns++

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // S02 — TOPOLOGY ONLY
        // =====================================================

        println()
        println(
            "===== MM S02: TOPOLOGY ====="
        )

        println(
            "seed,pdr,meanLatency,topologyEvents," +
                    "routeChanges,routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(

                    experimentSetId =
                        "MM-FINAL",

                    runId =
                        "MM-S02-R%03d".format(seed),

                    protocol =
                        "MM",

                    protocolVersion =
                        "MM-v1.0-candidate",

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
                     *
                     * S02 isolates topology only.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1L,
                            modelName =
                                "perfect",
                            successProbability =
                                null
                        ),

                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "MM-S02-SEEDED-TOPOLOGY",

                            scenarioName =
                                "MM seeded topology variation",

                            topologyType =
                                "dual-path",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1L,

                            conditionName =
                                "stochastic-topology",

                            topologyFailureProbability =
                                0.30,

                            topologyDecisionTimes =
                                topologyTimes(),

                            notes =
                                "Primary N2-N4 state sampled at configured decision epochs; physical links otherwise perfect."
                        ),

                    gitCommit =
                        "",

                    notes =
                        "MM S02 stochastic replication."
                )

            val output =
                runner.runSeededTopologyScenario(
                    config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            independentRuns++

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.topologyEvents}," +
                        "${s.routeChanges}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // S03 — CONGESTION ONLY
        // =====================================================

        println()
        println(
            "===== MM S03: CONGESTION ====="
        )

        println(
            "seed,pdr,meanLatency,queueFullEvents," +
                    "maximumQueueOccupancy,meanQueueWait," +
                    "routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(

                    experimentSetId =
                        "MM-FINAL",

                    runId =
                        "MM-S03-R%03d".format(seed),

                    protocol =
                        "MM",

                    protocolVersion =
                        "MM-v1.0-candidate",

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

                    /*
                     * Perfect links.
                     *
                     * S03 isolates offered-load congestion.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1L,
                            modelName =
                                "perfect",
                            successProbability =
                                null
                        ),

                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "MM-S03-SEEDED-CONGESTION",

                            scenarioName =
                                "MM seeded burst congestion",

                            topologyType =
                                "line",

                            nodeCount =
                                5,

                            queueCapacity =
                                5,

                            serviceTime =
                                3L,

                            conditionName =
                                "stochastic-congestion",

                            notes =
                                "Burst probability = 0.30, burst size = 5, burst spacing = 0."
                        ),

                    gitCommit =
                        "",

                    notes =
                        "MM S03 stochastic replication."
                )

            val output =
                runner.runSeededCongestionScenario(
                    config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            independentRuns++

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.queueFullEvents}," +
                        "${s.maximumQueueOccupancy}," +
                        "${s.meanQueueWait}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // S04 — RELIABILITY + TOPOLOGY
        // =====================================================

        println()
        println(
            "===== MM S04: RELIABILITY + TOPOLOGY ====="
        )

        println(
            "seed,pdr,meanLatency,retransmissions," +
                    "topologyEvents,routeChanges,routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(

                    experimentSetId =
                        "MM-FINAL",

                    runId =
                        "MM-S04-R%03d".format(seed),

                    protocol =
                        "MM",

                    protocolVersion =
                        "MM-v1.0-candidate",

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
                                "MM-S04-RELIABILITY-TOPOLOGY",

                            scenarioName =
                                "MM reliability plus topology",

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
                                "Reliability p=0.80 plus seeded primary-path topology dynamics."
                        ),

                    gitCommit =
                        "",

                    notes =
                        "MM S04 stochastic replication."
                )

            val output =
                runner
                    .runSeededReliabilityTopologyScenario(
                        config
                    )

            exporter.exportRun(
                config = config,
                output = output
            )

            independentRuns++

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.retransmissions}," +
                        "${s.topologyEvents}," +
                        "${s.routeChanges}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // S05 — COMBINED STRESS
        // =====================================================

        println()
        println(
            "===== MM S05: COMBINED ====="
        )

        println(
            "seed,pdr,meanLatency,retransmissions," +
                    "topologyEvents,queueFullEvents," +
                    "maximumQueueOccupancy,routeChanges," +
                    "routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(

                    experimentSetId =
                        "MM-FINAL",

                    runId =
                        "MM-S05-R%03d".format(seed),

                    protocol =
                        "MM",

                    protocolVersion =
                        "MM-v1.0-candidate",

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
                                "MM-S05-COMBINED",

                            scenarioName =
                                "MM combined stochastic stress",

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
                                "Reliability + topology + burst congestion."
                        ),

                    gitCommit =
                        "",

                    notes =
                        "MM S05 stochastic replication."
                )

            val output =
                runner.runSeededCombinedScenario(
                    config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            independentRuns++

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.retransmissions}," +
                        "${s.topologyEvents}," +
                        "${s.queueFullEvents}," +
                        "${s.maximumQueueOccupancy}," +
                        "${s.routeChanges}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // FINAL DATASET VALIDATION
        // =====================================================

        assertEquals(
            150,
            independentRuns
        )

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

        /*
         * 1 header + 150 independent runs.
         */
        assertEquals(
            151,
            runsFile.readLines().size
        )

        assertEquals(
            151,
            summaryFile.readLines().size
        )

        println()
        println(
            "===== MM DATASET COMPLETE ====="
        )

        println(
            "Output directory: ${outputDirectory.absolutePath}"
        )

        println(
            "Independent runs: $independentRuns"
        )

        println(
            "S01 reliability runs: 30"
        )

        println(
            "S02 topology runs: 30"
        )

        println(
            "S03 congestion runs: 30"
        )

        println(
            "S04 reliability + topology runs: 30"
        )

        println(
            "S05 combined-stress runs: 30"
        )

        println(
            "================================"
        )
    }

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
