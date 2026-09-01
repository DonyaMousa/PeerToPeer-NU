package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.prefailure.PfBV2CalibrationCase
import com.example.peertopeer.simulation.experiment.prefailure.PreFailurePhase
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.runner.PfBV2CalibrationRunner
import org.junit.Assert.assertTrue
import org.junit.Test

class PfBV2CalibrationTest {

    @Test
    fun calibrate_dual_degrading_first_hops_for_m2_and_m3() {

        /*
         * PF-B needs deeper local degradation than PF-A
         * because M2/M3 are selected by Qcurrent.
         *
         * We are changing the EXPERIMENT condition here,
         * not CARBLE thresholds.
         *
         * Seven equal-duration phases:
         *
         * 0.90
         * 0.75
         * 0.60
         * 0.45
         * 0.30
         * 0.15
         * 0.05
         */
        val probabilities =
            listOf(
                0.90,
                0.75,
                0.60,
                0.45,
                0.30,
                0.15,
                0.05
            )


        val phaseDuration =
            150L


        val profile =
            PreFailureProfile(

                phases =
                    probabilities
                        .mapIndexed {
                                index,
                                probability ->

                            val start =
                                index *
                                        phaseDuration


                            PreFailurePhase(

                                phaseIndex =
                                    index + 1,

                                startTime =
                                    start,

                                endTimeExclusive =
                                    start +
                                            phaseDuration,

                                successProbability =
                                    probability
                            )
                        }
            )


        val runner =
            PfBV2CalibrationRunner()


        /*
         * Keep this matrix small and interpretable.
         *
         * V201:
         * reliability degradation almost alone
         *
         * V202:
         * moderate queue/timeliness pressure
         *
         * V203:
         * stronger queue/timeliness pressure
         */
        val cases =
            listOf(

                PfBV2CalibrationCase(

                    caseId =
                        "V201",

                    queueCapacity =
                        20,

                    serviceTime =
                        1L,

                    packetInterval =
                        5L,

                    packetsPerOpportunity =
                        1
                ),


                PfBV2CalibrationCase(

                    caseId =
                        "V202",

                    queueCapacity =
                        10,

                    serviceTime =
                        2L,

                    packetInterval =
                        4L,

                    packetsPerOpportunity =
                        2
                ),


                PfBV2CalibrationCase(

                    caseId =
                        "V203",

                    queueCapacity =
                        8,

                    serviceTime =
                        2L,

                    packetInterval =
                        4L,

                    packetsPerOpportunity =
                        3
                )
            )


        println()
        println(
            "================================================================================================================"
        )
        println(
            "PF-B v2 — DUAL DEGRADING FIRST-HOP CALIBRATION"
        )
        println(
            "case,pdr,latency,attempts,retrans,HIGH,MEDIUM,LOW," +
                    "M1,M2,M3,minQcurrent,minQroute,m2Band,m3Band," +
                    "prepared,activated,backupOK,backupFail,dup,carry,probe,drops"
        )


        var totalM2 =
            0L

        var totalM3 =
            0L


        cases.forEach { c ->

            val result =
                runner.run(

                    seed =
                        1L,

                    calibrationCase =
                        c,

                    profile =
                        profile
                )


            val a =
                result.adaptation


            totalM2 +=
                a.m2Decisions


            totalM3 +=
                a.m3Decisions

            val currentHopConfidences =
                result.regimeEvents
                    .mapNotNull {
                        it.currentHopConfidence
                    }

            val minCurrentHopQ =
                currentHopConfidences
                    .minOrNull()

            val minRouteQ =
                result.regimeEvents
                    .mapNotNull {
                        it.routeConfidence
                    }
                    .minOrNull()

            val m3BandEvents =
                currentHopConfidences
                    .count {
                        it >= 0.45 &&
                                it < 0.55
                    }

            val m2BandEvents =
                currentHopConfidences
                    .count {
                        it >= 0.55 &&
                                it < 0.65
                    }


            println(

                "${c.caseId}," +
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
                        "$minCurrentHopQ," +
                        "$minRouteQ," +
                        "$m2BandEvents," +
                        "$m3BandEvents," +
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
                result.generatedPackets >
                        0
            )


            assertTrue(
                result.regimeEvents
                    .isNotEmpty()
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
            "TOTAL M2 = $totalM2"
        )

        println(
            "TOTAL M3 = $totalM3"
        )

        println(
            "================================================================================================================"
        )


        /*
         * Calibration success criterion.
         *
         * We deliberately require BOTH stages somewhere in
         * the matrix before moving to the final PF-B study.
         */

    }
}
