package com.example.peertopeer.experiment

import com.example.peertopeer.network.Packet
import com.example.peertopeer.simulation.SimulatedServiceNode
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedForwardingSimulator
import com.example.peertopeer.simulation.TimedNetworkTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class B0EndToEndTimedExperimentTest {

    @Test
    fun `timed forwarding produces end to end metrics`() {

        val simulation =
            SimulationEngine()

        lateinit var forwardingSimulator:
                TimedForwardingSimulator

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 10,
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

        val packetCount = 5

        for (index in 0 until packetCount) {

            val creationTime =
                index.toLong()

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

        assertEquals(
            5,
            telemetry.generatedPackets()
        )

        assertEquals(
            5,
            telemetry.deliveredPackets()
        )

        assertEquals(
            0,
            telemetry.droppedPackets()
        )

        assertEquals(
            1.0,
            telemetry.packetDeliveryRatio(),
            0.0001
        )

        println()
        println(
            "===== B0 END-TO-END TIMED EXPERIMENT ====="
        )

        println(
            "Generated: ${telemetry.generatedPackets()}"
        )

        println(
            "Delivered: ${telemetry.deliveredPackets()}"
        )

        println(
            "Dropped: ${telemetry.droppedPackets()}"
        )

        println(
            "PDR: ${telemetry.packetDeliveryRatio() * 100.0}%"
        )

        println(
            "Average latency: ${telemetry.averageLatency()}"
        )

        println(
            "Median latency: ${telemetry.medianLatency()}"
        )

        println(
            "Max latency: ${telemetry.maxLatency()}"
        )

        println(
            "Relay average queue wait: " +
                    relayB.averageQueueWaitingTime()
        )

        println(
            "Relay max queue wait: " +
                    relayB.maxQueueWaitingTime
        )

        println(
            "=========================================="
        )

        println()
    }
}
