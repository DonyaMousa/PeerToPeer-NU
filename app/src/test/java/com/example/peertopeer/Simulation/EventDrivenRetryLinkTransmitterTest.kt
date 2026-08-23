package com.example.peertopeer.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDrivenRetryLinkTransmitterTest {

    @Test
    fun retries_happen_as_separate_simulation_events() {

        val engine = SimulationEngine()

        val attemptTimes = mutableListOf<Long>()

        var completedResult: TimedLinkResult? = null
        var completedAt: Long? = null

        val transmitter = EventDrivenRetryLinkTransmitter(
            simulationEngine = engine,
            maxAttempts = 3,
            delayPerAttempt = 2,
            attemptPolicy = TimedLinkAttemptPolicy {
                    _,
                    _,
                    _,
                    attemptNumber,
                    attemptTime ->

                attemptTimes.add(attemptTime)

                attemptNumber == 3
            }
        )

        transmitter.transmit(
            fromNodeId = "B",
            toNodeId = "C",
            messageId = "MSG-A-1",
            startTime = 0
        ) { result, completionTime ->

            completedResult = result
            completedAt = completionTime
        }

        // Nothing should have happened yet.
        assertTrue(attemptTimes.isEmpty())
        assertNull(completedResult)

        engine.run()

        val result = requireNotNull(completedResult)

        assertEquals(
            listOf(2L, 4L, 6L),
            attemptTimes
        )

        assertTrue(result.success)
        assertEquals(3, result.attempts)
        assertEquals(2, result.retransmissions)
        assertEquals(6L, result.totalDelay)
        assertEquals(6L, completedAt)

        println("===== EVENT-DRIVEN RETRY TEST =====")
        println("Attempt times: $attemptTimes")
        println("Success: ${result.success}")
        println("Attempts: ${result.attempts}")
        println("Retransmissions: ${result.retransmissions}")
        println("Total delay: ${result.totalDelay}")
        println("Completed at: $completedAt")
        println("===================================")
    }

    @Test
    fun topology_can_change_between_retry_attempts() {

        val engine = SimulationEngine()

        var linkUp = false

        val attemptTimes = mutableListOf<Long>()

        var completedResult: TimedLinkResult? = null
        var completedAt: Long? = null

        /*
         * Link starts DOWN.
         *
         * attempt #1 happens at t=2 → fails
         *
         * at t=3 the network changes:
         * B-C becomes UP
         *
         * attempt #2 happens at t=4
         * and should now succeed.
         */
        engine.schedule(3L) {
            linkUp = true

            println(
                "t=${engine.currentTime}: B-C changed to UP"
            )
        }

        val transmitter = EventDrivenRetryLinkTransmitter(
            simulationEngine = engine,
            maxAttempts = 3,
            delayPerAttempt = 2,
            attemptPolicy = TimedLinkAttemptPolicy {
                    _,
                    _,
                    _,
                    _,
                    attemptTime ->

                attemptTimes.add(attemptTime)

                linkUp
            }
        )

        transmitter.transmit(
            fromNodeId = "B",
            toNodeId = "C",
            messageId = "MSG-A-2",
            startTime = 0
        ) { result, completionTime ->

            completedResult = result
            completedAt = completionTime
        }

        engine.run()

        val result = requireNotNull(completedResult)

        assertEquals(
            listOf(2L, 4L),
            attemptTimes
        )

        assertTrue(result.success)
        assertEquals(2, result.attempts)
        assertEquals(1, result.retransmissions)
        assertEquals(4L, result.totalDelay)
        assertEquals(4L, completedAt)

        println("===== TOPOLOGY CHANGE BETWEEN RETRIES =====")
        println("Attempt times: $attemptTimes")
        println("Success: ${result.success}")
        println("Attempts: ${result.attempts}")
        println("Completed at: $completedAt")
        println("===========================================")
    }

    @Test
    fun retry_exhaustion_consumes_full_simulated_time() {

        val engine = SimulationEngine()

        val attemptTimes = mutableListOf<Long>()

        var completedResult: TimedLinkResult? = null
        var completedAt: Long? = null

        val transmitter = EventDrivenRetryLinkTransmitter(
            simulationEngine = engine,
            maxAttempts = 3,
            delayPerAttempt = 2,
            attemptPolicy = TimedLinkAttemptPolicy {
                    _,
                    _,
                    _,
                    _,
                    attemptTime ->

                attemptTimes.add(attemptTime)

                false
            }
        )

        transmitter.transmit(
            fromNodeId = "B",
            toNodeId = "C",
            messageId = "MSG-A-3",
            startTime = 0
        ) { result, completionTime ->

            completedResult = result
            completedAt = completionTime
        }

        engine.run()

        val result = requireNotNull(completedResult)

        assertEquals(
            listOf(2L, 4L, 6L),
            attemptTimes
        )

        assertFalse(result.success)
        assertEquals(3, result.attempts)
        assertEquals(2, result.retransmissions)
        assertEquals(6L, result.totalDelay)

        // This is the critical assertion.
        //
        // Failure did NOT happen instantly at t=0.
        // The failed transmission finished at t=6.
        assertEquals(6L, completedAt)

        println("===== EVENT-DRIVEN RETRY EXHAUSTION =====")
        println("Attempt times: $attemptTimes")
        println("Success: ${result.success}")
        println("Attempts: ${result.attempts}")
        println("Retransmissions: ${result.retransmissions}")
        println("Total delay: ${result.totalDelay}")
        println("Completed at: $completedAt")
        println("=========================================")
    }
}