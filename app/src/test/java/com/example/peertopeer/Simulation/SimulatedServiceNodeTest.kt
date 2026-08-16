package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedServiceNodeTest {

    private fun createPacketState(
        messageId: String,
        nodeId: String
    ): PacketState {

        val packet =
            Packet(
                messageId = messageId,
                sourceId = "A",
                destinationId = "D",
                createdAt = 0L,
                ttl = 10,
                payload = messageId
            )

        return PacketState(
            packet = packet,
            currentNodeId = nodeId,
            remainingTtl = packet.ttl
        )
    }

    @Test
    fun `node processes packets according to service time`() {

        val simulation =
            SimulationEngine()

        val processed =
            mutableListOf<Pair<String, Long>>()

        val node =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 10,
                serviceTime = 3L,
                simulationEngine = simulation
            ) { packetState, completionTime ->

                processed.add(
                    packetState.packet.messageId to
                            completionTime
                )
            }

        /*
         * MSG-0 arrives at t=0.
         */
        simulation.schedule(
            atTime = 0L
        ) {

            node.receive(
                createPacketState(
                    messageId = "MSG-0",
                    nodeId = "B"
                )
            )
        }

        /*
         * MSG-1 arrives at t=1.
         */
        simulation.schedule(
            atTime = 1L
        ) {

            node.receive(
                createPacketState(
                    messageId = "MSG-1",
                    nodeId = "B"
                )
            )
        }

        /*
         * MSG-2 arrives at t=2.
         */
        simulation.schedule(
            atTime = 2L
        ) {

            node.receive(
                createPacketState(
                    messageId = "MSG-2",
                    nodeId = "B"
                )
            )
        }

        simulation.run()

        assertEquals(
            3,
            processed.size
        )

        /*
         * Processing times:
         *
         * MSG-0:
         * starts t=0
         * finishes t=3
         *
         * MSG-1:
         * starts t=3
         * finishes t=6
         *
         * MSG-2:
         * starts t=6
         * finishes t=9
         */
        assertEquals(
            "MSG-0" to 3L,
            processed[0]
        )

        assertEquals(
            "MSG-1" to 6L,
            processed[1]
        )

        assertEquals(
            "MSG-2" to 9L,
            processed[2]
        )

        assertEquals(
            3,
            node.processedPackets
        )

        assertEquals(
            0,
            node.queuedPackets()
        )

        assertTrue(
            simulation.isIdle()
        )
        assertEquals(
            2,
            node.maxQueueSize
        )

        assertEquals(
            2.0,
            node.averageQueueWaitingTime(),
            0.0001
        )

        assertEquals(
            4L,
            node.maxQueueWaitingTime
        )

        println()
        println("===== SERVICE NODE TEST =====")

        processed.forEach {
            println(
                "${it.first} completed at t=${it.second}"
            )
        }

        println(
            "Processed packets: ${node.processedPackets}"
        )

        println(
            "Dropped packets: ${node.droppedPackets}"
        )
        println(
            "Max queue size: ${node.maxQueueSize}"
        )

        println(
            "Average queue wait: ${node.averageQueueWaitingTime()}"
        )

        println(
            "Max queue wait: ${node.maxQueueWaitingTime}"
        )

        println("=============================")
        println()
    }

}
