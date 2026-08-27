package com.example.peertopeer.routing.mm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiMetricObservationTrackerTest {

    @Test
    fun degraded_edge_gets_lower_success_rate() {

        val store =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = store,
                reliabilityWindowSize = 4
            )

        tracker.registerEdge(
            fromNodeId = "A",
            toNodeId = "B",
            queueCapacity = 10
        )

        tracker.observeTransmission(
            "A",
            "B",
            success = false
        )

        tracker.observeTransmission(
            "A",
            "B",
            success = false
        )

        tracker.observeTransmission(
            "A",
            "B",
            success = true
        )

        tracker.observeTransmission(
            "A",
            "B",
            success = true
        )

        val state =
            store.get(
                "A",
                "B"
            )

        assertEquals(
            0.5,
            state!!.successRate,
            0.000001
        )
    }

    @Test
    fun old_failures_leave_sliding_window_after_recovery() {

        val store =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = store,
                reliabilityWindowSize = 4
            )

        tracker.registerEdge(
            fromNodeId = "A",
            toNodeId = "B",
            queueCapacity = 10
        )

        /*
         * Initial degraded period.
         */
        repeat(4) {

            tracker.observeTransmission(
                "A",
                "B",
                success = false
            )
        }

        assertEquals(
            0.0,
            store.get(
                "A",
                "B"
            )!!.successRate,
            0.000001
        )

        /*
         * Four newer healthy observations replace all
         * four old failures in the bounded window.
         */
        repeat(4) {

            tracker.observeTransmission(
                "A",
                "B",
                success = true
            )
        }

        assertEquals(
            1.0,
            store.get(
                "A",
                "B"
            )!!.successRate,
            0.000001
        )
    }

    @Test
    fun queue_state_updates_incoming_edge() {

        val store =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = store
            )

        tracker.registerEdge(
            "A",
            "B",
            queueCapacity = 5
        )

        tracker.observeQueue(
            nodeId = "B",
            queueOccupancy = 4,
            queueCapacity = 5
        )

        val state =
            store.get(
                "A",
                "B"
            )!!

        assertEquals(
            4,
            state.queueOccupancy
        )

        assertEquals(
            5,
            state.queueCapacity
        )
    }

    @Test
    fun topology_instability_is_bounded() {

        val store =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = store,
                instabilityReference = 5
            )

        tracker.registerEdge(
            "A",
            "B",
            10
        )

        repeat(20) {

            tracker.observeTopologyChange(
                "A",
                "B"
            )
        }

        val state =
            store.get(
                "A",
                "B"
            )!!

        assertEquals(
            5,
            state.recentLinkChanges
        )
    }

    @Test
    fun instability_can_decay() {

        val store =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = store,
                instabilityReference = 5
            )

        tracker.registerEdge(
            "A",
            "B",
            10
        )

        repeat(3) {

            tracker.observeTopologyChange(
                "A",
                "B"
            )
        }

        val before =
            store.get(
                "A",
                "B"
            )!!

        assertEquals(
            3,
            before.recentLinkChanges
        )

        tracker.decayInstability()

        val after =
            store.get(
                "A",
                "B"
            )!!

        assertEquals(
            2,
            after.recentLinkChanges
        )

        assertTrue(
            after.recentLinkChanges <
                    before.recentLinkChanges
        )
    }
}
