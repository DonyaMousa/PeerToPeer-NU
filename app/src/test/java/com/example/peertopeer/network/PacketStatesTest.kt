package com.example.peertopeer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketStateTest {

    @Test
    fun `packet state moves across hops correctly`() {

        val packet =
            Packet(
                messageId = "MSG-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Hello"
            )

        val initialState =
            PacketState(
                packet = packet,
                currentNodeId = "A",
                remainingTtl = packet.ttl
            )

        val afterB =
            initialState.forwardTo("B")

        val afterC =
            afterB.forwardTo("C")

        assertEquals(
            "C",
            afterC.currentNodeId
        )

        assertEquals(
            3,
            afterC.remainingTtl
        )

        assertEquals(
            2,
            afterC.hopCount
        )

        assertFalse(
            afterC.delivered
        )

        assertFalse(
            afterC.dropped
        )
    }

    @Test
    fun `packet can be marked delivered`() {

        val packet =
            Packet(
                messageId = "MSG-002",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Hello"
            )

        val state =
            PacketState(
                packet = packet,
                currentNodeId = "D",
                remainingTtl = 2,
                hopCount = 3
            ).markDelivered()

        assertTrue(
            state.delivered
        )

        assertFalse(
            state.dropped
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `packet cannot forward when ttl is exhausted`() {

        val packet =
            Packet(
                messageId = "MSG-003",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 1,
                payload = "Hello"
            )

        val state =
            PacketState(
                packet = packet,
                currentNodeId = "B",
                remainingTtl = 0,
                hopCount = 1
            )

        state.forwardTo("C")
    }
}
