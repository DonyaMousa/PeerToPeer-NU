package com.example.peertopeer.routing.carble

import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import org.junit.Assert.assertEquals
import org.junit.Test

class CarbleRouteEvaluatorTest {


    @Test
    fun healthy_route_is_high() {

        val store =
            MultiMetricStateStore()

        val evaluator =
            CarbleRouteEvaluator(
                stateStore =
                    store
            )


        val result =
            evaluator.evaluate(
                listOf(
                    "A",
                    "B",
                    "C"
                )
            )


        assertEquals(
            CarbleRegime.HIGH,
            result.regime
        )

        assertEquals(
            CarbleDecisionReason.HEALTHY_ROUTE,
            result.reason
        )
    }


    @Test
    fun degraded_current_hop_enters_medium() {

        val store =
            MultiMetricStateStore()


        /*
         * Build a Q in the MEDIUM region.
         */
        store.update(

            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    0.40,

                observedDelay =
                    1.5,

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
        )


        val evaluator =
            CarbleRouteEvaluator(
                stateStore =
                    store
            )


        val result =
            evaluator.evaluate(
                listOf(
                    "A",
                    "B",
                    "C"
                )
            )


        assertEquals(
            CarbleRegime.MEDIUM,
            result.regime
        )

        assertEquals(
            CarbleDecisionReason.LOCAL_MEDIUM,
            result.reason
        )
    }


    @Test
    fun downstream_degradation_creates_warning_not_low() {

        val store =
            MultiMetricStateStore()


        /*
         * Current hop A->B remains healthy.
         */
        store.update(

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
        )


        /*
         * Downstream B->C is badly degraded.
         */
        store.update(

            MultiMetricLinkState(

                fromNodeId =
                    "B",

                toNodeId =
                    "C",

                successRate =
                    0.0,

                observedDelay =
                    2.0,

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
        )


        val evaluator =
            CarbleRouteEvaluator(
                stateStore =
                    store
            )


        val result =
            evaluator.evaluate(
                listOf(
                    "A",
                    "B",
                    "C"
                )
            )


        /*
         * Important:
         *
         * A->B is healthy.
         *
         * B->C may even be LOW.
         *
         * The packet must NOT enter LOW while still at A.
         */
        assertEquals(
            CarbleRegime.MEDIUM,
            result.regime
        )

        assertEquals(
            CarbleMediumStage.M1,
            result.mediumStage
        )

        assertEquals(
            CarbleDecisionReason.DOWNSTREAM_WARNING,
            result.reason
        )

        assertEquals(
            "B",
            result.bottleneckFromNodeId
        )

        assertEquals(
            "C",
            result.bottleneckToNodeId
        )
    }


    @Test
    fun current_low_hop_enters_low() {

        val store =
            MultiMetricStateStore()


        /*
         * With:
         *
         * D = 0
         * S = 0
         * R = 1
         * F = 1
         * B = 1
         * T ≈ .9
         *
         * Q ≈ .585
         *
         * That is MEDIUM, not LOW.
         *
         * Therefore also introduce severe instability,
         * delay, queue and resource pressure.
         */
        store.update(

            MultiMetricLinkState(

                fromNodeId =
                    "A",

                toNodeId =
                    "B",

                successRate =
                    0.0,

                observedDelay =
                    10.0,

                delayReference =
                    10.0,

                queueOccupancy =
                    10,

                queueCapacity =
                    10,

                recentLinkChanges =
                    5,

                instabilityReference =
                    5,

                energyPenaltyNormalized =
                    1.0
            )
        )


        val evaluator =
            CarbleRouteEvaluator(
                stateStore =
                    store
            )


        val result =
            evaluator.evaluate(
                listOf(
                    "A",
                    "B"
                )
            )


        assertEquals(
            CarbleRegime.LOW,
            result.regime
        )

        assertEquals(
            CarbleDecisionReason.LOCAL_LOW,
            result.reason
        )
    }


    @Test
    fun one_node_route_is_high() {

        val evaluator =
            CarbleRouteEvaluator(
                stateStore =
                    MultiMetricStateStore()
            )


        val result =
            evaluator.evaluate(
                listOf(
                    "A"
                )
            )


        assertEquals(
            CarbleRegime.HIGH,
            result.regime
        )

        assertEquals(
            1.0,
            result.currentHopConfidence,
            0.000001
        )

        assertEquals(
            1.0,
            result.routeConfidence,
            0.000001
        )
    }
}