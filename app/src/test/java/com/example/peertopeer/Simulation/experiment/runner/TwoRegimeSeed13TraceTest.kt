package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeState
import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.TwoRegimeExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoRegimeSeed13TraceTest {

    @Test
    fun trace_pathological_s01_seed_13() {

        val traces =
            mutableListOf<
                    TwoRegimeRouteEvaluator.HopEvaluationTrace
                    >()

        val runner =
            TwoRegimeExperimentRunner(

                hysteresisFraction =
                    0.05,

                maxFallbackReevaluations =
                    3,

                fallbackReevaluationDelay =
                    5L,

                routeTraceObserver = { trace ->

                    traces.add(
                        trace
                    )
                }
            )


        val config =
            createConfig()


        val output =
            runner.runSeededRetryScenario(
                config
            )


        // =================================================
        // BASIC REPRODUCTION
        // =================================================

        assertEquals(
            100,
            output.summary.generatedPackets
        )

        assertTrue(
            traces.isNotEmpty()
        )


        // =================================================
        // LOW TRACE
        // =================================================

        val lowTraces =
            traces.filter {
                it.state ==
                        TwoRegimeState.LOW
            }


        println()
        println(
            "===================================================="
        )

        println(
            "2RH S01 SEED 13 CONFIDENCE TRACE"
        )

        println(
            "===================================================="
        )

        println(
            "PDR=${output.summary.packetDeliveryRatio}"
        )

        println(
            "Delivered=${output.summary.deliveredPackets}"
        )

        println(
            "Dropped=${output.summary.droppedPackets}"
        )

        println(
            "RouteRequests=${output.summary.routeRequests}"
        )

        println(
            "HIGH=${output.adaptation.highDecisions}"
        )

        println(
            "LOW=${output.adaptation.lowDecisions}"
        )

        println(
            "Carries=${output.adaptation.carryDecisions}"
        )

        println(
            "Probes=${output.adaptation.probeDecisions}"
        )

        println(
            "ProbeSuccesses=${output.adaptation.probeSuccesses}"
        )

        println(
            "ProbeFailures=${output.adaptation.probeFailures}"
        )

        println(
            "Recoveries=${output.adaptation.lowToHighRecoveries}"
        )

        println(
            "FallbackDrops=${output.adaptation.fallbackDrops}"
        )

        println()
        println(
            "Total hop evaluations=${traces.size}"
        )

        println(
            "LOW hop evaluations=${lowTraces.size}"
        )

        println()


        // =================================================
        // PRINT FIRST LOW OBSERVATIONS
        // =================================================

        println(
            "----- FIRST 25 LOW HOP EVALUATIONS -----"
        )

        lowTraces
            .take(
                25
            )
            .forEachIndexed {
                    index,
                    trace ->

                printTrace(
                    index =
                        index + 1,

                    trace =
                        trace
                )
            }


        // =================================================
        // PRINT FINAL LOW OBSERVATIONS
        // =================================================

        println()
        println(
            "----- LAST 25 LOW HOP EVALUATIONS -----"
        )

        lowTraces
            .takeLast(
                25
            )
            .forEachIndexed {
                    index,
                    trace ->

                printTrace(
                    index =
                        index + 1,

                    trace =
                        trace
                )
            }


        // =================================================
        // BOTTLENECK FREQUENCY
        // =================================================

        println()
        println(
            "----- LOW HOP FREQUENCY -----"
        )

        lowTraces
            .groupBy {
                "${it.fromNodeId}->${it.toNodeId}"
            }
            .mapValues {
                    (_, records) ->

                records.size
            }
            .toList()
            .sortedByDescending {
                it.second
            }
            .forEach {
                    (hop, count) ->

                println(
                    "$hop LOW evaluations=$count"
                )
            }


        // =================================================
        // MINIMUM CONFIDENCE BY HOP
        // =================================================

        println()
        println(
            "----- MINIMUM Q BY HOP -----"
        )

        traces
            .groupBy {
                "${it.fromNodeId}->${it.toNodeId}"
            }
            .forEach {
                    (hop, records) ->

                val minimum =
                    records.minOf {
                        it.confidence
                    }

                val maximum =
                    records.maxOf {
                        it.confidence
                    }

                val last =
                    records.last()

                println(
                    "$hop " +
                            "minQ=$minimum " +
                            "maxQ=$maximum " +
                            "finalQ=${last.confidence} " +
                            "finalD=${last.deliverySuccess} " +
                            "finalT=${last.timeliness} " +
                            "finalS=${last.signalReliability}"
                )
            }


        println()
        println(
            "===================================================="
        )
    }


    // =====================================================
    // TRACE PRINTING
    // =====================================================

    private fun printTrace(
        index: Int,
        trace:
        TwoRegimeRouteEvaluator.HopEvaluationTrace
    ) {

        println(
            "#$index " +
                    "${trace.fromNodeId}->${trace.toNodeId} " +
                    "Q=${format(trace.confidence)} " +
                    "D=${format(trace.deliverySuccess)} " +
                    "F=${format(trace.freshness)} " +
                    "R=${format(trace.stability)} " +
                    "T=${format(trace.timeliness)} " +
                    "S=${format(trace.signalReliability)} " +
                    "B=${format(trace.resourceSuitability)} " +
                    "successRate=${format(trace.successRate)} " +
                    "delay=${format(trace.observedDelay)} " +
                    "queue=${trace.queueOccupancy}/${trace.queueCapacity} " +
                    "changes=${trace.recentLinkChanges}"
        )
    }


    private fun format(
        value: Double
    ): String {

        return "%.4f".format(
            value
        )
    }


    // =====================================================
    // EXACT S01 SEED-13 CONFIG
    // =====================================================

    private fun createConfig():
            ExperimentConfig {

        return ExperimentConfig(

            experimentSetId =
                "2RH-DIAGNOSTIC",

            runId =
                "2RH-S01-SEED13-TRACE",

            protocol =
                "2RH",

            protocolVersion =
                "2RH-v1.0-CANDIDATE",

            runIndex =
                13,

            seed =
                13L,

            scenario =
                ScenarioConfig(

                    scenarioId =
                        "S01",

                    scenarioName =
                        "Reliability only seed 13 diagnostic",

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
                        "Diagnostic trace for pathological 2RH seed 13."
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
                "Do not use as final dataset. Diagnostic only."
        )
    }
}