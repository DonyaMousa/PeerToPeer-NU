package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.routing.carble.CarbleRegime
import com.example.peertopeer.simulation.experiment.export.PreFailureTimelineCsvExporter
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.runner.PreFailureExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreFailureExperimentTest {

    @Test
    fun carble_gradual_degradation_generates_prefailure_timeline() {

        val profile =
            PreFailureProfile
                .defaultProfile()

        val runner =
            PreFailureExperimentRunner()

        val result =
            runner.run(
                seed = 1L,
                profile = profile
            )

        /*
         * Seven phases × 150 time units with one packet
         * every 5 time units = 210 generated packets.
         */
        assertEquals(
            210,
            result.generatedPackets
        )

        assertEquals(
            result.generatedPackets,
            result.deliveredPackets +
                    result.droppedPackets
        )

        assertTrue(
            result.regimeEvents
                .isNotEmpty()
        )

        assertTrue(
            result.regimeEvents
                .all {
                    it.runId ==
                            result.runId
                }
        )

        val highEvents =
            result.regimeEvents
                .count {
                    it.regime ==
                            CarbleRegime.HIGH
                }

        val mediumEvents =
            result.regimeEvents
                .count {
                    it.regime ==
                            CarbleRegime.MEDIUM
                }

        val lowEvents =
            result.regimeEvents
                .count {
                    it.regime ==
                            CarbleRegime.LOW
                }

        println()
        println(
            "============================================"
        )
        println(
            "CARBLE PRE-FAILURE EXPERIMENT"
        )
        println(
            "PDR=${result.packetDeliveryRatio}"
        )
        println(
            "meanLatency=${result.meanLatency}"
        )
        println(
            "physicalAttempts=${result.physicalAttempts}"
        )
        println(
            "retransmissions=${result.retransmissions}"
        )
        println(
            "HIGH=$highEvents"
        )
        println(
            "MEDIUM=$mediumEvents"
        )
        println(
            "LOW=$lowEvents"
        )
        println(
            "M1=${result.adaptation.m1Decisions}"
        )
        println(
            "M2=${result.adaptation.m2Decisions}"
        )
        println(
            "M3=${result.adaptation.m3Decisions}"
        )
        println(
            "carry=${result.adaptation.carryDecisions}"
        )
        println(
            "probe=${result.adaptation.probeDecisions}"
        )
        println(
            "fallbackDrops=${result.adaptation.fallbackDrops}"
        )
        println(
            "============================================"
        )

        val outputDirectory =
            File(
                "build/research/CARBLE-PREFAILURE"
            )

        if (
            outputDirectory.exists()
        ) {
            outputDirectory
                .deleteRecursively()
        }

        PreFailureTimelineCsvExporter(
            outputDirectory
        ).export(
            result
        )

        val timeline =
            File(
                outputDirectory,
                "prefailure_timeline.csv"
            )

        assertTrue(
            timeline.exists()
        )

        assertTrue(
            timeline.readLines()
                .size >
                    1
        )

        println(
            "Timeline: ${timeline.absolutePath}"
        )
    }
}
