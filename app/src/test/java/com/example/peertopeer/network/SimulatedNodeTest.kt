package com.example.peertopeer.network

import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedNodeTest {

    private fun createPacketState(
        messageId: String,
        currentNodeId: String
    ): PacketState {

        val packet =
            Packet(
                messageId = messageId,
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Test"
            )

        return PacketState(
            packet = packet,
            currentNodeId = currentNodeId,
            remainingTtl = packet.ttl
        )
    }

    @Test
    fun `simulated node receives and processes packet`() {

        val nodeB =
            SimulatedNode(
                node = Node(
                    "B",
                    "Node B"
                ),
                queueCapacity = 3
            )

        val packet =
            createPacketState(
                messageId = "MSG-001",
                currentNodeId = "B"
            )

        val accepted =
            nodeB.receive(packet)

        assertTrue(accepted)

        assertEquals(
            1,
            nodeB.queuedPackets()
        )

        val processed =
            nodeB.nextPacket()

        assertEquals(
            "MSG-001",
            processed!!
                .packet
                .messageId
        )

        assertEquals(
            0,
            nodeB.queuedPackets()
        )
    }

    @Test
    fun `simulated node rejects packet when queue is full`() {

        val nodeB =
            SimulatedNode(
                node = Node(
                    "B",
                    "Node B"
                ),
                queueCapacity = 2
            )

        assertTrue(
            nodeB.receive(
                createPacketState(
                    "MSG-001",
                    "B"
                )
            )
        )

        assertTrue(
            nodeB.receive(
                createPacketState(
                    "MSG-002",
                    "B"
                )
            )
        )

        assertTrue(
            nodeB.isQueueFull()
        )

        val accepted =
            nodeB.receive(
                createPacketState(
                    "MSG-003",
                    "B"
                )
            )

        assertFalse(accepted)

        assertEquals(
            2,
            nodeB.queuedPackets()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `node rejects packet assigned to different node`() {

        val nodeB =
            SimulatedNode(
                node = Node(
                    "B",
                    "Node B"
                ),
                queueCapacity = 2
            )

        val packetAtC =
            createPacketState(
                messageId = "MSG-001",
                currentNodeId = "C"
            )

        nodeB.receive(packetAtC)
    }
}
