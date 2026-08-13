package com.example.peertopeer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketQueueTest {

    private fun createState(
        messageId: String
    ): PacketState {

        val packet =
            Packet(
                messageId = messageId,
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
    fun `queue processes packets in fifo order`() {

        val queue =
            PacketQueue(
                capacity = 3
            )

        val first =
            createState("MSG-001")

        val second =
            createState("MSG-002")

        val third =
            createState("MSG-003")

        assertTrue(
            queue.enqueue(first)
        )

        assertTrue(
            queue.enqueue(second)
        )

        assertTrue(
            queue.enqueue(third)
        )

        assertEquals(
            3,
            queue.size()
        )

        assertEquals(
            "MSG-001",
            queue.dequeue()!!
                .packet
                .messageId
        )

        assertEquals(
            "MSG-002",
            queue.dequeue()!!
                .packet
                .messageId
        )

        assertEquals(
            "MSG-003",
            queue.dequeue()!!
                .packet
                .messageId
        )

        assertTrue(
            queue.isEmpty()
        )
    }

    @Test
    fun `queue rejects packet when capacity is full`() {

        val queue =
            PacketQueue(
                capacity = 2
            )

        assertTrue(
            queue.enqueue(
                createState("MSG-001")
            )
        )

        assertTrue(
            queue.enqueue(
                createState("MSG-002")
            )
        )

        assertTrue(
            queue.isFull()
        )

        val accepted =
            queue.enqueue(
                createState("MSG-003")
            )

        assertFalse(
            accepted
        )

        assertEquals(
            2,
            queue.size()
        )
    }

    @Test
    fun `empty queue returns null`() {

        val queue =
            PacketQueue(
                capacity = 2
            )

        assertNull(
            queue.dequeue()
        )
    }
}
