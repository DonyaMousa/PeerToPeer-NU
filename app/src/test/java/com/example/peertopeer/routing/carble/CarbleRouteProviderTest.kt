package com.example.peertopeer.routing.carble

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.CarbleRouteProvider
import com.example.peertopeer.simulation.MMRouteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbleRouteProviderTest {


    // =====================================================
    // HIGH
    // =====================================================

    @Test
    fun healthy_route_returns_high_forward() {

        val context =
            createContext(
                createLineGraph()
            )


        val decision =
            context.provider.decide(

                currentNodeId =
                    "N0",

                destinationId =
                    "N2",

                messageId =
                    "MSG-HIGH"
            )


        assertTrue(
            decision is
                    CarbleRouteDecision.Forward
        )


        val forward =
            decision as
                    CarbleRouteDecision.Forward


        assertEquals(
            CarbleRegime.HIGH,
            forward.regime
        )


        assertEquals(
            "N1",
            forward.path[1]
        )
    }


    // =====================================================
    // M1
    // =====================================================

    @Test
    fun local_m1_returns_normal_forward() {

        val graph =
            createLineGraph()


        val context =
            createContext(
                graph
            )


        /*
         * Gives Q just below .75 with otherwise healthy
         * simulation signals.
         */
        putState(

            context.stateStore,

            from =
                "N0",

            to =
                "N1",

            successRate =
                0.40,

            observedDelay =
                1.5
        )


        val decision =
            context.provider.decide(

                currentNodeId =
                    "N0",

                destinationId =
                    "N2",

                messageId =
                    "MSG-M1"
            )


        assertTrue(
            decision is
                    CarbleRouteDecision.Forward
        )


        val forward =
            decision as
                    CarbleRouteDecision.Forward


        assertEquals(
            CarbleRegime.MEDIUM,
            forward.regime
        )


        assertEquals(
            CarbleMediumStage.M1,
            forward.mediumStage
        )
    }


    // =====================================================
    // M2
    // =====================================================

    @Test
    fun m2_prepares_distinct_backup() {

        val graph =
            createDualPathGraph()


        val context =
            createContext(
                graph
            )


        /*
         * Degrade both immediate alternatives into M2 so
         * MM's primary remains deterministic and CARBLE
         * can still select the other branch.
         */
        putState(

            context.stateStore,

            from =
                "N1",

            to =
                "N2",

            successRate =
                0.10,

            observedDelay =
                2.0
        )


        putState(

            context.stateStore,

            from =
                "N1",

            to =
                "N3",

            successRate =
                0.10,

            observedDelay =
                2.0
        )


        val decision =
            context.provider.decide(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4",

                messageId =
                    "MSG-M2"
            )


        assertTrue(
            decision is
                    CarbleRouteDecision
                    .ForwardWithFailover
        )


        val m2 =
            decision as
                    CarbleRouteDecision
                    .ForwardWithFailover


        assertNotNull(
            m2.backupPath
        )


        assertNotEquals(
            m2.primaryPath[1],
            m2.backupPath!![1]
        )
    }


    // =====================================================
    // LOW
    // =====================================================

    @Test
    fun low_initial_decision_returns_carry() {

        val context =
            createContext(
                createLineGraph()
            )


        putSeverelyDegradedState(

            context.stateStore,

            from =
                "N0",

            to =
                "N1"
        )


        val decision =
            context.provider.decide(

                currentNodeId =
                    "N0",

                destinationId =
                    "N2",

                messageId =
                    "MSG-LOW"
            )


        assertTrue(
            decision is
                    CarbleRouteDecision.Carry
        )


        val carry =
            decision as
                    CarbleRouteDecision.Carry


        assertEquals(
            1,
            carry.reevaluationNumber
        )


        assertEquals(
            5L,
            carry.reevaluationDelay
        )
    }


    @Test
    fun low_after_carry_returns_probe_when_still_low() {

        val context =
            createContext(
                createLineGraph()
            )


        putSeverelyDegradedState(

            context.stateStore,

            from =
                "N0",

            to =
                "N1"
        )


        context.provider.decide(

            currentNodeId =
                "N0",

            destinationId =
                "N2",

            messageId =
                "MSG-PROBE"
        )


        val second =
            context.provider
                .decideAfterCarry(

                    currentNodeId =
                        "N0",

                    destinationId =
                        "N2",

                    messageId =
                        "MSG-PROBE"
                )


        assertTrue(
            second is
                    CarbleRouteDecision.Probe
        )


        val probe =
            second as
                    CarbleRouteDecision.Probe


        assertEquals(
            "N1",
            probe.path[1]
        )
    }


    // =====================================================
    // RECOVERY
    // =====================================================

    @Test
    fun medium_to_high_transition_is_recorded() {

        val context =
            createContext(
                createLineGraph()
            )


        putState(

            context.stateStore,

            from =
                "N0",

            to =
                "N1",

            successRate =
                0.40,

            observedDelay =
                1.5
        )


        context.provider.decide(

            currentNodeId =
                "N0",

            destinationId =
                "N2",

            messageId =
                "MSG-RECOVERY"
        )


        /*
         * Fresh evidence restores healthy state.
         */
        putState(

            context.stateStore,

            from =
                "N0",

            to =
                "N1",

            successRate =
                1.0,

            observedDelay =
                1.0
        )


        context.provider.decide(

            currentNodeId =
                "N0",

            destinationId =
                "N2",

            messageId =
                "MSG-RECOVERY"
        )


        assertEquals(
            1L,
            context.provider
                .adaptationTelemetry
                .mediumToHighRecoveries
        )
    }


    // =====================================================
    // COPY BUDGET
    // =====================================================

    @Test
    fun activating_backup_consumes_single_copy_budget() {

        val context =
            createContext(
                createDualPathGraph()
            )

        /*
         * Force the current N1 forwarding opportunity
         * into MEDIUM M2 so CARBLE genuinely prepares
         * an alternate next hop.
         */
        putState(
            store =
                context.stateStore,

            from =
                "N1",

            to =
                "N2",

            successRate =
                0.10,

            observedDelay =
                2.0
        )

        putState(
            store =
                context.stateStore,

            from =
                "N1",

            to =
                "N3",

            successRate =
                0.10,

            observedDelay =
                2.0
        )


        val decision =
            context.provider.decide(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4",

                messageId =
                    "MSG-BUDGET"
            )


        /*
         * Confirm this really became an M2 decision
         * with a prepared backup.
         */
        assertTrue(
            decision is
                    CarbleRouteDecision
                    .ForwardWithFailover
        )

        val m2Decision =
            decision as
                    CarbleRouteDecision
                    .ForwardWithFailover

        assertNotNull(
            m2Decision.backupPath
        )


        /*
         * The backup is now ACTUALLY launched.
         *
         * This is the moment CARBLE consumes its
         * one-copy MEDIUM budget.
         */
        context.provider
            .recordBackupActivation(
                "MSG-BUDGET"
            )


        val state =
            context.provider
                .getPacketState(
                    "MSG-BUDGET"
                )


        assertEquals(
            0,
            state?.copyBudgetRemaining
        )

        assertEquals(
            true,
            state?.backupUsed
        )
    }


    // =====================================================
    // M3 WINNER
    // =====================================================

    @Test
    fun only_first_branch_can_claim_forwarding_winner() {

        val context =
            createContext(
                createDualPathGraph()
            )


        val first =
            context.provider
                .tryClaimForwardingWinner(

                    messageId =
                        "MSG-WINNER",

                    nextHopId =
                        "N2",

                    isBackup =
                        false
                )


        val second =
            context.provider
                .tryClaimForwardingWinner(

                    messageId =
                        "MSG-WINNER",

                    nextHopId =
                        "N3",

                    isBackup =
                        true
                )


        assertTrue(
            first
        )


        assertEquals(
            false,
            second
        )


        assertEquals(
            1L,
            context.provider
                .adaptationTelemetry
                .duplicateSuppressions
        )
    }


    // =====================================================
    // HELPERS
    // =====================================================

    private data class TestContext(

        val stateStore:
        MultiMetricStateStore,

        val provider:
        CarbleRouteProvider
    )


    private fun createContext(
        graph: Graph
    ): TestContext {

        val stateStore =
            MultiMetricStateStore()


        val mmRouteProvider =
            MMRouteProvider(

                graph =
                    graph,

                stateStore =
                    stateStore,

                hysteresisFraction =
                    0.05
            )


        val evaluator =
            CarbleRouteEvaluator(

                stateStore =
                    stateStore
            )


        val candidateFactory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    stateStore
            )


        val provider =
            CarbleRouteProvider(

                mmRouteProvider =
                    mmRouteProvider,

                routeEvaluator =
                    evaluator,

                candidateFactory =
                    candidateFactory,

                backupSelector =
                    CarbleBackupSelector(),

                fallbackPolicy =
                    TwoRegimeFallbackPolicy(

                        maxReevaluations =
                            3,

                        reevaluationDelay =
                            5L
                    ),

                retryDelay =
                    1L
            )


        return TestContext(

            stateStore =
                stateStore,

            provider =
                provider
        )
    }


    private fun createLineGraph():
            Graph {

        val graph =
            Graph()


        repeat(
            3
        ) { index ->

            graph.addNode(

                Node(

                    nodeId =
                        "N$index",

                    displayName =
                        "N$index"
                )
            )
        }


        graph.addEdge(
            "N0",
            "N1",
            1
        )

        graph.addEdge(
            "N1",
            "N2",
            1
        )


        return graph
    }


    private fun createDualPathGraph():
            Graph {

        val graph =
            Graph()


        repeat(
            5
        ) { index ->

            graph.addNode(

                Node(

                    nodeId =
                        "N$index",

                    displayName =
                        "N$index"
                )
            )
        }


        graph.addEdge(
            "N0",
            "N1",
            1
        )

        graph.addEdge(
            "N1",
            "N2",
            1
        )

        graph.addEdge(
            "N2",
            "N4",
            1
        )

        graph.addEdge(
            "N1",
            "N3",
            1
        )

        graph.addEdge(
            "N3",
            "N4",
            1
        )


        return graph
    }


    private fun putState(
        store:
        MultiMetricStateStore,
        from: String,
        to: String,
        successRate: Double,
        observedDelay: Double
    ) {

        store.update(

            MultiMetricLinkState(

                fromNodeId =
                    from,

                toNodeId =
                    to,

                successRate =
                    successRate,

                observedDelay =
                    observedDelay,

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
    }


    private fun putSeverelyDegradedState(
        store:
        MultiMetricStateStore,
        from: String,
        to: String
    ) {

        store.update(

            MultiMetricLinkState(

                fromNodeId =
                    from,

                toNodeId =
                    to,

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
    }
}