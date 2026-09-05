package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.CarbleThresholdRobustnessRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final threshold-robustness validation.
 *
 * 3 pre-specified threshold configurations
 * × 30 paired seeds
 * = 90 CARBLE runs.
 *
 * NOMINAL is also checked against the already-frozen full-transition
 * CARBLE dataset to prove that the sensitivity runner reproduces the
 * official CARBLE-v1.0 experiment before perturbing thresholds.
 */
class CarbleThresholdRobustnessTest {

    @Test
    fun threshold_robustness_30_paired_seeds() {

        val runner =
            CarbleThresholdRobustnessRunner()

        val seeds =
            (1L..30L)
                .toList()

        val results =
            mutableListOf<
                    CarbleThresholdRobustnessRunner.Result
                    >()

        CarbleThresholdRobustnessRunner
            .ThresholdConfig
            .entries
            .forEach { config ->

                seeds.forEach { seed ->

                    val result =
                        runner.run(
                            thresholdConfig =
                                config,
                            seed =
                                seed
                        )

                    assertTrue(
                        result.generated >
                                0
                    )

                    assertEquals(
                        result.generated,
                        result.delivered +
                                result.dropped
                    )

                    results.add(
                        result
                    )
                }
            }

        assertEquals(
            3 *
                    30,
            results.size
        )

        CarbleThresholdRobustnessRunner
            .ThresholdConfig
            .entries
            .forEach { config ->

                assertEquals(
                    30,
                    results.count {
                        it.thresholdConfig ==
                                config
                    }
                )
            }

        // =================================================
        // NOMINAL REPRODUCTION AUDIT
        // =================================================

        val frozenFile =
            File(
                "build/research/" +
                        "CARBLE-FULL-TRANSITION-COMPARISON/" +
                        "full_carble_transition_comparison.csv"
            )

        assertTrue(
            "Frozen full-transition dataset is missing.",
            frozenFile.exists()
        )

        val frozenCarble =
            readFrozenCarble(
                frozenFile
            )

        assertEquals(
            30,
            frozenCarble.size
        )

        val nominal =
            results
                .filter {
                    it.thresholdConfig ==
                            CarbleThresholdRobustnessRunner
                                .ThresholdConfig
                                .NOMINAL
                }
                .associateBy {
                    it.seed
                }

        seeds.forEach { seed ->

            val actual =
                requireNotNull(
                    nominal[seed]
                )

            val expected =
                requireNotNull(
                    frozenCarble[seed]
                )

            assertEquals(
                "NOMINAL PDR no longer reproduces frozen CARBLE for seed $seed.",
                expected.pdr,
                actual.pdr,
                1e-12
            )

            assertEquals(
                "NOMINAL latency no longer reproduces frozen CARBLE for seed $seed.",
                expected.meanLatency,
                actual.conditionalMeanLatency,
                1e-12
            )

            assertEquals(
                "NOMINAL physical attempts no longer reproduce frozen CARBLE for seed $seed.",
                expected.physicalAttempts,
                actual.physicalAttempts
            )

            assertEquals(
                "NOMINAL retransmissions no longer reproduce frozen CARBLE for seed $seed.",
                expected.retransmissions,
                actual.retransmissions
            )
        }

        // =================================================
        // PRINT RESEARCH SUMMARY
        // =================================================

        println()
        println(
            "================================================================================================================================================"
        )
        println(
            "CARBLE THRESHOLD ROBUSTNESS — 30 PAIRED SEEDS"
        )
        println(
            "config,thresholds,meanPDR,meanLatency,meanAttempts,meanRetrans,meanAttemptsPerDelivered," +
                    "HIGH,M1,M2,M3,LOW,allStagesSeeds,strictOrderSeeds,meanFirstM1,meanFirstM2,meanFirstM3,meanFirstLOW"
        )

        CarbleThresholdRobustnessRunner
            .ThresholdConfig
            .entries
            .forEach { config ->

                val group =
                    results.filter {
                        it.thresholdConfig ==
                                config
                    }

                println(
                    "${config.name}," +
                            "${config.highThreshold}/" +
                            "${config.m1LowerThreshold}/" +
                            "${config.m2LowerThreshold}/" +
                            "${config.lowThreshold}," +
                            "${group.map { it.pdr }.average()}," +
                            "${group.map { it.conditionalMeanLatency }.average()}," +
                            "${group.map { it.physicalAttempts.toDouble() }.average()}," +
                            "${group.map { it.retransmissions.toDouble() }.average()}," +
                            "${group.map { it.attemptsPerDelivered }.average()}," +
                            "${group.sumOf { it.high }}," +
                            "${group.sumOf { it.m1 }}," +
                            "${group.sumOf { it.m2 }}," +
                            "${group.sumOf { it.m3 }}," +
                            "${group.sumOf { it.low }}," +
                            "${group.count { it.hasAllStages }}," +
                            "${group.count { it.strictFirstEntryOrder }}," +
                            "${meanNullable(group.map { it.firstM1Time })}," +
                            "${meanNullable(group.map { it.firstM2Time })}," +
                            "${meanNullable(group.map { it.firstM3Time })}," +
                            "${meanNullable(group.map { it.firstLowTime })}"
                )
            }

        println(
            "================================================================================================================================================"
        )

        // =================================================
        // EXPORT
        // =================================================

        val outputDirectory =
            File(
                "build/research/" +
                        "CARBLE-THRESHOLD-ROBUSTNESS"
            )

        if (
            outputDirectory.exists()
        ) {
            outputDirectory
                .deleteRecursively()
        }

        val csv =
            runner.exportCsv(
                results =
                    results,
                outputDirectory =
                    outputDirectory
            )

        assertTrue(
            csv.exists()
        )

        assertEquals(
            results.size +
                    1,
            csv.readLines()
                .size
        )

        println(
            "CSV: ${csv.absolutePath}"
        )
    }

    private data class FrozenRow(
        val seed: Long,
        val pdr: Double,
        val meanLatency: Double,
        val physicalAttempts: Long,
        val retransmissions: Long
    )

    private fun readFrozenCarble(
        file: File
    ): Map<Long, FrozenRow> {

        val lines =
            file.readLines()

        require(
            lines.isNotEmpty()
        )

        val header =
            lines.first()
                .split(",")

        fun index(
            name: String
        ): Int {

            val found =
                header.indexOf(
                    name
                )

            require(
                found >= 0
            ) {
                "Frozen CSV missing column: $name"
            }

            return found
        }

        val protocolIndex =
            index("protocol")

        val seedIndex =
            index("seed")

        val pdrIndex =
            index("pdr")

        val latencyIndex =
            index("meanLatency")

        val attemptsIndex =
            index("physicalAttempts")

        val retransIndex =
            index("retransmissions")

        return lines
            .drop(1)
            .map {
                it.split(",")
            }
            .filter {
                it[protocolIndex] ==
                        "CARBLE"
            }
            .associate { row ->

                val seed =
                    row[seedIndex]
                        .toLong()

                seed to
                        FrozenRow(
                            seed =
                                seed,
                            pdr =
                                row[pdrIndex]
                                    .toDouble(),
                            meanLatency =
                                row[latencyIndex]
                                    .toDouble(),
                            physicalAttempts =
                                row[attemptsIndex]
                                    .toLong(),
                            retransmissions =
                                row[retransIndex]
                                    .toLong()
                        )
            }
    }

    private fun meanNullable(
        values:
        List<Long?>
    ): Double? {

        val resolved =
            values
                .filterNotNull()

        return if (
            resolved.isEmpty()
        ) {
            null
        } else {
            resolved.average()
        }
    }
}
