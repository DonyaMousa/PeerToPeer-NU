package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.record.RoutingEventType
import com.example.peertopeer.simulation.experiment.runner.MMExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Test

class MMHysteresisSensitivityTest {

    @Test
    fun compare_hysteresis_on_s04_and_s05() {

        val hysteresisValues =
            listOf(
                0.00,
                0.05,
                0.10
            )

        println()
        println(
            "===== MM HYSTERESIS SENSITIVITY ====="
        )

        println(
            "scenario,hysteresis,meanPdr,meanLatency," +
                    "meanRouteChanges,meanPhysicalAttempts," +
                    "meanRetransmissions,meanBurdenImbalance"
        )

        for (
        hysteresis in hysteresisValues
        ) {

            runScenarioBatch(
                scenarioId = "S04",
                hysteresis = hysteresis
            )

            runScenarioBatch(
                scenarioId = "S05",
                hysteresis = hysteresis
            )
        }

        println(
            "====================================="
        )
    }

    // =====================================================
    // BATCH
    // =====================================================

    private fun runScenarioBatch(
        scenarioId: String,
        hysteresis: Double
    ) {

        val runner =
            MMExperimentRunner(
                hysteresisFraction =
                    hysteresis
            )

        val outputs =
            mutableListOf<
                    MMExperimentRunner.RunOutput
                    >()

        for (
        seed in 1L..30L
        ) {

            val config =
                when (
                    scenarioId
                ) {

                    "S04" ->
                        createS04Config(
                            seed = seed,
                            hysteresis =
                                hysteresis
                        )

                    "S05" ->
                        createS05Config(
                            seed = seed,
                            hysteresis =
                                hysteresis
                        )

                    else ->
                        error(
                            "Unsupported scenario: $scenarioId"
                        )
                }

            val output =
                when (
                    scenarioId
                ) {

                    "S04" ->
                        runner
                            .runSeededReliabilityTopologyScenario(
                                config
                            )

                    "S05" ->
                        runner
                            .runSeededCombinedScenario(
                                config
                            )

                    else ->
                        error(
                            "Unsupported scenario: $scenarioId"
                        )
                }

            /*
             * Every run must retain correct packet
             * accounting.
             */
            assertEquals(
                config.traffic.packetCount,
                output.summary.generatedPackets
            )

            assertEquals(
                output.summary.generatedPackets,
                output.summary.deliveredPackets +
                        output.summary.droppedPackets
            )

            outputs.add(
                output
            )
        }

        assertEquals(
            30,
            outputs.size
        )

        val summaries =
            outputs.map {
                it.summary
            }

        val meanPdr =
            summaries
                .map {
                    it.packetDeliveryRatio
                }
                .average()

        val meanLatency =
            summaries
                .mapNotNull {
                    it.meanLatency
                }
                .averageOrNull()

        val meanRouteChanges =
            summaries
                .map {
                    it.routeChanges
                        .toDouble()
                }
                .average()

        val meanPhysicalAttempts =
            summaries
                .map {
                    it.physicalAttempts
                        .toDouble()
                }
                .average()

        val meanRetransmissions =
            summaries
                .map {
                    it.retransmissions
                        .toDouble()
                }
                .average()

        val meanBurdenImbalance =
            summaries
                .mapNotNull {
                    it.forwardingBurdenImbalance
                }
                .averageOrNull()

        /*
         * Extra oscillation indicator:
         *
         * Count A -> B -> A reversals for the same
         * (run, node, destination).
         */
        val totalReversals =
            outputs.sumOf {
                countRouteReversals(
                    it
                )
            }

        val totalRouteChanges =
            summaries.sumOf {
                it.routeChanges
            }

        val reversalShare =
            if (
                totalRouteChanges == 0L
            ) {

                0.0

            } else {

                totalReversals
                    .toDouble() /
                        totalRouteChanges
                            .toDouble()
            }

        println(
            "$scenarioId," +
                    "$hysteresis," +
                    "$meanPdr," +
                    "$meanLatency," +
                    "$meanRouteChanges," +
                    "$meanPhysicalAttempts," +
                    "$meanRetransmissions," +
                    "$meanBurdenImbalance"
        )

        println(
            "  reversals=$totalReversals, " +
                    "routeChanges=$totalRouteChanges, " +
                    "reversalShare=$reversalShare"
        )
    }

    // =====================================================
    // OSCILLATION / REVERSAL COUNT
    // =====================================================

    private fun countRouteReversals(
        output:
        MMExperimentRunner.RunOutput
    ): Long {

        val changedEvents =
            output.routingEvents
                .filter {
                    it.eventType ==
                            RoutingEventType.ROUTE_CHANGED
                }
                .filter {
                    it.path != null
                }

        val grouped =
            changedEvents
                .groupBy {
                    Pair(
                        it.nodeId,
                        it.destinationId
                    )
                }

        var reversals =
            0L

        for (
        (_, events) in grouped
        ) {

            val ordered =
                events.sortedBy {
                    it.eventTime
                }

            /*
             * Detect:
             *
             * A -> B -> A
             *
             * among successive observed selected paths.
             */
            for (
            index in 2 until
                    ordered.size
            ) {

                val pathA =
                    ordered[
                        index - 2
                    ].path

                val pathB =
                    ordered[
                        index - 1
                    ].path

                val pathC =
                    ordered[
                        index
                    ].path

                if (
                    pathA != null &&
                    pathB != null &&
                    pathC != null &&
                    pathA == pathC &&
                    pathA != pathB
                ) {

                    reversals++
                }
            }
        }

        return reversals
    }

    // =====================================================
    // S04 CONFIG
    // =====================================================

    private fun createS04Config(
        seed: Long,
        hysteresis: Double
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "MM-HYSTERESIS-SENSITIVITY",

            runId =
                "MM-H${formatHysteresis(hysteresis)}-" +
                        "S04-R%03d".format(seed),

            protocol =
                "MM",

            protocolVersion =
                "MM-HYSTERESIS-CANDIDATE",

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
                        "MM hysteresis sensitivity test. " +
                                "H=$hysteresis"
                ),

            gitCommit =
                "",

            notes =
                "Sensitivity run only; not final MM dataset."
        )
    }

    // =====================================================
    // S05 CONFIG
    // =====================================================

    private fun createS05Config(
        seed: Long,
        hysteresis: Double
    ): ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "MM-HYSTERESIS-SENSITIVITY",

            runId =
                "MM-H${formatHysteresis(hysteresis)}-" +
                        "S05-R%03d".format(seed),

            protocol =
                "MM",

            protocolVersion =
                "MM-HYSTERESIS-CANDIDATE",

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
                        "MM hysteresis sensitivity test. " +
                                "H=$hysteresis"
                ),

            gitCommit =
                "",

            notes =
                "Sensitivity run only; not final MM dataset."
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

    private fun formatHysteresis(
        value: Double
    ): String {

        return (
                value *
                        100.0
                )
            .toInt()
            .toString()
            .padStart(
                2,
                '0'
            )
    }

    private fun List<Double>
            .averageOrNull():
            Double? {

        return if (
            isEmpty()
        ) {

            null

        } else {

            average()
        }
    }
}
