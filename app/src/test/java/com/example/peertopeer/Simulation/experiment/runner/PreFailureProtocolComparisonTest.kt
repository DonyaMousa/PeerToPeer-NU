package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.PreFailureProtocolComparisonRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreFailureProtocolComparisonTest {

    @Test
    fun calibration_prefailure_comparison_all_conditions_all_protocols() {

        val runner =
            PreFailureProtocolComparisonRunner()

        /*
         * Calibration only. Confirmatory seeds are deliberately not used here.
         * 4 conditions x 4 protocols x 10 paired calibration seeds = 160 runs.
         */
        val seeds =
            (1001L..1010L).toList()

        val results =
            mutableListOf<
                    PreFailureProtocolComparisonRunner
                        .ComparisonResult
                    >()

        PreFailureProtocolComparisonRunner
            .Condition
            .values()
            .forEach { condition ->

                PreFailureProtocolComparisonRunner
                    .Protocol
                    .values()
                    .forEach { protocol ->

                        seeds.forEach { seed ->
                            val result =
                                runner.run(
                                    condition = condition,
                                    protocol = protocol,
                                    seed = seed
                                )

                            assertTrue(
                                result.generated > 0
                            )

                            assertEquals(
                                result.generated,
                                result.delivered +
                                        result.dropped
                            )

                            results.add(result)
                        }
                    }
            }

        assertEquals(
            4 * 4 * 10,
            results.size
        )

        val carblePfA =
            results.filter {
                it.condition ==
                        PreFailureProtocolComparisonRunner
                            .Condition
                            .PF_A &&
                        it.protocol ==
                        PreFailureProtocolComparisonRunner
                            .Protocol
                            .CARBLE
            }

        val carblePfB1 =
            results.filter {
                it.condition ==
                        PreFailureProtocolComparisonRunner
                            .Condition
                            .PF_B1 &&
                        it.protocol ==
                        PreFailureProtocolComparisonRunner
                            .Protocol
                            .CARBLE
            }

        val carblePfB2 =
            results.filter {
                it.condition ==
                        PreFailureProtocolComparisonRunner
                            .Condition
                            .PF_B2 &&
                        it.protocol ==
                        PreFailureProtocolComparisonRunner
                            .Protocol
                            .CARBLE
            }

        val carblePfC =
            results.filter {
                it.condition ==
                        PreFailureProtocolComparisonRunner
                            .Condition
                            .PF_C &&
                        it.protocol ==
                        PreFailureProtocolComparisonRunner
                            .Protocol
                            .CARBLE
            }

        // Calibrated stage sanity across the final paired set.
        assertTrue(
            "PF-A produced no M1 across final paired seeds.",
            carblePfA.sumOf {
                it.m1Decisions
            } > 0L
        )

        assertTrue(
            "PF-B1 produced no M2 across final paired seeds.",
            carblePfB1.sumOf {
                it.m2Decisions
            } > 0L
        )

        assertTrue(
            "PF-B2 produced no M3 across final paired seeds.",
            carblePfB2.sumOf {
                it.m3Decisions
            } > 0L
        )

        assertTrue(
            "PF-C produced no LOW decisions across final paired seeds.",
            carblePfC.sumOf {
                it.lowDecisions
            } > 0L
        )

        assertTrue(
            "PF-C produced no LOW carry behavior across final paired seeds.",
            carblePfC.sumOf {
                it.carryDecisions
            } > 0L
        )

        assertTrue(
            "PF-C produced no LOW probes across final paired seeds.",
            carblePfC.sumOf {
                it.probeDecisions
            } > 0L
        )

        assertTrue(
            "PF-C produced no bounded fallback drops across final paired seeds.",
            carblePfC.sumOf {
                it.fallbackDrops
            } > 0L
        )

        val twoRhResults =
            results.filter {
                it.protocol ==
                        PreFailureProtocolComparisonRunner
                            .Protocol
                            .TWO_RH
            }

        assertTrue(
            "2RH telemetry recorded no HIGH/LOW decisions.",
            twoRhResults.sumOf {
                it.twoRhHighDecisions +
                        it.twoRhLowDecisions
            } > 0L
        )

        assertEquals(
            "2RH must not expose CARBLE M1 decisions.",
            0L,
            twoRhResults.sumOf {
                it.m1Decisions
            }
        )

        assertEquals(
            "2RH must not expose CARBLE M2 decisions.",
            0L,
            twoRhResults.sumOf {
                it.m2Decisions
            }
        )

        assertEquals(
            "2RH must not expose CARBLE M3 decisions.",
            0L,
            twoRhResults.sumOf {
                it.m3Decisions
            }
        )

        println()
        println(
            "================================================================================================================"
        )
        println(
            "PRE-FAILURE CALIBRATION — 10 PAIRED CALIBRATION SEEDS"
        )
        println(
            "condition,protocol,meanPDR,meanLatency,meanAttempts,meanRetrans,twoRhHIGH,twoRhLOW,totalM1,totalM2,totalM3,totalLOW,totalCarry,totalProbe,totalProbeOK,totalProbeFail,totalFallbackDrops"
        )

        PreFailureProtocolComparisonRunner
            .Condition
            .values()
            .forEach { condition ->

                PreFailureProtocolComparisonRunner
                    .Protocol
                    .values()
                    .forEach { protocol ->

                        val group =
                            results.filter {
                                it.condition ==
                                        condition &&
                                        it.protocol ==
                                        protocol
                            }

                        println(
                            "$condition," +
                                    "$protocol," +
                                    "${group.map { it.pdr }.average()}," +
                                    "${group.map { it.meanLatency }.average()}," +
                                    "${group.map { it.physicalAttempts.toDouble() }.average()}," +
                                    "${group.map { it.retransmissions.toDouble() }.average()}," +
                                    "${group.sumOf { it.twoRhHighDecisions }}," +
                                    "${group.sumOf { it.twoRhLowDecisions }}," +
                                    "${group.sumOf { it.m1Decisions }}," +
                                    "${group.sumOf { it.m2Decisions }}," +
                                    "${group.sumOf { it.m3Decisions }}," +
                                    "${group.sumOf { it.lowDecisions }}," +
                                    "${group.sumOf { it.carryDecisions }}," +
                                    "${group.sumOf { it.probeDecisions }}," +
                                    "${group.sumOf { it.probeSuccesses }}," +
                                    "${group.sumOf { it.probeFailures }}," +
                                    "${group.sumOf { it.fallbackDrops }}"
                        )
                    }
            }

        println(
            "================================================================================================================"
        )

        val outputDirectory =
            File(
                "build/research/CARBLE-PREFAILURE-CALIBRATION"
            )

        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }

        val csv =
            runner.exportCsv(
                results = results,
                outputDirectory = outputDirectory
            )

        assertTrue(
            csv.exists()
        )

        assertEquals(
            results.size + 1,
            csv.readLines().size
        )

        println(
            "CSV: ${csv.absolutePath}"
        )
    }
}
