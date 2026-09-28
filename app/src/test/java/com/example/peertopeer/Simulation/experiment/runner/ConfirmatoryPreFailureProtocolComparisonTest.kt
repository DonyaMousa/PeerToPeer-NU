package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.PreFailureProtocolComparisonRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfirmatoryPreFailureProtocolComparisonTest {

    @Test
    fun confirmatory_prefailure_all_conditions_all_protocols_30_paired_seeds() {
        val runner = PreFailureProtocolComparisonRunner()
        val seeds = (2001L..2030L).toList()
        val results = mutableListOf<PreFailureProtocolComparisonRunner.ComparisonResult>()

        PreFailureProtocolComparisonRunner.Condition.entries.forEach { condition ->
            PreFailureProtocolComparisonRunner.Protocol.entries.forEach { protocol ->
                seeds.forEach { seed ->
                    val result = runner.run(condition, protocol, seed)
                    assertTrue(result.generated > 0)
                    assertEquals(result.generated, result.delivered + result.dropped)
                    assertTrue(result.pdr in 0.0..1.0)
                    assertTrue(result.physicalAttempts >= 0L)
                    results += result
                }
            }
        }

        assertEquals(4 * 4 * 30, results.size)

        PreFailureProtocolComparisonRunner.Condition.entries.forEach { condition ->
            PreFailureProtocolComparisonRunner.Protocol.entries.forEach { protocol ->
                val cell = results.filter { it.condition == condition && it.protocol == protocol }
                assertEquals(30, cell.size)
                assertEquals(seeds.toSet(), cell.map { it.seed }.toSet())
            }
        }

        println("================================================================================================================")
        println("FINAL CONFIRMATORY PRE-FAILURE COMPARISON — SEEDS 2001..2030")
        println("condition,protocol,meanPDR,meanMedianLatency,meanAttemptsPerGenerated,meanAttemptsPerDelivered,meanRetransmissions,twoRhHIGH,twoRhLOW,CARBLE_HIGH,M1,M2,M3,CARBLE_LOW")
        PreFailureProtocolComparisonRunner.Condition.entries.forEach { condition ->
            PreFailureProtocolComparisonRunner.Protocol.entries.forEach { protocol ->
                val g = results.filter { it.condition == condition && it.protocol == protocol }
                println(listOf(
                    condition,
                    protocol,
                    g.map { it.pdr }.average(),
                    g.map { it.medianLatency }.average(),
                    g.map { it.attemptsPerGenerated }.average(),
                    g.map { it.attemptsPerDelivered }.average(),
                    g.map { it.retransmissions.toDouble() }.average(),
                    g.sumOf { it.twoRhHighDecisions },
                    g.sumOf { it.twoRhLowDecisions },
                    g.sumOf { it.highDecisions },
                    g.sumOf { it.m1Decisions },
                    g.sumOf { it.m2Decisions },
                    g.sumOf { it.m3Decisions },
                    g.sumOf { it.lowDecisions }
                ).joinToString(","))
            }
        }
        println("================================================================================================================")

        val out = File("build/research/CARBLE-CONFIRMATORY/PREFAILURE")
        if (out.exists()) out.deleteRecursively()
        val csv = runner.exportCsv(results, out)
        assertTrue(csv.exists())
        assertEquals(results.size + 1, csv.readLines().size)
        println("CONFIRMATORY CSV: ${csv.absolutePath}")
    }
}
