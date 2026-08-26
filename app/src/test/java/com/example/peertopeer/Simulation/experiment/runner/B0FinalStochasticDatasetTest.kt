package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.export.ExperimentCsvExporter
import com.example.peertopeer.simulation.experiment.record.QueueEventType
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class B0FinalStochasticDatasetTest {

    @Test
    fun generate_final_b0_stochastic_dataset() {

        val outputDirectory =
            File(
                "build/research/B0-DAY06-FINAL"
            )

        /*
         * Always start clean.
         *
         * Otherwise rerunning this generator would append
         * duplicate research rows.
         */
        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }

        val runner =
            B0ExperimentRunner()

        val exporter =
            ExperimentCsvExporter(
                outputDirectory
            )


        // =====================================================
        // S01
        // STOCHASTIC RELIABILITY ONLY
        // =====================================================

        println()
        println(
            "===== FINAL S01: SEEDED RELIABILITY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts,retransmissions"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-DAY06-FINAL",

                    runId =
                        "B0-S01-R%03d".format(seed),

                    protocol =
                        "B0",

                    protocolVersion =
                        "B0-FREEZE-CANDIDATE",

                    runIndex =
                        seed.toInt(),

                    seed =
                        seed,

                    traffic =
                        TrafficConfig(
                            packetCount = 100,
                            packetInterval = 10,
                            packetTtl = 30,
                            payloadBytes = 32,
                            sourceCount = 1
                        ),

                    /*
                     * S01 activates only stochastic
                     * physical-attempt reliability.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1,
                            modelName =
                                "seeded-bernoulli-link",
                            successProbability =
                                0.80
                        ),

                    /*
                     * Static topology.
                     *
                     * No topology stochasticity belongs
                     * in S01.
                     */
                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S01-SEEDED-RELIABILITY",

                            scenarioName =
                                "Seeded stochastic link reliability",

                            topologyType =
                                "line",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1,

                            conditionName =
                                "stochastic-reliability",

                            topologyFailureProbability =
                                null,

                            topologyDecisionTimes =
                                emptyList(),

                            notes =
                                "Static five-node line; Bernoulli physical-attempt success probability 0.80."
                        ),

                    notes =
                        "B0 S01 reliability-only stochastic replication."
                )

            val output =
                runner.runSeededRetryScenario(
                    config = config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}"
            )

        }

        // =====================================================
        // S02
        // STOCHASTIC TOPOLOGY ONLY
        // =====================================================

        println()
        println(
            "===== FINAL S02: SEEDED TOPOLOGY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "topologyEvents,routeChanges," +
                    "cacheInvalidations,routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-DAY06-FINAL",

                    runId =
                        "B0-S02-R%03d".format(seed),

                    protocol =
                        "B0",

                    protocolVersion =
                        "B0-FREEZE-CANDIDATE",

                    runIndex =
                        seed.toInt(),

                    seed =
                        seed,

                    traffic =
                        TrafficConfig(
                            packetCount = 50,
                            packetInterval = 10,
                            packetTtl = 30,
                            payloadBytes = 32,
                            sourceCount = 1
                        ),

                    /*
                     * S02 links themselves are perfect.
                     *
                     * Reliability randomness must therefore
                     * remain disabled.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1,
                            modelName =
                                "perfect-link",
                            successProbability =
                                null
                        ),

                    /*
                     * Only topology state changes are
                     * stochastic in S02.
                     */
                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S02-SEEDED-TOPOLOGY",

                            scenarioName =
                                "Seeded stochastic topology variation",

                            topologyType =
                                "dual-path",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1,

                            conditionName =
                                "stochastic-topology",

                            topologyFailureProbability =
                                0.30,

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
                                ),

                            notes =
                                "Primary N2-N4 link state sampled at explicit decision times; perfect physical-attempt reliability."
                        ),

                    notes =
                        "B0 S02 topology-only stochastic replication."
                )

            val output =
                runner.runSeededTopologyScenario(
                    config = config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${output.topologyEvents.size}," +
                        "${s.routeChanges}," +
                        "${s.cacheInvalidations}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // S03
        // STOCHASTIC CONGESTION ONLY
        // =====================================================

        println()
        println(
            "===== FINAL S03: SEEDED CONGESTION ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,queueFullDrops,queueEvents"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-DAY06-FINAL",

                    runId =
                        "B0-S03-R%03d".format(seed),

                    protocol =
                        "B0",

                    protocolVersion =
                        "B0-FREEZE-CANDIDATE",

                    runIndex =
                        seed.toInt(),

                    seed =
                        seed,

                    /*
                     * Seeded burst process creates
                     * stochastic offered-load pressure.
                     */
                    traffic =
                        TrafficConfig(
                            packetCount = 100,
                            packetInterval = 10,
                            packetTtl = 30,
                            payloadBytes = 32,
                            sourceCount = 1,

                            burstProbability =
                                0.30,

                            burstSize =
                                5,

                            burstSpacing =
                                0
                        ),

                    /*
                     * Perfect links isolate congestion.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1,
                            modelName =
                                "perfect-link",
                            successProbability =
                                null
                        ),

                    /*
                     * Static topology + deliberately
                     * bounded queue/service configuration.
                     */
                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S03-SEEDED-CONGESTION",

                            scenarioName =
                                "Seeded stochastic burst congestion",

                            topologyType =
                                "line",

                            nodeCount =
                                5,

                            queueCapacity =
                                5,

                            serviceTime =
                                3,

                            conditionName =
                                "stochastic-congestion",

                            topologyFailureProbability =
                                null,

                            topologyDecisionTimes =
                                emptyList(),

                            notes =
                                "Static topology and perfect links; congestion generated only by seeded bursty offered load."
                        ),

                    notes =
                        "B0 S03 congestion-only stochastic replication."
                )

            val output =
                runner.runSeededCongestionScenario(
                    config = config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            val s =
                output.summary

            val queueFullDrops =
                output.queueEvents.count {
                    it.eventType ==
                            QueueEventType.DROPPED_FULL
                }

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}," +
                        "$queueFullDrops," +
                        "${output.queueEvents.size}"
            )
        }

        // =====================================================
        // S04
        // STOCHASTIC RELIABILITY + TOPOLOGY
        // =====================================================

        println()
        println(
            "===== FINAL S04: RELIABILITY + TOPOLOGY ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,topologyEvents,routeChanges," +
                    "cacheInvalidations,routeCalculations"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-DAY06-FINAL",

                    runId =
                        "B0-S04-R%03d".format(seed),

                    protocol =
                        "B0",

                    protocolVersion =
                        "B0-FREEZE-CANDIDATE",

                    runIndex =
                        seed.toInt(),

                    seed =
                        seed,

                    /*
                     * Normal/non-bursty traffic.
                     *
                     * Congestion is deliberately excluded
                     * from S04.
                     */
                    traffic =
                        TrafficConfig(
                            packetCount = 100,
                            packetInterval = 10,
                            packetTtl = 30,
                            payloadBytes = 32,
                            sourceCount = 1
                        ),

                    /*
                     * Reliability stress.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1,
                            modelName =
                                "seeded-bernoulli-link",
                            successProbability =
                                0.80
                        ),

                    /*
                     * Topology stress.
                     */
                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S04-RELIABILITY-TOPOLOGY",

                            scenarioName =
                                "Seeded reliability and topology interaction",

                            topologyType =
                                "dual-path",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1,

                            conditionName =
                                "reliability-topology",

                            topologyFailureProbability =
                                0.30,

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
                                ),

                            notes =
                                "Stochastic physical-attempt reliability combined with stochastic primary-link topology variation."
                        ),

                    notes =
                        "B0 S04 reliability-plus-topology stochastic replication."
                )

            val output =
                runner.runSeededReliabilityTopologyScenario(
                    config = config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            val s =
                output.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}," +
                        "${output.topologyEvents.size}," +
                        "${s.routeChanges}," +
                        "${s.cacheInvalidations}," +
                        "${s.routeCalculations}"
            )
        }

        // =====================================================
        // S05
        // RELIABILITY + TOPOLOGY + CONGESTION
        // =====================================================

        println()
        println(
            "===== FINAL S05: COMBINED STRESS ====="
        )

        println(
            "seed,pdr,meanLatency,physicalAttempts," +
                    "retransmissions,topologyEvents,routeChanges," +
                    "queueFullDrops,queueEvents"
        )

        for (seed in 1L..30L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-DAY06-FINAL",

                    runId =
                        "B0-S05-R%03d".format(seed),

                    protocol =
                        "B0",

                    protocolVersion =
                        "B0-FREEZE-CANDIDATE",

                    runIndex =
                        seed.toInt(),

                    seed =
                        seed,

                    /*
                     * Congestion stress.
                     */
                    traffic =
                        TrafficConfig(
                            packetCount = 100,
                            packetInterval = 10,
                            packetTtl = 30,
                            payloadBytes = 32,
                            sourceCount = 1,

                            burstProbability =
                                0.30,

                            burstSize =
                                5,

                            burstSpacing =
                                0
                        ),

                    /*
                     * Reliability stress.
                     */
                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1,
                            modelName =
                                "seeded-bernoulli-link",
                            successProbability =
                                0.80
                        ),

                    /*
                     * Topology + queue-pressure
                     * configuration.
                     */
                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S05-COMBINED-STRESS",

                            scenarioName =
                                "Seeded combined reliability topology and congestion",

                            topologyType =
                                "dual-path",

                            nodeCount =
                                5,

                            queueCapacity =
                                5,

                            serviceTime =
                                3,

                            conditionName =
                                "combined-stress",

                            topologyFailureProbability =
                                0.30,

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
                                ),

                            notes =
                                "Stochastic reliability, stochastic topology variation and seeded burst congestion combined."
                        ),

                    notes =
                        "B0 S05 full combined-stress stochastic replication."
                )

            val output =
                runner.runSeededCombinedScenario(
                    config = config
                )

            exporter.exportRun(
                config = config,
                output = output
            )

            val s =
                output.summary

            val queueFullDrops =
                output.queueEvents.count {
                    it.eventType ==
                            QueueEventType.DROPPED_FULL
                }

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}," +
                        "${output.topologyEvents.size}," +
                        "${s.routeChanges}," +
                        "$queueFullDrops," +
                        "${output.queueEvents.size}"
            )
        }

        // =====================================================
        // FINAL DATASET SANITY CHECK
        // =====================================================

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
         * 5 scenarios × 30 independent seeds
         * = 150 independent runs.
         *
         * CSV line count therefore equals:
         *
         * 1 header + 150 rows = 151.
         */
        assertEquals(
            151,
            runsFile.readLines().size
        )

        assertEquals(
            151,
            summaryFile.readLines().size
        )

        // =====================================================
        // FINAL REPORT
        // =====================================================

        println()
        println(
            "===== B0 DATASET COMPLETE ====="
        )

        println(
            "Output directory: ${outputDirectory.absolutePath}"
        )

        println(
            "Independent runs: 150"
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
}