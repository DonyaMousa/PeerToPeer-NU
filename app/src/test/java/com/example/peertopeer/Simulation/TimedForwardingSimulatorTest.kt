package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedForwardingSimulatorTest {

    @Test
    fun `packet is delivered after relay processing and forwarding delay`() {

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

        val packet =
            Packet(
                messageId = "MSG-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 0L,
                ttl = 10,
                payload = "Hello D"
            )

        simulation.schedule(
            atTime = 0L
        ) {

            forwardingSimulator
                .sendThroughRelay(
                    packet = packet,
                    relayNodeId = "B"
                )
        }

        simulation.run()

        val results =
            forwardingSimulator
                .getResults()

        assertEquals(
            1,
            results.size
        )

        val result =
            results.first()

        assertTrue(
            result.delivered
        )

        assertEquals(
            7L,
            result.deliveredAt
        )

        assertEquals(
            7L,
            result.endToEndLatency()
        )

        println()
        println(
            "===== TIMED END-TO-END TEST ====="
        )

        println(
            "Packet: ${result.messageId}"
        )

        println(
            "Created at: ${result.createdAt}"
        )

        println(
            "Delivered at: ${result.deliveredAt}"
        )

        println(
            "End-to-end latency: " +
                    result.endToEndLatency()
        )

        println(
            "Delivered: ${result.delivered}"
        )

        println(
            "================================="
        )

        println()
    }
}
