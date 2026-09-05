package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.PfB2M3CalibrationRunner
import org.junit.Assert.assertTrue
import org.junit.Test

class PfB2M3CalibrationTest {

    @Test
    fun calibrate_instability_needed_for_m3() {

        val runner =
            PfB2M3CalibrationRunner()

        val instabilityCases =
            listOf(
                0,
                1,
                2,
                3
            )

        println()
        println(
            "================================================================================================================"
        )
        println(
            "PF-B2 — M3 INSTABILITY CALIBRATION"
        )
        println(
            "changes,pdr,latency,attempts,retrans,HIGH,MEDIUM,LOW,M1,M2,M3,minQcurrent,m2Band,m3Band,prepared,activated,backupOK,backupFail,dup,carry,probe,drops"
        )

        var totalM3 =
            0L

        instabilityCases.forEach { changes ->

            val result =
                runner.run(
                    seed = 1L,
                    instabilityChanges = changes
                )

            val a =
                result.adaptation

            val q =
                result.regimeEvents
                    .mapNotNull {
                        it.currentHopConfidence
                    }

            val minQ =
                q.minOrNull()

            val m2Band =
                q.count {
                    it >= 0.55 &&
                            it < 0.65
                }

            val m3Band =
                q.count {
                    it >= 0.45 &&
                            it < 0.55
                }

            totalM3 +=
                a.m3Decisions

            println(
                "$changes," +
                        "${result.packetDeliveryRatio}," +
                        "${result.meanLatency}," +
                        "${result.physicalAttempts}," +
                        "${result.retransmissions}," +
                        "${a.highDecisions}," +
                        "${a.mediumDecisions}," +
                        "${a.lowDecisions}," +
                        "${a.m1Decisions}," +
                        "${a.m2Decisions}," +
                        "${a.m3Decisions}," +
                        "$minQ," +
                        "$m2Band," +
                        "$m3Band," +
                        "${a.backupPrepared}," +
                        "${a.backupActivations}," +
                        "${a.backupSuccesses}," +
                        "${a.backupFailures}," +
                        "${a.duplicateSuppressions}," +
                        "${a.carryDecisions}," +
                        "${a.probeDecisions}," +
                        "${a.fallbackDrops}"
            )

            assertTrue(
                result.generatedPackets > 0
            )

            assertTrue(
                result.regimeEvents.isNotEmpty()
            )

            assertTrue(
                a.mediumDecisions ==
                        a.m1Decisions +
                                a.m2Decisions +
                                a.m3Decisions
            )
        }

        println(
            "----------------------------------------------------------------------------------------------------------------"
        )
        println(
            "TOTAL M3 = $totalM3"
        )
        println(
            "================================================================================================================"
        )
    }
}
