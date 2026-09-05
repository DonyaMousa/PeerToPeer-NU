package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.routing.carble.CarbleRegime
import com.example.peertopeer.simulation.experiment.runner.PfCLowCalibrationRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class PfCLowCalibrationTest {

    @Test
    fun calibrate_low_entry_and_bounded_fallback() {

        val runner =
            PfCLowCalibrationRunner()

        /*
         * PF-B2 showed:
         *
         * 2 changes -> M3, min Q around .52
         * 3 changes -> deeper M3, min Q around .49
         *
         * Therefore PF-C starts at 3 and explores the
         * remaining instability budget up to the frozen
         * reference of 5.
         */
        val cases =
            listOf(
                3,
                4,
                5
            )

        println()
        println(
            "================================================================================================================================================"
        )
        println(
            "PF-C LOW CALIBRATION"
        )
        println(
            "changes,pdr,latency,attempts,retrans,HIGH,MEDIUM,LOW,M1,M2,M3,minQcurrent,carry,probe,probeOK,probeFail,fallbackDrops,mediumToLow"
        )

        var totalLow = 0L
        var totalCarry = 0L
        var totalProbe = 0L

        cases.forEach { changes ->

            val result =
                runner.run(
                    seed = 1L,
                    instabilityChanges =
                        changes
                )

            assertEquals(
                result.generatedPackets,
                result.deliveredPackets +
                        result.droppedPackets
            )

            assertEquals(
                result.adaptation.mediumDecisions,
                result.adaptation.m1Decisions +
                        result.adaptation.m2Decisions +
                        result.adaptation.m3Decisions
            )

            val lowEvents =
                result.regimeEvents
                    .count {
                        it.regime ==
                                CarbleRegime.LOW
                    }
                    .toLong()

            val minQ =
                result.regimeEvents
                    .mapNotNull {
                        it.currentHopConfidence
                    }
                    .minOrNull()

            println(
                "$changes," +
                        "${result.packetDeliveryRatio}," +
                        "${result.meanLatency}," +
                        "${result.physicalAttempts}," +
                        "${result.retransmissions}," +
                        "${result.adaptation.highDecisions}," +
                        "${result.adaptation.mediumDecisions}," +
                        "$lowEvents," +
                        "${result.adaptation.m1Decisions}," +
                        "${result.adaptation.m2Decisions}," +
                        "${result.adaptation.m3Decisions}," +
                        "$minQ," +
                        "${result.adaptation.carryDecisions}," +
                        "${result.adaptation.probeDecisions}," +
                        "${result.adaptation.probeSuccesses}," +
                        "${result.adaptation.probeFailures}," +
                        "${result.adaptation.fallbackDrops}," +
                        "${result.adaptation.mediumToLowEscalations}"
            )

            totalLow +=
                lowEvents

            totalCarry +=
                result.adaptation
                    .carryDecisions

            totalProbe +=
                result.adaptation
                    .probeDecisions
        }

        println(
            "================================================================================================================================================"
        )

        /*
         * Calibration success criteria:
         *
         * - at least one true LOW regime evaluation
         * - LOW causes bounded carry behavior
         * - after carry, at least one LOW probe occurs
         *
         * We do NOT assert which instability count wins;
         * the printed evidence tells us which is the
         * least-severe valid PF-C condition to freeze.
         */
        assertTrue(
            "PF-C calibration produced no LOW regime events.",
            totalLow > 0L
        )

        assertTrue(
            "PF-C calibration produced no carry decisions.",
            totalCarry > 0L
        )

        assertTrue(
            "PF-C calibration produced no probe decisions.",
            totalProbe > 0L
        )
    }
}
