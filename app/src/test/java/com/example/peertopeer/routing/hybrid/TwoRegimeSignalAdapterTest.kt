package com.example.peertopeer.routing.hybrid

import com.example.peertopeer.routing.mm.MultiMetricLinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoRegimeSignalAdapterTest {

    private val adapter =
        TwoRegimeSignalAdapter()

    @Test
    fun healthy_link_produces_high_suitability_signals() {

        val state =
            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    1.0,

                observedDelay =
                    0.0,

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

        assertEquals(
            1.0,
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
    fun instability_reduces_stability_signal() {

        val state =
            createBaseState(
                recentLinkChanges = 3
            )

        val signals =
            adapter.fromLinkState(
                state
            )

        /*
         * 3 / 5 = 0.60 instability
         *
         * stability = 1 - 0.60 = 0.40
         */
        assertEquals(
            0.40,
            signals.stability,
            0.000001
        )
    }

    @Test
    fun delay_and_queue_pressure_reduce_timeliness() {

        val state =
            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    1.0,

                observedDelay =
                    5.0,

                delayReference =
                    10.0,

                queueOccupancy =
                    5,

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

        /*
         * Delay suitability:
         *
         * 1 - 5/10 = 0.5
         *
         * Queue suitability:
         *
         * 1 - 5/10 = 0.5
         *
         * Timeliness:
         *
         * (0.5 + 0.5) / 2 = 0.5
         */
        assertEquals(
            0.50,
            signals.timeliness,
            0.000001
        )
    }

    @Test
    fun energy_penalty_is_converted_to_resource_suitability() {

        val state =
            createBaseState(
                energyPenalty =
                    0.30
            )

        val signals =
            adapter.fromLinkState(
                state
            )

        assertEquals(
            0.70,
            signals.resourceSuitability,
            0.000001
        )
    }

    @Test
    fun delivery_success_is_also_used_as_signal_proxy() {

        val state =
            createBaseState(
                successRate =
                    0.65
            )

        val signals =
            adapter.fromLinkState(
                state
            )

        assertEquals(
            0.65,
            signals.deliverySuccess,
            0.000001
        )

        assertEquals(
            0.65,
            signals.signalReliability,
            0.000001
        )
    }

    @Test
    fun adapter_output_can_drive_two_regime_controller() {

        val state =
            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    1.0,

                observedDelay =
                    0.0,

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

        val controller =
            TwoRegimeController()

        val decision =
            controller.decide(
                signals
            )

        assertEquals(
            TwoRegimeState.HIGH,
            decision.state
        )

        assertEquals(
            1.0,
            decision.confidence,
            0.000001
        )
    }

    // =====================================================
    // HELPER
    // =====================================================

    private fun createBaseState(
        successRate: Double = 1.0,
        recentLinkChanges: Int = 0,
        energyPenalty: Double = 0.0
    ): MultiMetricLinkState {

        return MultiMetricLinkState(

            fromNodeId =
                "A",

            toNodeId =
                "B",

            successRate =
                successRate,

            observedDelay =
                0.0,

            delayReference =
                10.0,

            queueOccupancy =
                0,

            queueCapacity =
                10,

            recentLinkChanges =
                recentLinkChanges,

            instabilityReference =
                5,

            energyPenaltyNormalized =
                energyPenalty
        )
    }
}