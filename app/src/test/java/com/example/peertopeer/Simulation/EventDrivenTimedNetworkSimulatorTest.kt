package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDrivenTimedNetworkSimulatorTest {

    @Test
    fun packet_can_recover_when_link_changes_between_retries() {

        val engine = SimulationEngine()

        var bcLinkUp = false

        /*
         * B->C attempt #1 will happen at t=5.
         *
         * We bring B->C UP at t=6.
         *
         * B->C attempt #2 happens at t=7,
         * therefore it should succeed.
         */
        engine.schedule(6L) {

            bcLinkUp = true

            println(
                "t=${engine.currentTime}: B-C LINK UP"
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
                            _,
                            _ ->

                        if (
                            from == "B" &&
                            to == "C"
                        ) {
                            bcLinkUp
                        } else {
                            true
                        }
                    }
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = engine,
                eventDrivenLinkTransmitter =
                    transmitter
            )

        /*
         * Source A itself does not need to be
         * a TimedNetworkNode.
         *
         * B, C and D are processing nodes.
         */

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
                messageId = "MSG-A-1",
                sourceId = "A",
                destinationId = "D",
                createdAt = 0,
                ttl = 10,
                payload = "Hello D"
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

        val results =
            simulator.getResults()

        assertEquals(
            1,
            results.size
        )

        val result =
            results.single()

        assertTrue(
            result.delivered
        )

        assertEquals(
            11L,
            result.deliveredAt
        )

        assertEquals(
            11L,
            result.endToEndLatency()
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
            3,
            telemetry.successfulHopTransmissions()
        )

        assertEquals(
            1,
            telemetry.failedTransmissionAttempts()
        )

        assertEquals(
            1,
            telemetry.retransmissions()
        )

        println(
            "===== EVENT-DRIVEN NETWORK INTEGRATION ====="
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
            "Logical hop attempts: " +
                    telemetry.logicalHopAttempts()
        )

        println(
            "Transmission attempts: " +
                    telemetry.transmissionAttempts()
        )

        println(
            "Successful hops: " +
                    telemetry.successfulHopTransmissions()
        )

        println(
            "Failed attempts: " +
                    telemetry.failedTransmissionAttempts()
        )

        println(
            "Retransmissions: " +
                    telemetry.retransmissions()
        )

        println(
            "============================================"
        )
    }
}