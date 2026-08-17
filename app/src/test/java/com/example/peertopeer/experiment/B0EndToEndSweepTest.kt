package com.example.peertopeer.experiment

import com.example.peertopeer.network.Packet
import com.example.peertopeer.simulation.SimulatedServiceNode
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedForwardingSimulator
import com.example.peertopeer.simulation.TimedNetworkTelemetry
import org.junit.Test

class B0EndToEndLoadSweepTest {

    data class LoadResult(
        val interval: Long,
        val generated: Int,
        val delivered: Int,
        val dropped: Int,
        val pdr: Double,
        val averageLatency: Double,
        val medianLatency: Double,
        val maxLatency: Long,
        val averageQueueWait: Double,
        val maxQueueWait: Long
    )

    @Test
    fun `end to end performance degrades as traffic increases`() {

        val intervals =
            listOf(
                6L,
                3L,
                1L
            )

        val results =
            intervals.map { interval ->

                runScenario(
                    interval = interval
                )
            }

        println()
        println(
            "===== B0 END-TO-END LOAD SWEEP ====="
        )

        println(
            "Interval | Generated | Delivered | Dropped | PDR | AvgLatency | MedianLatency | MaxLatency | AvgQueueWait | MaxQueueWait"
        )

        results.forEach { result ->

            println(
                "${result.interval} | " +
                        "${result.generated} | " +
                        "${result.delivered} | " +
                        "${result.dropped} | " +
                        "${result.pdr * 100.0}% | " +
                        "${result.averageLatency} | " +
                        "${result.medianLatency} | " +
                        "${result.maxLatency} | " +
                        "${result.averageQueueWait} | " +
                        "${result.maxQueueWait}"
            )
        }

        println(
            "===================================="
        )
        println()
    }

    private fun runScenario(
        interval: Long
    ): LoadResult {

        val simulation =
            SimulationEngine()

        lateinit var forwardingSimulator:
                TimedForwardingSimulator

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 5,
                serviceTime = 5L,
                simulationEngine = simulation
            ) { packetState, completionTime ->

                forwardingSimulator
                    .recordRelayProcessed(
                        packetState = packetState,
                        completionTime = completionTime
                    )
            }

        forwardingSimulator =
            TimedForwardingSimulator(
                simulationEngine = simulation,
                relayNode = relayB,
                relayToDestinationDelay = 2L
            )

        val packetCount = 10

        for (index in 0 until packetCount) {

            val creationTime =
                index * interval

            val packet =
                Packet(
                    messageId = "MSG-$index",
                    sourceId = "A",
                    destinationId = "D",
                    createdAt = creationTime,
                    ttl = 10,
                    payload = "Message $index"
                )

            simulation.schedule(
                atTime = creationTime
            ) {

                forwardingSimulator
                    .sendThroughRelay(
                        packet = packet,
                        relayNodeId = "B"
                    )
            }
        }

        simulation.run()

        val telemetry =
            TimedNetworkTelemetry()

        forwardingSimulator
            .getResults()
            .forEach {
                telemetry.record(it)
            }

        return LoadResult(
            interval = interval,
            generated = telemetry.generatedPackets(),
            delivered = telemetry.deliveredPackets(),
            dropped = telemetry.droppedPackets(),
            pdr = telemetry.packetDeliveryRatio(),
            averageLatency = telemetry.averageLatency(),
            medianLatency = telemetry.medianLatency(),
            maxLatency = telemetry.maxLatency(),
            averageQueueWait =
                relayB.averageQueueWaitingTime(),
            maxQueueWait =
                relayB.maxQueueWaitingTime
        )
    }
}
