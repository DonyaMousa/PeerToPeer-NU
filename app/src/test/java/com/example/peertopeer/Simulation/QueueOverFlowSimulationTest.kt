package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketState
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOverflowSimulationTest {

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
    fun `queue overflows when packets arrive faster than service rate`() {

        val simulation =
            SimulationEngine()

        val processed =
            mutableListOf<Pair<String, Long>>()

        val node =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 2,
                serviceTime = 5L,
                simulationEngine = simulation
            ) { packetState, completionTime ->

                processed.add(
                    packetState.packet.messageId to
                            completionTime
                )
            }

        /*
         * Five packets arrive one time unit apart.
         */
        for (
        index in 0 until 5
        ) {

            simulation.schedule(
                atTime = index.toLong()
            ) {

                node.receive(
                    createPacketState(
                        messageId = "MSG-$index",
                        nodeId = "B"
                    )
                )
            }
        }

        simulation.run()

        println()
        println("===== QUEUE OVERFLOW TEST =====")

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
            "Remaining queued packets: ${node.queuedPackets()}"
        )

        println("===============================")
        println()

        /*
         * Expected behavior:
         *
         * MSG-0 starts immediately.
         *
         * While MSG-0 is being processed,
         * only two more packets fit in the queue.
         *
         * Later arrivals overflow the queue.
         */

        assertEquals(
            3,
            node.processedPackets
        )

        assertEquals(
            2,
            node.droppedPackets
        )

        assertEquals(
            0,
            node.queuedPackets()
        )
    }
}
