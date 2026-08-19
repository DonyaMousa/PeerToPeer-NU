package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class TimedNetworkSimulatorTest {

    @Test
    fun `packet travels across multiple timed nodes`() {

        val simulation =
            SimulationEngine()

        val transmitter =
            DeterministicTimedLinkTransmitter(
                maxAttempts = 3,
                delayPerAttempt = 1L
            ) {
                    _,
                    _,
                    _,
                    _ ->

                true
            }

        val network =
            TimedNetworkSimulator(
                simulationEngine = simulation,
                linkTransmitter = transmitter
            )

        network.addNode(
            nodeId = "B",
            queueCapacity = 5,
            serviceTime = 3L
        )

        network.addNode(
            nodeId = "C",
            queueCapacity = 5,
            serviceTime = 4L
        )

        network.addNode(
            nodeId = "D",
            queueCapacity = 5,
            serviceTime = 1L
        )

        val packet =
            Packet(
                messageId = "MSG-A-0",
                sourceId = "A",
                destinationId = "D",
                createdAt = 0L,
                ttl = 10,
                payload = "Hello D"
            )

        network.send(
            packet = packet,
            path =
                listOf(
                    "A",
                    "B",
                    "C",
                    "D"
                )
        )

        simulation.run()

        val results =
            network.getResults()

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
            11L,
            result.deliveredAt
        )

        assertEquals(
            11L,
            result.endToEndLatency()
        )

        println()
        println(
            "===== GENERAL TIMED NETWORK ====="
        )

        println(
            "Path: [A, B, C, D]"
        )

        println(
            "Delivered: ${result.delivered}"
        )

        println(
            "Delivered at: ${result.deliveredAt}"
        )

        println(
            "End-to-end latency: ${
                result.endToEndLatency()
            }"
        )

        println(
            "B processed: ${
                network.getNode("B")
                    ?.processedPackets
            }"
        )

        println(
            "C processed: ${
                network.getNode("C")
                    ?.processedPackets
            }"
        )

        println(
            "D processed: ${
                network.getNode("D")
                    ?.processedPackets
            }"
        )

        println(
            "================================="
        )
        println()
    }
}