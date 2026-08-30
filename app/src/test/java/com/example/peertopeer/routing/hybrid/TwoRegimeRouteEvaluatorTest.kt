package com.example.peertopeer.routing.hybrid

import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoRegimeRouteEvaluatorTest {

    @Test
    fun healthy_route_is_high() {

        val store =
            MultiMetricStateStore()

        store.update(
            healthyState(
                "A",
                "B"
            )
        )

        store.update(
            healthyState(
                "B",
                "C"
            )
        )

        val evaluator =
            TwoRegimeRouteEvaluator(
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
            TwoRegimeState.HIGH,
            result.state
        )

        assertEquals(
            0.9925,
            result.routeConfidence,
            0.000001
        )
    }

    @Test
    fun weak_hop_makes_whole_route_low() {

        val store =
            MultiMetricStateStore()

        store.update(
            healthyState(
                "A",
                "B"
            )
        )

        store.update(
            degradedState(
                "B",
                "C"
            )
        )

        val evaluator =
            TwoRegimeRouteEvaluator(
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
            TwoRegimeState.LOW,
            result.state
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
    fun route_confidence_uses_minimum_hop_confidence() {

        val store =
            MultiMetricStateStore()

        store.update(
            stateWithSuccessRate(
                from =
                    "A",
                to =
                    "B",
                successRate =
                    0.90
            )
        )

        store.update(
            stateWithSuccessRate(
                from =
                    "B",
                to =
                    "C",
                successRate =
                    0.60
            )
        )

        val adapter =
            TwoRegimeSignalAdapter()

        val controller =
            TwoRegimeController()

        val expectedAB =
            controller
                .calculateConfidence(
                    adapter.fromLinkState(
                        requireNotNull(
                            store.get(
                                "A",
                                "B"
                            )
                        )
                    )
                )

        val expectedBC =
            controller
                .calculateConfidence(
                    adapter.fromLinkState(
                        requireNotNull(
                            store.get(
                                "B",
                                "C"
                            )
                        )
                    )
                )

        val expectedMinimum =
            minOf(
                expectedAB,
                expectedBC
            )

        val evaluator =
            TwoRegimeRouteEvaluator(
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
            expectedMinimum,
            result.routeConfidence,
            0.000001
        )
    }

    @Test
    fun single_node_route_is_high_with_full_confidence() {

        val evaluator =
            TwoRegimeRouteEvaluator(
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
            TwoRegimeState.HIGH,
            result.state
        )

        assertEquals(
            1.0,
            result.routeConfidence,
            0.000001
        )
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private fun healthyState(
        from: String,
        to: String
    ): MultiMetricLinkState {

        return MultiMetricLinkState(

            fromNodeId =
                from,

            toNodeId =
                to,

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
    }

    private fun degradedState(
        from: String,
        to: String
    ): MultiMetricLinkState {

        return MultiMetricLinkState(

            fromNodeId =
                from,

            toNodeId =
                to,

            successRate =
                0.40,

            observedDelay =
                8.0,

            delayReference =
                10.0,

            queueOccupancy =
                8,

            queueCapacity =
                10,

            recentLinkChanges =
                3,

            instabilityReference =
                5,

            energyPenaltyNormalized =
                0.30
        )
    }

    private fun stateWithSuccessRate(
        from: String,
        to: String,
        successRate: Double
    ): MultiMetricLinkState {

        return MultiMetricLinkState(

            fromNodeId =
                from,

            toNodeId =
                to,

            successRate =
                successRate,

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
    }
}