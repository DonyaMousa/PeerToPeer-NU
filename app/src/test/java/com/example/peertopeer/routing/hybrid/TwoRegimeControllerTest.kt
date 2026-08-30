package com.example.peertopeer.routing.hybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoRegimeControllerTest {

    private val controller =
        TwoRegimeController()

    @Test
    fun perfect_signals_produce_high_confidence() {

        val signals =
            TwoRegimeSignals(
                deliverySuccess = 1.0,
                freshness = 1.0,
                stability = 1.0,
                timeliness = 1.0,
                signalReliability = 1.0,
                resourceSuitability = 1.0
            )

        val decision =
            controller.decide(
                signals
            )

        assertEquals(
            1.0,
            decision.confidence,
            0.000001
        )

        assertEquals(
            TwoRegimeState.HIGH,
            decision.state
        )
    }

    @Test
    fun confidence_exactly_at_high_threshold_is_high() {

        /*
         * Choose values that produce Q = 0.75 exactly.
         *
         * D = 0.5
         * F = 1.0
         * R = 1.0
         * T = 1.0
         * S = 0.0
         * B = 1.0
         *
         * Q =
         * 0.30(0.5) +
         * 0.20(1.0) +
         * 0.15(1.0) +
         * 0.15(1.0) +
         * 0.10(0.0) +
         * 0.10(1.0)
         *
         * Q = 0.75
         */

        val signals =
            TwoRegimeSignals(
                deliverySuccess = 0.5,
                freshness = 1.0,
                stability = 1.0,
                timeliness = 1.0,
                signalReliability = 0.0,
                resourceSuitability = 1.0
            )

        val decision =
            controller.decide(
                signals
            )

        assertEquals(
            0.75,
            decision.confidence,
            0.000001
        )

        assertEquals(
            TwoRegimeState.HIGH,
            decision.state
        )
    }

    @Test
    fun confidence_below_high_threshold_is_low() {

        val signals =
            TwoRegimeSignals(
                deliverySuccess = 0.60,
                freshness = 0.80,
                stability = 0.60,
                timeliness = 0.60,
                signalReliability = 0.70,
                resourceSuitability = 1.0
            )

        val decision =
            controller.decide(
                signals
            )

        assertEquals(
            0.69,
            decision.confidence,
            0.000001
        )

        assertEquals(
            TwoRegimeState.LOW,
            decision.state
        )
    }

    @Test
    fun zero_signals_produce_zero_confidence_and_low_state() {

        val signals =
            TwoRegimeSignals(
                deliverySuccess = 0.0,
                freshness = 0.0,
                stability = 0.0,
                timeliness = 0.0,
                signalReliability = 0.0,
                resourceSuitability = 0.0
            )

        val decision =
            controller.decide(
                signals
            )

        assertEquals(
            0.0,
            decision.confidence,
            0.000001
        )

        assertEquals(
            TwoRegimeState.LOW,
            decision.state
        )
    }

    @Test
    fun confidence_formula_uses_expected_weights() {

        val signals =
            TwoRegimeSignals(
                deliverySuccess = 0.8,
                freshness = 0.7,
                stability = 0.6,
                timeliness = 0.5,
                signalReliability = 0.4,
                resourceSuitability = 0.3
            )

        val confidence =
            controller.calculateConfidence(
                signals
            )

        val expected =
            0.30 * 0.8 +
                    0.20 * 0.7 +
                    0.15 * 0.6 +
                    0.15 * 0.5 +
                    0.10 * 0.4 +
                    0.10 * 0.3

        assertEquals(
            expected,
            confidence,
            0.000001
        )
    }
}