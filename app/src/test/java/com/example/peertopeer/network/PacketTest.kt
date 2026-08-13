package com.example.peertopeer.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketTest {

    @Test
    fun `packet stores required message information`() {

        val packet =
            Packet(
                messageId = "MSG-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 10,
                payload = "Hello D"
            )

        assertEquals(
            "MSG-001",
            packet.messageId
        )

        assertEquals(
            "A",
            packet.sourceId
        )

        assertEquals(
            "D",
            packet.destinationId
        )

        assertEquals(
            1000L,
            packet.createdAt
        )

        assertEquals(
            10,
            packet.ttl
        )

        assertEquals(
            "Hello D",
            packet.payload
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `packet rejects non positive ttl`() {

        Packet(
            messageId = "MSG-002",
            sourceId = "A",
            destinationId = "D",
            createdAt = 1000L,
            ttl = 0,
            payload = "Invalid"
        )
    }
}
