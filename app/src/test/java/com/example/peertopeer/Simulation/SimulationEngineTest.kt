package com.example.peertopeer.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationEngineTest {

    @Test
    fun `events execute in simulated time order`() {

        val simulation =
            SimulationEngine()

        val executed =
            mutableListOf<String>()

        /*
         * Intentionally schedule events
         * in the wrong order.
         */

        simulation.schedule(
            atTime = 10L
        ) {
            executed.add("TIME-10")
        }

        simulation.schedule(
            atTime = 2L
        ) {
            executed.add("TIME-2")
        }

        simulation.schedule(
            atTime = 5L
        ) {
            executed.add("TIME-5")
        }

        simulation.run()

        assertEquals(
            listOf(
                "TIME-2",
                "TIME-5",
                "TIME-10"
            ),
            executed
        )

        assertEquals(
            10L,
            simulation.currentTime
        )

        assertTrue(
            simulation.isIdle()
        )
    }

    @Test
    fun `events with same time execute deterministically`() {

        val simulation =
            SimulationEngine()

        val executed =
            mutableListOf<String>()

        simulation.schedule(
            atTime = 5L
        ) {
            executed.add("FIRST")
        }

        simulation.schedule(
            atTime = 5L
        ) {
            executed.add("SECOND")
        }

        simulation.schedule(
            atTime = 5L
        ) {
            executed.add("THIRD")
        }

        simulation.run()

        assertEquals(
            listOf(
                "FIRST",
                "SECOND",
                "THIRD"
            ),
            executed
        )
    }

    @Test
    fun `runUntil processes only events inside experiment window`() {

        val simulation =
            SimulationEngine()

        val executed =
            mutableListOf<String>()

        simulation.schedule(
            atTime = 3L
        ) {
            executed.add("TIME-3")
        }

        simulation.schedule(
            atTime = 8L
        ) {
            executed.add("TIME-8")
        }

        simulation.schedule(
            atTime = 20L
        ) {
            executed.add("TIME-20")
        }

        simulation.runUntil(
            endTime = 10L
        )

        assertEquals(
            listOf(
                "TIME-3",
                "TIME-8"
            ),
            executed
        )

        assertEquals(
            10L,
            simulation.currentTime
        )

        assertEquals(
            1,
            simulation.pendingEvents()
        )

        assertFalse(
            simulation.isIdle()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot schedule event in the past`() {

        val simulation =
            SimulationEngine()

        simulation.schedule(
            atTime = 10L
        ) {
            // No action required.
        }

        simulation.run()

        /*
         * Current simulation time is now 10.
         *
         * Scheduling at time 5 must fail.
         */
        simulation.schedule(
            atTime = 5L
        ) {
            // Invalid.
        }
    }
}
