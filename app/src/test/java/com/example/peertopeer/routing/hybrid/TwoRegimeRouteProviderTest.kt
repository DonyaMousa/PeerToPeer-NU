package com.example.peertopeer.routing.hybrid

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.TwoRegimeRouteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoRegimeRouteProviderTest {

    // =====================================================
    // HIGH -> FORWARD
    // =====================================================

    @Test
    fun healthy_route_produces_forward_decision() {

        val setup =
            createConnectedSetup()

        setup.stateStore.update(
            healthyState(
                from = "A",
                to = "B"
            )
        )

        val provider =
            createTwoRegimeProvider(
                graph =
                    setup.graph,

                stateStore =
                    setup.stateStore
            )

        val decision =
            provider.decide(
                currentNodeId =
                    "A",

                destinationId =
                    "B",

                messageId =
                    "MSG-1"
            )

        assertTrue(
            decision is
                    TwoRegimeRouteDecision.Forward
        )

        val forward =
            decision as
                    TwoRegimeRouteDecision.Forward

        assertEquals(
            listOf(
                "A",
                "B"
            ),
            forward.path
        )

        /*
         * Healthy state:
         *
         * D = 1.0
         * F = 1.0
         * R = 1.0
         *
         * observed delay = 1 / 10
         * delay suitability = 0.9
         *
         * queue suitability = 1.0
         *
         * T = (0.9 + 1.0) / 2 = 0.95
         *
         * S = 1.0
         * B = 1.0
         *
         * Q = 0.9925
         */
        assertEquals(
            0.9925,
            forward.confidence,
            0.000001
        )
    }

    // =====================================================
    // LOW -> CARRY
    // =====================================================

    @Test
    fun degraded_route_enters_low_and_carries() {

        val setup =
            createConnectedSetup()

        setup.stateStore.update(
            degradedState(
                from = "A",
                to = "B"
            )
        )

        val provider =
            createTwoRegimeProvider(
                graph =
                    setup.graph,

                stateStore =
                    setup.stateStore
            )

        val decision =
            provider.decide(
                currentNodeId =
                    "A",

                destinationId =
                    "B",

                messageId =
                    "MSG-LOW"
            )

        assertTrue(
            decision is
                    TwoRegimeRouteDecision.Carry
        )

        val carry =
            decision as
                    TwoRegimeRouteDecision.Carry

        assertEquals(
            1,
            carry.reevaluationNumber
        )

        assertEquals(
            5L,
            carry.reevaluationDelay
        )

        assertTrue(
            carry.confidence <
                    0.75
        )

        assertEquals(
            1,
            provider.getCompletedReevaluations(
                "MSG-LOW"
            )
        )
    }

    // =====================================================
    // LOW BUDGET IS BOUNDED
    // =====================================================

    @Test
    fun repeated_low_state_eventually_drops_packet() {

        val setup =
            createConnectedSetup()

        setup.stateStore.update(
            degradedState(
                from = "A",
                to = "B"
            )
        )

        val provider =
            createTwoRegimeProvider(
                graph =
                    setup.graph,

                stateStore =
                    setup.stateStore
            )

        val first =
            provider.decide(
                currentNodeId = "A",
                destinationId = "B",
                messageId = "MSG-BOUNDED"
            )

        val second =
            provider.decide(
                currentNodeId = "A",
                destinationId = "B",
                messageId = "MSG-BOUNDED"
            )

        val third =
            provider.decide(
                currentNodeId = "A",
                destinationId = "B",
                messageId = "MSG-BOUNDED"
            )

        val fourth =
            provider.decide(
                currentNodeId = "A",
                destinationId = "B",
                messageId = "MSG-BOUNDED"
            )

        assertTrue(
            first is
                    TwoRegimeRouteDecision.Carry
        )

        assertTrue(
            second is
                    TwoRegimeRouteDecision.Carry
        )

        assertTrue(
            third is
                    TwoRegimeRouteDecision.Carry
        )

        assertTrue(
            fourth is
                    TwoRegimeRouteDecision.Drop
        )

        val firstCarry =
            first as
                    TwoRegimeRouteDecision.Carry

        val secondCarry =
            second as
                    TwoRegimeRouteDecision.Carry

        val thirdCarry =
            third as
                    TwoRegimeRouteDecision.Carry

        assertEquals(
            1,
            firstCarry.reevaluationNumber
        )

        assertEquals(
            2,
            secondCarry.reevaluationNumber
        )

        assertEquals(
            3,
            thirdCarry.reevaluationNumber
        )

        /*
         * Packet-specific fallback state is removed after
         * the final DROP.
         */
        assertEquals(
            0,
            provider.getCompletedReevaluations(
                "MSG-BOUNDED"
            )
        )
    }

    // =====================================================
    // RECOVERY LOW -> HIGH
    // =====================================================

    @Test
    fun confidence_recovery_returns_to_high_and_resets_fallback_budget() {

        val setup =
            createConnectedSetup()

        setup.stateStore.update(
            degradedState(
                from = "A",
                to = "B"
            )
        )

        val provider =
            createTwoRegimeProvider(
                graph =
                    setup.graph,

                stateStore =
                    setup.stateStore
            )

        // -------------------------------------------------
        // First decision is LOW.
        // -------------------------------------------------

        val lowDecision =
            provider.decide(
                currentNodeId =
                    "A",

                destinationId =
                    "B",

                messageId =
                    "MSG-RECOVER"
            )

        assertTrue(
            lowDecision is
                    TwoRegimeRouteDecision.Carry
        )

        assertEquals(
            1,
            provider.getCompletedReevaluations(
                "MSG-RECOVER"
            )
        )

        // -------------------------------------------------
        // Network condition improves.
        // -------------------------------------------------

        setup.stateStore.update(
            healthyState(
                from = "A",
                to = "B"
            )
        )

        // -------------------------------------------------
        // Same packet is reevaluated.
        // -------------------------------------------------

        val recoveredDecision =
            provider.decide(
                currentNodeId =
                    "A",

                destinationId =
                    "B",

                messageId =
                    "MSG-RECOVER"
            )

        assertTrue(
            recoveredDecision is
                    TwoRegimeRouteDecision.Forward
        )

        /*
         * HIGH recovery clears the packet's previous LOW
         * carry history.
         */
        assertEquals(
            0,
            provider.getCompletedReevaluations(
                "MSG-RECOVER"
            )
        )
    }

    // =====================================================
    // NO DETERMINISTIC ROUTE -> BOUNDED CARRY
    // =====================================================

    @Test
    fun unavailable_route_enters_bounded_low_fallback() {

        val graph =
            Graph()

        graph.addNode(
            Node(
                nodeId = "A",
                displayName = "A"
            )
        )

        graph.addNode(
            Node(
                nodeId = "B",
                displayName = "B"
            )
        )

        /*
         * Intentionally no A-B edge.
         */

        val stateStore =
            MultiMetricStateStore()

        val provider =
            createTwoRegimeProvider(
                graph =
                    graph,

                stateStore =
                    stateStore
            )

        val decision =
            provider.decide(
                currentNodeId =
                    "A",

                destinationId =
                    "B",

                messageId =
                    "MSG-NO-ROUTE"
            )

        assertTrue(
            decision is
                    TwoRegimeRouteDecision.Carry
        )

        val carry =
            decision as
                    TwoRegimeRouteDecision.Carry

        /*
         * No deterministic route means there is no
         * measurable route confidence.
         *
         * TwoRegimeRouteProvider currently represents this
         * internally as 0.0 for Carry.
         */
        assertEquals(
            0.0,
            carry.confidence,
            0.000001
        )

        assertEquals(
            1,
            carry.reevaluationNumber
        )
    }

    // =====================================================
    // EXPLICIT CLEANUP
    // =====================================================

    @Test
    fun clear_packet_state_removes_fallback_history() {

        val setup =
            createConnectedSetup()

        setup.stateStore.update(
            degradedState(
                from = "A",
                to = "B"
            )
        )

        val provider =
            createTwoRegimeProvider(
                graph =
                    setup.graph,

                stateStore =
                    setup.stateStore
            )

        provider.decide(
            currentNodeId =
                "A",

            destinationId =
                "B",

            messageId =
                "MSG-CLEAR"
        )

        assertEquals(
            1,
            provider.getCompletedReevaluations(
                "MSG-CLEAR"
            )
        )

        provider.clearPacketState(
            "MSG-CLEAR"
        )

        assertEquals(
            0,
            provider.getCompletedReevaluations(
                "MSG-CLEAR"
            )
        )
    }

    // =====================================================
    // SETUP
    // =====================================================

    private data class TestSetup(

        val graph:
        Graph,

        val stateStore:
        MultiMetricStateStore
    )

    private fun createConnectedSetup():
            TestSetup {

        val graph =
            Graph()

        graph.addNode(
            Node(
                nodeId =
                    "A",

                displayName =
                    "A"
            )
        )

        graph.addNode(
            Node(
                nodeId =
                    "B",

                displayName =
                    "B"
            )
        )

        graph.addEdge(
            from =
                "A",

            to =
                "B",

            weight =
                1
        )

        return TestSetup(

            graph =
                graph,

            stateStore =
                MultiMetricStateStore()
        )
    }

    // =====================================================
    // 2RH PROVIDER
    // =====================================================

    private fun createTwoRegimeProvider(
        graph: Graph,
        stateStore:
        MultiMetricStateStore
    ): TwoRegimeRouteProvider {

        /*
         * 2RH HIGH must use the frozen MM-v1.0 routing
         * behavior.
         *
         * Therefore we explicitly preserve:
         *
         * hysteresis = 5%
         */
        val mmRouteProvider =
            MMRouteProvider(

                graph =
                    graph,

                stateStore =
                    stateStore,

                hysteresisFraction =
                    0.05
            )

        val routeEvaluator =
            TwoRegimeRouteEvaluator(

                stateStore =
                    stateStore
            )

        val fallbackPolicy =
            TwoRegimeFallbackPolicy(

                maxReevaluations =
                    3,

                reevaluationDelay =
                    5L
            )

        return TwoRegimeRouteProvider(

            mmRouteProvider =
                mmRouteProvider,

            routeEvaluator =
                routeEvaluator,

            fallbackPolicy =
                fallbackPolicy
        )
    }

    // =====================================================
    // LINK STATES
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

        /*
         * This deliberately produces Q < 0.75.
         *
         * D = 0.40
         * F = 1.00
         * R = 0.40
         *
         * delay suitability:
         * 1 - 8/10 = 0.20
         *
         * queue suitability:
         * 1 - 8/10 = 0.20
         *
         * T = 0.20
         *
         * S = 0.40
         * B = 0.70
         *
         * Q =
         * 0.30(0.40)
         * + 0.20(1.00)
         * + 0.15(0.40)
         * + 0.15(0.20)
         * + 0.10(0.40)
         * + 0.10(0.70)
         *
         * Q = 0.52
         */
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
}