package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedDynamicLinkStateTest {

    @Test
    fun active_packet_observes_real_dynamic_link_state() {

        val engine =
            SimulationEngine()

        val linkStates =
            TimedLinkStateTable()

        /*
         * Initial physical topology:
         *
         * A -- B    C -- D
         *
         * B-C starts DOWN.
         */

        linkStates.setBidirectionalLinkState(
            nodeA = "A",
            nodeB = "B",
            isUp = true,
            changedAt = 0
        )

        linkStates.setBidirectionalLinkState(
            nodeA = "B",
            nodeB = "C",
            isUp = false,
            changedAt = 0
        )

        linkStates.setBidirectionalLinkState(
            nodeA = "C",
            nodeB = "D",
            isUp = true,
            changedAt = 0
        )

        /*
         * At t=6:
         *
         * B-C becomes available.
         */
        engine.schedule(6L) {

            linkStates.setBidirectionalLinkState(
                nodeA = "B",
                nodeB = "C",
                isUp = true,
                changedAt = engine.currentTime
            )

            println(
                "t=${engine.currentTime}: " +
                        "B-C changed to UP"
            )
        }

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = engine,
                maxAttempts = 3,
                delayPerAttempt = 2,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            attemptNumber,
                            attemptTime ->

                        val linkUp =
                            linkStates.isLinkUp(
                                fromNodeId = from,
                                toNodeId = to
                            )

                        println(
                            "t=$attemptTime: " +
                                    "$from->$to " +
                                    "attempt #$attemptNumber " +
                                    "linkUp=$linkUp"
                        )

                        linkUp
                    }
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = engine,
                eventDrivenLinkTransmitter =
                    transmitter
            )

        simulator.addNode(
            nodeId = "B",
            queueCapacity = 5,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "C",
            queueCapacity = 5,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "D",
            queueCapacity = 5,
            serviceTime = 1
        )

        val packet =
            Packet(
                messageId = "MSG-DYNAMIC-1",
                sourceId = "A",
                destinationId = "D",
                createdAt = 0,
                ttl = 10,
                payload = "Dynamic topology"
            )

        simulator.send(
            packet = packet,
            path = listOf(
                "A",
                "B",
                "C",
                "D"
            )
        )

        engine.run()

        val result =
            simulator.getResults().single()

        assertTrue(
            result.delivered
        )

        assertEquals(
            11L,
            result.deliveredAt
        )

        /*
         * B-C actually changed state at t=6.
         */
        assertTrue(
            linkStates.isLinkUp(
                fromNodeId = "B",
                toNodeId = "C"
            )
        )

        assertEquals(
            6L,
            linkStates.lastChangedAt(
                fromNodeId = "B",
                toNodeId = "C"
            )
        )

        val telemetry =
            simulator.getTransmissionTelemetry()

        assertEquals(
            3,
            telemetry.logicalHopAttempts()
        )

        assertEquals(
            4,
            telemetry.transmissionAttempts()
        )

        assertEquals(
            1,
            telemetry.retransmissions()
        )

        println(
            "===== DYNAMIC LINK STATE TEST ====="
        )

        println(
            "Delivered: ${result.delivered}"
        )

        println(
            "Delivered at: ${result.deliveredAt}"
        )

        println(
            "Latency: ${result.endToEndLatency()}"
        )

        println(
            "B-C final state: " +
                    linkStates.isLinkUp(
                        fromNodeId = "B",
                        toNodeId = "C"
                    )
        )

        println(
            "B-C last changed at: " +
                    linkStates.lastChangedAt(
                        fromNodeId = "B",
                        toNodeId = "C"
                    )
        )

        println(
            "Transmission attempts: " +
                    telemetry.transmissionAttempts()
        )

        println(
            "Retransmissions: " +
                    telemetry.retransmissions()
        )

        println(
            "==================================="
        )
    }
}
