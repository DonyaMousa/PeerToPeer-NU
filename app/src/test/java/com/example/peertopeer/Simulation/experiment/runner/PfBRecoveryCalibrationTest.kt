package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.prefailure.PfBRecoveryCalibrationCase
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.runner.PfBRecoveryCalibrationRunner
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PF-B is calibrated BEFORE the final recovery experiment.
 *
 * This test deliberately does not modify CARBLE thresholds
 * or assert that one chosen condition "must" produce M2/M3.
 *
 * Instead it prints a small controlled matrix. We select
 * one condition from that evidence, freeze it, and then run
 * the real multi-seed PF-B comparison.
 */
class PfBRecoveryCalibrationTest {

    @Test
    fun find_controlled_condition_that_exercises_m2_and_m3() {

        val profile =
            PreFailureProfile
                .defaultProfile()


        val runner =
            PfBRecoveryCalibrationRunner()


        val cases =
            listOf(

                /*
                 * Light pressure.
                 */
                PfBRecoveryCalibrationCase(
                    caseId =
                        "C01",
                    backupLinkSuccessProbability =
                        0.90,
                    queueCapacity =
                        20,
                    serviceTime =
                        1L,
                    packetInterval =
                        5L,
                    packetsPerOpportunity =
                        1
                ),


                /*
                 * Backup remains strong, but queue/delay
                 * evidence is moderately stressed.
                 */
                PfBRecoveryCalibrationCase(
                    caseId =
                        "C02",
                    backupLinkSuccessProbability =
                        0.90,
                    queueCapacity =
                        8,
                    serviceTime =
                        2L,
                    packetInterval =
                        4L,
                    packetsPerOpportunity =
                        2
                ),


                /*
                 * Strong backup with heavier local queue
                 * pressure.
                 */
                PfBRecoveryCalibrationCase(
                    caseId =
                        "C03",
                    backupLinkSuccessProbability =
                        0.90,
                    queueCapacity =
                        5,
                    serviceTime =
                        3L,
                    packetInterval =
                        4L,
                    packetsPerOpportunity =
                        3
                ),


                /*
                 * Alternate route is usable but imperfect.
                 */
                PfBRecoveryCalibrationCase(
                    caseId =
                        "C04",
                    backupLinkSuccessProbability =
                        0.80,
                    queueCapacity =
                        8,
                    serviceTime =
                        2L,
                    packetInterval =
                        4L,
                    packetsPerOpportunity =
                        2
                ),


                PfBRecoveryCalibrationCase(
                    caseId =
                        "C05",
                    backupLinkSuccessProbability =
                        0.80,
                    queueCapacity =
                        5,
                    serviceTime =
                        3L,
                    packetInterval =
                        4L,
                    packetsPerOpportunity =
                        3
                ),


                /*
                 * Deeper pressure while still leaving an
                 * alternate branch available.
                 */
                PfBRecoveryCalibrationCase(
                    caseId =
                        "C06",
                    backupLinkSuccessProbability =
                        0.75,
                    queueCapacity =
                        5,
                    serviceTime =
                        3L,
                    packetInterval =
                        3L,
                    packetsPerOpportunity =
                        3
                )
            )


        println()
        println(
            "================================================================================================================"
        )
        println(
            "PF-B RECOVERY-STAGE CALIBRATION"
        )
        println(
            "case,pdr,latency,attempts,retrans,HIGH,MEDIUM,LOW,M1,M2,M3,prepared,activated,backupOK,backupFail,dup,carry,probe,drops"
        )


        var anyM2 =
            false

        var anyM3 =
            false


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


            if (
                a.m2Decisions > 0
            ) {
                anyM2 =
                    true
            }


            if (
                a.m3Decisions > 0
            ) {
                anyM3 =
                    true
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
                        "${a.backupPrepared}," +
                        "${a.backupActivations}," +
                        "${a.backupSuccesses}," +
                        "${a.backupFailures}," +
                        "${a.duplicateSuppressions}," +
                        "${a.carryDecisions}," +
                        "${a.probeDecisions}," +
                        "${a.fallbackDrops}"
            )


            /*
             * Basic accounting only. Calibration is an
             * observation exercise, not a result-tuning
             * assertion.
             */
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
            "Any M2 observed: $anyM2"
        )
        println(
            "Any M3 observed: $anyM3"
        )
        println(
            "================================================================================================================"
        )
    }
}
