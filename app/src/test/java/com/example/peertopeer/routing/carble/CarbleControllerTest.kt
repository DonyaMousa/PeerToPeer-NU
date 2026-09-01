package com.example.peertopeer.routing.carble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarbleControllerTest {

    private val controller =
        CarbleController()


    // =====================================================
    // CONFIDENCE MODEL
    // =====================================================

    @Test
    fun all_perfect_signals_produce_confidence_one() {

        val signals =
            CarbleSignals(

                deliverySuccess =
                    1.0,

                freshness =
                    1.0,

                stability =
                    1.0,

                timeliness =
                    1.0,

                signalReliability =
                    1.0,

                resourceSuitability =
                    1.0
            )

        val confidence =
            controller.calculateConfidence(
                signals
            )

        assertEquals(
            1.0,
            confidence,
            0.000001
        )
    }


    @Test
    fun confidence_uses_expected_weights() {

        val signals =
            CarbleSignals(

                deliverySuccess =
                    0.5,

                freshness =
                    1.0,

                stability =
                    0.8,

                timeliness =
                    0.6,

                signalReliability =
                    0.5,

                resourceSuitability =
                    1.0
            )

        /*
         * 0.30 * .5 = .15
         * 0.20 * 1  = .20
         * 0.15 * .8 = .12
         * 0.15 * .6 = .09
         * 0.10 * .5 = .05
         * 0.10 * 1  = .10
         *
         * total = .71
         */
        val confidence =
            controller.calculateConfidence(
                signals
            )

        assertEquals(
            0.71,
            confidence,
            0.000001
        )
    }


    // =====================================================
    // HIGH
    // =====================================================

    @Test
    fun healthy_current_hop_and_route_are_high() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.90,

                routeConfidence =
                    0.85
            )

        assertEquals(
            CarbleRegime.HIGH,
            decision.regime
        )

        assertNull(
            decision.mediumStage
        )

        assertEquals(
            CarbleDecisionReason.HEALTHY_ROUTE,
            decision.reason
        )
    }


    @Test
    fun exact_high_boundary_is_high_when_route_is_healthy() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.75,

                routeConfidence =
                    0.75
            )

        assertEquals(
            CarbleRegime.HIGH,
            decision.regime
        )
    }


    // =====================================================
    // DOWNSTREAM WARNING
    // =====================================================

    @Test
    fun healthy_current_hop_with_degraded_downstream_route_is_m1() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.90,

                routeConfidence =
                    0.60
            )

        assertEquals(
            CarbleRegime.MEDIUM,
            decision.regime
        )

        assertEquals(
            CarbleMediumStage.M1,
            decision.mediumStage
        )

        assertEquals(
            CarbleDecisionReason.DOWNSTREAM_WARNING,
            decision.reason
        )
    }


    // =====================================================
    // M1
    // =====================================================

    @Test
    fun confidence_070_is_m1() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.70,

                routeConfidence =
                    0.70
            )

        assertEquals(
            CarbleRegime.MEDIUM,
            decision.regime
        )

        assertEquals(
            CarbleMediumStage.M1,
            decision.mediumStage
        )
    }


    @Test
    fun exact_m1_lower_boundary_is_m1() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.65,

                routeConfidence =
                    0.65
            )

        assertEquals(
            CarbleMediumStage.M1,
            decision.mediumStage
        )
    }


    // =====================================================
    // M2
    // =====================================================

    @Test
    fun confidence_060_is_m2() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.60,

                routeConfidence =
                    0.60
            )

        assertEquals(
            CarbleRegime.MEDIUM,
            decision.regime
        )

        assertEquals(
            CarbleMediumStage.M2,
            decision.mediumStage
        )
    }


    @Test
    fun exact_m2_lower_boundary_is_m2() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.55,

                routeConfidence =
                    0.55
            )

        assertEquals(
            CarbleMediumStage.M2,
            decision.mediumStage
        )
    }


    // =====================================================
    // M3
    // =====================================================

    @Test
    fun confidence_050_is_m3() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.50,

                routeConfidence =
                    0.50
            )

        assertEquals(
            CarbleRegime.MEDIUM,
            decision.regime
        )

        assertEquals(
            CarbleMediumStage.M3,
            decision.mediumStage
        )
    }


    @Test
    fun exact_low_boundary_is_still_m3() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.45,

                routeConfidence =
                    0.45
            )

        assertEquals(
            CarbleRegime.MEDIUM,
            decision.regime
        )

        assertEquals(
            CarbleMediumStage.M3,
            decision.mediumStage
        )
    }


    // =====================================================
    // LOW
    // =====================================================

    @Test
    fun confidence_below_045_is_low() {

        val decision =
            controller.decide(

                currentHopConfidence =
                    0.44,

                routeConfidence =
                    0.44
            )

        assertEquals(
            CarbleRegime.LOW,
            decision.regime
        )

        assertNull(
            decision.mediumStage
        )

        assertEquals(
            CarbleDecisionReason.LOCAL_LOW,
            decision.reason
        )
    }


    // =====================================================
    // IMPORTANT REGRESSION TEST
    // =====================================================

    @Test
    fun downstream_low_hop_does_not_force_current_hop_into_low() {

        /*
         * This protects us from the exact semantic bug that
         * originally broke 2RH seed 13.
         *
         * Current forwarding opportunity is healthy:
         *
         * Qcurrent = .90
         *
         * But a future route hop is severely degraded:
         *
         * Qroute = .30
         *
         * CARBLE may enter MEDIUM M1 as a warning,
         * but MUST NOT enter LOW at the healthy upstream
         * current hop.
         */
        val decision =
            controller.decide(

                currentHopConfidence =
                    0.90,

                routeConfidence =
                    0.30
            )

        assertEquals(
            CarbleRegime.MEDIUM,
            decision.regime
        )

        assertEquals(
            CarbleMediumStage.M1,
            decision.mediumStage
        )

        assertEquals(
            CarbleDecisionReason.DOWNSTREAM_WARNING,
            decision.reason
        )
    }
}