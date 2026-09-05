package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.FullCarbleTransitionComparisonRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FullCarbleTransitionComparisonTest {

    @Test
    fun full_transition_calibration_all_protocols() {

        val runner =
            FullCarbleTransitionComparisonRunner()

        val seeds =
            (1001L..1010L).toList()

        val results =
            mutableListOf<
                    FullCarbleTransitionComparisonRunner.Result
                    >()

        FullCarbleTransitionComparisonRunner
            .Protocol
            .entries
            .forEach { protocol ->

                seeds.forEach { seed ->

                    val result =
                        runner.run(
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

        assertEquals(
            4 * 10,
            results.size
        )

        val carble =
            results.filter {
                it.protocol ==
                        FullCarbleTransitionComparisonRunner
                            .Protocol
                            .CARBLE
            }

        /*
         * Full-controller validation across paired seeds.
         *
         * We require each intended regime/stage to occur
         * somewhere in the final experiment population.
         *
         * We do not require every individual seed to hit
         * every stage because the link process is stochastic.
         */
        assertTrue(
            "Full transition produced no HIGH decisions.",
            carble.sumOf {
                it.high
            } > 0
        )

        assertTrue(
            "Full transition produced no M1 decisions.",
            carble.sumOf {
                it.m1
            } > 0
        )

        assertTrue(
            "Full transition produced no M2 decisions.",
            carble.sumOf {
                it.m2
            } > 0
        )

        assertTrue(
            "Full transition produced no M3 decisions.",
            carble.sumOf {
                it.m3
            } > 0
        )

        assertTrue(
            "Full transition produced no LOW decisions.",
            carble.sumOf {
                it.low
            } > 0
        )

        assertTrue(
            "Full transition produced no LOW probes.",
            carble.sumOf {
                it.probe
            } > 0
        )

        assertTrue(
            "CARBLE full-transition results did not retain regime events.",
            carble.all {
                it.regimeEvents.isNotEmpty()
            }
        )

        assertTrue(
            "CARBLE full-transition results contain no route-confidence evidence.",
            carble.all {
                it.minRouteConfidence != null
            }
        )

        results.forEach { result ->

            assertEquals(
                "Per-node physical attempts do not reconcile for ${result.runId}.",
                result.physicalAttempts,
                result.relayBurden.sumOf {
                    it.physicalAttempts
                }
            )

            assertEquals(
                "Per-node retransmissions do not reconcile for ${result.runId}.",
                result.retransmissions,
                result.relayBurden.sumOf {
                    it.retransmissions
                }
            )

            assertEquals(
                "Expected exactly five node-burden rows for ${result.runId}.",
                5,
                result.relayBurden.size
            )

            assertEquals(
                "Expected exactly three relay nodes for ${result.runId}.",
                3,
                result.relayBurden.count {
                    it.isRelay
                }
            )
        }

        println()
        println(
            "======================================================================================================================"
        )
        println(
            "FULL DEGRADATION CALIBRATION — B0 vs MM vs 2RH vs CARBLE — 10 PAIRED SEEDS"
        )
        println(
            "protocol,meanPDR,meanLatency,meanAttempts,meanRetrans,twoRhHIGH,twoRhLOW,CARBLE_HIGH,M1,M2,M3,CARBLE_LOW,carry,probe,minQ"
        )

        FullCarbleTransitionComparisonRunner
            .Protocol
            .entries
            .forEach { protocol ->

                val group =
                    results.filter {
                        it.protocol == protocol
                    }

                val minQ =
                    group.mapNotNull {
                        it.minCurrentHopConfidence
                    }.minOrNull()

                println(
                    "$protocol," +
                            "${group.map { it.pdr }.average()}," +
                            "${group.map { it.meanLatency }.average()}," +
                            "${group.map { it.physicalAttempts.toDouble() }.average()}," +
                            "${group.map { it.retransmissions.toDouble() }.average()}," +
                            "${group.sumOf { it.high }}," +
                            "${group.sumOf { it.m1 }}," +
                            "${group.sumOf { it.m2 }}," +
                            "${group.sumOf { it.m3 }}," +
                            "${group.sumOf { it.low }}," +
                            "${group.sumOf { it.carry }}," +
                            "${group.sumOf { it.probe }}," +
                            "$minQ"
                )
            }

        val carbleWithAllStages =
            carble.count {
                it.high > 0 &&
                        it.m1 > 0 &&
                        it.m2 > 0 &&
                        it.m3 > 0 &&
                        it.low > 0
            }

        println()
        println(
            "CARBLE seeds hitting HIGH+M1+M2+M3+LOW in the same run: " +
                    "$carbleWithAllStages / ${carble.size}"
        )

        val strictOrderedTransitions =
            carble.count {
                it.firstM1Time != null &&
                        it.firstM2Time != null &&
                        it.firstM3Time != null &&
                        it.firstLowTime != null &&
                        it.firstM1Time <
                        it.firstM2Time &&
                        it.firstM2Time <
                        it.firstM3Time &&
                        it.firstM3Time <
                        it.firstLowTime
            }

        println(
            "CARBLE seeds with strict first-entry order M1<M2<M3<LOW: " +
                    "$strictOrderedTransitions / ${carble.size}"
        )

        assertTrue(
            "Calibration schedule produced no run with the intended M1<M2<M3<LOW first-entry order.",
            strictOrderedTransitions > 0
        )

        val m1ToLowLeadTimes =
            carble.mapNotNull {
                if (
                    it.firstM1Time != null &&
                    it.firstLowTime != null
                ) {
                    it.firstLowTime -
                            it.firstM1Time
                } else {
                    null
                }
            }

        println(
            "Mean M1→LOW pre-LOW lead time: " +
                    "${m1ToLowLeadTimes.average()}"
        )

        val firstM1 =
            carble.mapNotNull {
                it.firstM1Time
            }

        val firstM2 =
            carble.mapNotNull {
                it.firstM2Time
            }

        val firstM3 =
            carble.mapNotNull {
                it.firstM3Time
            }

        val firstLow =
            carble.mapNotNull {
                it.firstLowTime
            }

        println(
            "Mean first-entry times: " +
                    "M1=${firstM1.takeIf { it.isNotEmpty() }?.average()}, " +
                    "M2=${firstM2.takeIf { it.isNotEmpty() }?.average()}, " +
                    "M3=${firstM3.takeIf { it.isNotEmpty() }?.average()}, " +
                    "LOW=${firstLow.takeIf { it.isNotEmpty() }?.average()}"
        )

        println(
            "======================================================================================================================"
        )

        val outputDirectory =
            File(
                "build/research/CARBLE-FULL-TRANSITION-CALIBRATION"
            )

        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }

        val summaryCsv =
            runner.exportCsv(
                results,
                outputDirectory
            )

        val eventCsv =
            runner.exportCarbleEventCsv(
                results,
                outputDirectory
            )

        val auditCsv =
            runner.exportTransitionAuditCsv(
                results,
                outputDirectory
            )

        val relayBurdenCsv =
            runner.exportRelayBurdenCsv(
                results,
                outputDirectory
            )

        val resourceSummaryCsv =
            runner.exportResourceSummaryCsv(
                results,
                outputDirectory
            )

        assertTrue(summaryCsv.exists())
        assertTrue(eventCsv.exists())
        assertTrue(auditCsv.exists())
        assertTrue(relayBurdenCsv.exists())
        assertTrue(resourceSummaryCsv.exists())

        assertEquals(
            results.size + 1,
            summaryCsv.readLines().size
        )

        val expectedEventRows =
            carble.sumOf {
                it.regimeEvents.size
            }

        assertEquals(
            expectedEventRows + 1,
            eventCsv.readLines().size
        )

        assertEquals(
            carble.size + 1,
            auditCsv.readLines().size
        )

        assertEquals(
            results.size * 5 + 1,
            relayBurdenCsv.readLines().size
        )

        assertEquals(
            results.size + 1,
            resourceSummaryCsv.readLines().size
        )

        println(
            "SUMMARY CSV: ${summaryCsv.absolutePath}"
        )

        println(
            "EVENT CSV: ${eventCsv.absolutePath}"
        )

        println(
            "AUDIT CSV: ${auditCsv.absolutePath}"
        )

        println(
            "RELAY BURDEN CSV: ${relayBurdenCsv.absolutePath}"
        )

        println(
            "RESOURCE SUMMARY CSV: ${resourceSummaryCsv.absolutePath}"
        )
    }
}
