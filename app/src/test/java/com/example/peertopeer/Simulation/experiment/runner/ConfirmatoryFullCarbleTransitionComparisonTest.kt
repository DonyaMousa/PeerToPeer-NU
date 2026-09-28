package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.FullCarbleTransitionComparisonRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfirmatoryFullCarbleTransitionComparisonTest {

    @Test
    fun confirmatory_full_transition_all_protocols_30_paired_seeds() {
        val runner = FullCarbleTransitionComparisonRunner()
        val seeds = (2001L..2030L).toList()
        val results = mutableListOf<FullCarbleTransitionComparisonRunner.Result>()

        FullCarbleTransitionComparisonRunner.Protocol.entries.forEach { protocol ->
            seeds.forEach { seed ->
                val result = runner.run(protocol, seed)
                assertTrue(result.generated > 0)
                assertEquals(result.generated, result.delivered + result.dropped)
                assertTrue(result.pdr in 0.0..1.0)
                assertEquals(result.physicalAttempts, result.relayBurden.sumOf { it.physicalAttempts })
                assertEquals(result.retransmissions, result.relayBurden.sumOf { it.retransmissions })
                results += result
            }
        }

        assertEquals(4 * 30, results.size)
        FullCarbleTransitionComparisonRunner.Protocol.entries.forEach { protocol ->
            val g = results.filter { it.protocol == protocol }
            assertEquals(30, g.size)
            assertEquals(seeds.toSet(), g.map { it.seed }.toSet())
        }

        val carble = results.filter { it.protocol == FullCarbleTransitionComparisonRunner.Protocol.CARBLE }
        val allStages = carble.count { it.high > 0 && it.m1 > 0 && it.m2 > 0 && it.m3 > 0 && it.low > 0 }
        val strictOrder = carble.count {
            it.firstM1Time != null && it.firstM2Time != null && it.firstM3Time != null && it.firstLowTime != null &&
                it.firstM1Time < it.firstM2Time && it.firstM2Time < it.firstM3Time && it.firstM3Time < it.firstLowTime
        }

        println("======================================================================================================================")
        println("FINAL CONFIRMATORY FULL DEGRADATION — B0 vs MM vs 2RH vs CARBLE — SEEDS 2001..2030")
        println("protocol,meanPDR,meanMedianLatency,meanAttemptsPerGenerated,meanAttemptsPerDelivered,meanRetransmissions,twoRhHIGH,twoRhLOW,CARBLE_HIGH,M1,M2,M3,CARBLE_LOW,carry,probe,minCurrentHopQ,minRouteQ")
        FullCarbleTransitionComparisonRunner.Protocol.entries.forEach { protocol ->
            val g = results.filter { it.protocol == protocol }
            println(listOf(
                protocol,
                g.map { it.pdr }.average(),
                g.map { it.medianLatency }.average(),
                g.map { it.attemptsPerGenerated }.average(),
                g.map { it.attemptsPerDelivered }.average(),
                g.map { it.retransmissions.toDouble() }.average(),
                g.sumOf { it.twoRhHigh },
                g.sumOf { it.twoRhLow },
                g.sumOf { it.high },
                g.sumOf { it.m1 },
                g.sumOf { it.m2 },
                g.sumOf { it.m3 },
                g.sumOf { it.low },
                g.sumOf { it.carry },
                g.sumOf { it.probe },
                g.mapNotNull { it.minCurrentHopConfidence }.minOrNull(),
                g.mapNotNull { it.minRouteConfidence }.minOrNull()
            ).joinToString(","))
        }
        println("CARBLE confirmatory seeds hitting HIGH+M1+M2+M3+LOW: $allStages / ${carble.size}")
        println("CARBLE confirmatory seeds with strict M1<M2<M3<LOW first-entry order: $strictOrder / ${carble.size}")
        println("======================================================================================================================")

        val out = File("build/research/CARBLE-CONFIRMATORY/FULL-DEGRADATION")
        if (out.exists()) out.deleteRecursively()
        val summary = runner.exportCsv(results, out)
        val events = runner.exportCarbleEventCsv(results, out)
        val audit = runner.exportTransitionAuditCsv(results, out)
        val relay = runner.exportRelayBurdenCsv(results, out)
        val resource = runner.exportResourceSummaryCsv(results, out)
        assertTrue(summary.exists())
        assertTrue(events.exists())
        assertTrue(audit.exists())
        assertTrue(relay.exists())
        assertTrue(resource.exists())
        assertEquals(results.size + 1, summary.readLines().size)
        assertEquals(carble.size + 1, audit.readLines().size)
    }
}
