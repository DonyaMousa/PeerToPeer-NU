package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDrivenDropTimingTest {

    @Test
    fun retry_exhaustion_records_exact_drop_time() {

        val engine =
            SimulationEngine()

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

                        /*
                         * A->B always succeeds.
                         *
                         * B->C always fails.
                         */
                        !(from == "B" && to == "C")
                    }
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = engine,
                eventDrivenLinkTransmitter = transmitter
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

        val packet =
            Packet(
                messageId = "MSG-A-DROP-1",
                sourceId = "A",
                destinationId = "C",
                createdAt = 0,
                ttl = 10,
                payload = "Drop timing test"
            )

        simulator.send(
            packet = packet,
            path = listOf(
                "A",
                "B",
                "C"
            )
        )

        engine.run()

        val result =
            simulator.getResults().single()

        /*
         * Timeline:
         *
         * t=2  A->B succeeds
         * t=3  B finishes processing
         *
         * t=5  B->C attempt #1 fails
         * t=7  B->C attempt #2 fails
         * t=9  B->C attempt #3 fails
         *
         * Therefore the packet becomes
         * terminal at t=9.
         */

        assertFalse(
            result.delivered
        )

        assertTrue(
            result.dropped
        )

        assertEquals(
            PacketDropReason.RETRY_EXHAUSTED,
            result.dropReason
        )

        assertEquals(
            9L,
            result.droppedAt
        )

        assertEquals(
            9L,
            result.terminalTime()
        )

        assertEquals(
            9L,
            result.timeUntilTermination()
        )

        println(
            "===== EVENT-DRIVEN DROP TIMING ====="
        )

        println(
            "Delivered: ${result.delivered}"
        )

        println(
            "Dropped: ${result.dropped}"
        )

        println(
            "Reason: ${result.dropReason}"
        )

        println(
            "Dropped at: ${result.droppedAt}"
        )

        println(
            "Terminal time: ${result.terminalTime()}"
        )

        println(
            "Time until termination: " +
                    result.timeUntilTermination()
        )

        println(
            "===================================="
        )
    }
}
