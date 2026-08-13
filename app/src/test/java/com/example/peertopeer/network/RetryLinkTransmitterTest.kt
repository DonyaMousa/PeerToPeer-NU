package com.example.peertopeer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryLinkTransmitterTest {

    private fun createPacketState(): PacketState {

        val packet =
            Packet(
                messageId = "MSG-RETRY-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Hello"
            )

        return PacketState(
            packet = packet,
            currentNodeId = "A",
            remainingTtl = packet.ttl
        )
    }

    @Test
    fun `transmission succeeds after retries`() {

        val transmitter =
            RetryLinkTransmitter(
                maxAttempts = 3
            ) { _, _, _, attempt ->

                /*
                 * Fail attempts 1 and 2.
                 * Succeed on attempt 3.
                 */
                attempt == 3
            }

        val result =
            transmitter.transmit(
                fromNodeId = "A",
                toNodeId = "B",
                packetState =
                    createPacketState()
            )

        assertTrue(
            result.success
        )

        assertEquals(
            3,
            result.attempts
        )
    }

    @Test
    fun `transmission stops immediately after ack`() {

        val transmitter =
            RetryLinkTransmitter(
                maxAttempts = 5
            ) { _, _, _, attempt ->

                /*
                 * First attempt succeeds.
                 */
                attempt == 1
            }

        val result =
            transmitter.transmit(
                fromNodeId = "A",
                toNodeId = "B",
                packetState =
                    createPacketState()
            )

        assertTrue(
            result.success
        )

        assertEquals(
            1,
            result.attempts
        )
    }

    @Test
    fun `transmission fails after retry budget exhausted`() {

        val transmitter =
            RetryLinkTransmitter(
                maxAttempts = 3
            ) { _, _, _, _ ->

                /*
                 * Every attempt fails.
                 */
                false
            }

        val result =
            transmitter.transmit(
                fromNodeId = "A",
                toNodeId = "B",
                packetState =
                    createPacketState()
            )

        assertFalse(
            result.success
        )

        assertEquals(
            3,
            result.attempts
        )
    }
}
