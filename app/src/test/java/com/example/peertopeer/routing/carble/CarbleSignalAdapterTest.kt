package com.example.peertopeer.routing.carble

import com.example.peertopeer.routing.mm.MultiMetricLinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class CarbleSignalAdapterTest {

    private val adapter =
        CarbleSignalAdapter()


    @Test
    fun healthy_link_produces_expected_signals() {

        val state =
            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    1.0,

                observedDelay =
                    1.0,

                delayReference =
                    10.0,

                queueOccupancy =
                    0,

                queueCapacity =
                    10,

                recentLinkChanges =
                    0,

                instabilityReference =
                    5,

                energyPenaltyNormalized =
                    0.0
            )


        val signals =
            adapter.fromLinkState(
                state
            )


        assertEquals(
            1.0,
            signals.deliverySuccess,
            0.000001
        )

        assertEquals(
            1.0,
            signals.freshness,
            0.000001
        )

        assertEquals(
            1.0,
            signals.stability,
            0.000001
        )

        /*
         * delay suitability = .9
         * queue suitability = 1.0
         *
         * T = .95
         */
        assertEquals(
            0.95,
            signals.timeliness,
            0.000001
        )

        assertEquals(
            1.0,
            signals.signalReliability,
            0.000001
        )

        assertEquals(
            1.0,
            signals.resourceSuitability,
            0.000001
        )
    }


    @Test
    fun degraded_state_maps_to_expected_signals() {

        val state =
            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    0.5,

                observedDelay =
                    5.0,

                delayReference =
                    10.0,

                queueOccupancy =
                    5,

                queueCapacity =
                    10,

                recentLinkChanges =
                    2,

                instabilityReference =
                    5,

                energyPenaltyNormalized =
                    0.25
            )


        val signals =
            adapter.fromLinkState(
                state
            )


        assertEquals(
            0.5,
            signals.deliverySuccess,
            0.000001
        )

        assertEquals(
            1.0,
            signals.freshness,
            0.000001
        )

        /*
         * R = 1 - 2/5 = .6
         */
        assertEquals(
            0.6,
            signals.stability,
            0.000001
        )

        /*
         * delay suitability = .5
         * queue suitability = .5
         *
         * T = .5
         */
        assertEquals(
            0.5,
            signals.timeliness,
            0.000001
        )

        assertEquals(
            0.5,
            signals.signalReliability,
            0.000001
        )

        assertEquals(
            0.75,
            signals.resourceSuitability,
            0.000001
        )
    }
}