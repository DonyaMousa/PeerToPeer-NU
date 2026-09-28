package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.PreFailureProtocolComparisonRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreFailureProtocolComparisonTest {

    @Test
    fun calibration_prefailure_comparison_10_paired_seeds() {
        val runner = PreFailureProtocolComparisonRunner()
        val seeds = (1001L..1010L).toList()
        val results = mutableListOf<PreFailureProtocolComparisonRunner.ComparisonResult>()

        PreFailureProtocolComparisonRunner.Condition.entries.forEach { condition ->
            PreFailureProtocolComparisonRunner.Protocol.entries.forEach { protocol ->
                seeds.forEach { seed ->
                    val result = runner.run(condition, protocol, seed)
                    assertTrue(result.generated > 0)
                    assertEquals(result.generated, result.delivered + result.dropped)
                    results += result
                }
            }
        }

        assertEquals(4 * 4 * 10, results.size)

        val pfa = results.filter { it.condition == PreFailureProtocolComparisonRunner.Condition.PF_A && it.protocol == PreFailureProtocolComparisonRunner.Protocol.CARBLE }
        val pfb1 = results.filter { it.condition == PreFailureProtocolComparisonRunner.Condition.PF_B1 && it.protocol == PreFailureProtocolComparisonRunner.Protocol.CARBLE }
        val pfb2 = results.filter { it.condition == PreFailureProtocolComparisonRunner.Condition.PF_B2 && it.protocol == PreFailureProtocolComparisonRunner.Protocol.CARBLE }
        val pfc = results.filter { it.condition == PreFailureProtocolComparisonRunner.Condition.PF_C && it.protocol == PreFailureProtocolComparisonRunner.Protocol.CARBLE }

        assertTrue(pfa.sumOf { it.m1Decisions } > 0L)
        assertTrue(pfb1.sumOf { it.m2Decisions } > 0L)
        assertTrue(pfb2.sumOf { it.m3Decisions } > 0L)
        assertTrue(pfc.sumOf { it.lowDecisions } > 0L)

        val outputDirectory = File("build/research/CARBLE-CALIBRATION/PREFAILURE")
        val csv = runner.exportCsv(results, outputDirectory)
        assertTrue(csv.exists())
    }
}
