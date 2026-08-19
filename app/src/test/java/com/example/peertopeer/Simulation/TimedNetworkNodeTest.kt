package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedNetworkNodeTest {

    @Test
    fun `timed network node processes packet`() {

        val simulation =
            SimulationEngine()

        var processedNodeId:
                String? = null

        var processedTime:
                Long? = null

        val nodeB =
            TimedNetworkNode(
                nodeId = "B",
                queueCapacity = 5,
                serviceTime = 5L,
                simulationEngine = simulation
            ) {
                    nodeId,
                    _,
                    completionTime ->

                processedNodeId =
                    nodeId

                processedTime =
                    completionTime
            }

        val packet =
            Packet(
                messageId = "MSG-A-0",
                sourceId = "A",
                destinationId = "D",
                createdAt = 0L,
                ttl = 10,
                payload = "Hello"
            )

        val state =
            PacketState(
                packet = packet,
                currentNodeId = "B",
                remainingTtl = 9,
                hopCount = 1
            )

        val accepted =
            nodeB.receive(
                state
            )

        assertTrue(
            accepted
        )

        simulation.run()

        assertEquals(
            "B",
            processedNodeId
        )

        assertEquals(
            5L,
            processedTime
        )

        assertEquals(
            1,
            nodeB.processedPackets
        )

        println()
        println(
            "===== TIMED NETWORK NODE ====="
        )

        println(
            "Node processed: $processedNodeId"
        )

        println(
            "Completion time: $processedTime"
        )

        println(
            "Processed packets: ${nodeB.processedPackets}"
        )

        println(
            "=============================="
        )
        println()
    }
}
