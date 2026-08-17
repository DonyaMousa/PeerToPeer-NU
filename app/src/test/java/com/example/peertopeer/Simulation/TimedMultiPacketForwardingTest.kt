package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Test

class TimedMultiPacketForwardingTest {

    @Test
    fun `queue waiting increases end to end latency`() {

        val simulation =
            SimulationEngine()

        lateinit var forwardingSimulator:
                TimedForwardingSimulator

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 10,
                serviceTime = 5L,
                simulationEngine = simulation
            ) { packetState, completionTime ->

                forwardingSimulator
                    .recordRelayProcessed(
                        packetState = packetState,
                        completionTime = completionTime
                    )
            }

        forwardingSimulator =
            TimedForwardingSimulator(
                simulationEngine = simulation,
                relayNode = relayB,
                relayToDestinationDelay = 2L
            )

        /*
         * Packet creation / arrival times:
         *
         * MSG-0 -> t=0
         * MSG-1 -> t=1
         * MSG-2 -> t=2
         */
        for (
        index in 0 until 3
        ) {

            val creationTime =
                index.toLong()

            val packet =
                Packet(
                    messageId =
                        "MSG-$index",
                    sourceId = "A",
                    destinationId = "D",
                    createdAt =
                        creationTime,
                    ttl = 10,
                    payload =
                        "Message $index"
                )

            simulation.schedule(
                atTime = creationTime
            ) {

                forwardingSimulator
                    .sendThroughRelay(
                        packet = packet,
                        relayNodeId = "B"
                    )
            }
        }

        simulation.run()

        val results =
            forwardingSimulator
                .getResults()
                .sortedBy {
                    it.messageId
                }

        assertEquals(
            3,
            results.size
        )

        /*
         * MSG-0:
         *
         * created = 0
         * B finishes = 5
         * D receives = 7
         *
         * latency = 7
         */
        assertEquals(
            7L,
            results[0].endToEndLatency()
        )

        /*
         * MSG-1:
         *
         * created = 1
         * waits while MSG-0 is processed
         * B finishes = 10
         * D receives = 12
         *
         * latency = 11
         */
        assertEquals(
            11L,
            results[1].endToEndLatency()
        )

        /*
         * MSG-2:
         *
         * created = 2
         * B finishes = 15
         * D receives = 17
         *
         * latency = 15
         */
        assertEquals(
            15L,
            results[2].endToEndLatency()
        )

        println()
        println(
            "===== MULTI-PACKET LATENCY TEST ====="
        )

        results.forEach { result ->

            println(
                "${result.messageId} | " +
                        "created=${result.createdAt} | " +
                        "delivered=${result.deliveredAt} | " +
                        "latency=${result.endToEndLatency()}"
            )
        }

        println(
            "Relay average queue wait: " +
                    relayB.averageQueueWaitingTime()
        )

        println(
            "Relay max queue wait: " +
                    relayB.maxQueueWaitingTime
        )

        println(
            "===================================="
        )

        println()
    }
}
