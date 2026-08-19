package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedNetworkRetryPathTest {

    @Test
    fun `retry on middle hop increases end to end latency`() {

        val simulation =
            SimulationEngine()

        val transmitter =
            DeterministicTimedLinkTransmitter(
                maxAttempts = 3,
                delayPerAttempt = 1L
            ) {
                    fromNodeId,
                    toNodeId,
                    _,
                    attemptNumber ->

                if (
                    fromNodeId == "B" &&
                    toNodeId == "C"
                ) {
                    attemptNumber == 2
                } else {
                    true
                }
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

        val result =
            network
                .getResults()
                .first()

        assertTrue(
            result.delivered
        )

        assertEquals(
            12L,
            result.deliveredAt
        )

        assertEquals(
            12L,
            result.endToEndLatency()
        )

        println()
        println(
            "===== TIMED NETWORK RETRY PATH ====="
        )

        println(
            "Path: [A, B, C, D]"
        )

        println(
            "B -> C: first attempt fails, second succeeds"
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
            "===================================="
        )

        println()
    }
}
