package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedNetworkRetryExhaustionTest {

    @Test
    fun `packet drops when retries are exhausted`() {

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
                    _ ->

                if (
                    fromNodeId == "B" &&
                    toNodeId == "C"
                ) {
                    false
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

        val results =
            network.getResults()

        assertEquals(
            1,
            results.size
        )

        val result =
            results.first()

        assertFalse(
            result.delivered
        )

        assertTrue(
            result.dropped
        )

        assertEquals(
            PacketDropReason.RETRY_EXHAUSTED,
            result.dropReason
        )

        assertEquals(
            null,
            result.deliveredAt
        )

        assertEquals(
            null,
            result.endToEndLatency()
        )

        /*
         * B receives and processes the packet.
         * B -> C then fails on all retry attempts.
         *
         * Therefore C and D should never process it.
         */
        assertEquals(
            1,
            network.getNode("B")
                ?.processedPackets
        )

        assertEquals(
            0,
            network.getNode("C")
                ?.processedPackets
        )

        assertEquals(
            0,
            network.getNode("D")
                ?.processedPackets
        )

        println()
        println(
            "===== TIMED RETRY EXHAUSTION ====="
        )

        println(
            "Delivered: ${result.delivered}"
        )

        println(
            "Dropped: ${result.dropped}"
        )

        println(
            "Drop reason: ${result.dropReason}"
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
            "=================================="
        )
        println()
    }
}
