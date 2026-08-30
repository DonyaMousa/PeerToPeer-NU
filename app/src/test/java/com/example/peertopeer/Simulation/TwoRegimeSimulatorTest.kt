package com.example.peertopeer.Simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import com.example.peertopeer.simulation.TwoRegimeRouteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoRegimeSimulatorTest {

    @Test
    fun low_carry_then_recovery_delivers_packet() {

        val simulationEngine =
            SimulationEngine()

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

        graph.addEdge(
            from = "A",
            to = "B",
            weight = 1
        )

        val stateStore =
            MultiMetricStateStore()

        /*
         * Start in degraded condition.
         *
         * This should force:
         *
         * Q < 0.75
         * -> LOW
         * -> Carry
         */
        stateStore.update(
            degradedState(
                from = "A",
                to = "B"
            )
        )

        val mmRouteProvider =
            MMRouteProvider(
                graph = graph,
                stateStore = stateStore,
                hysteresisFraction = 0.05
            )

        val routeEvaluator =
            TwoRegimeRouteEvaluator(
                stateStore = stateStore
            )

        val twoRegimeProvider =
            TwoRegimeRouteProvider(
                mmRouteProvider = mmRouteProvider,
                routeEvaluator = routeEvaluator,
                fallbackPolicy =
                    TwoRegimeFallbackPolicy(
                        maxReevaluations = 3,
                        reevaluationDelay = 5L
                    )
            )

        /*
         * Perfect physical link.
         *
         * We are testing regime/carry behavior here,
         * not transmission failure.
         */
        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = simulationEngine,
                maxAttempts = 1,
                delayPerAttempt = 1L,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            _,
                            _ ->

                        graph.containsEdge(
                            from,
                            to
                        )
                    }
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter
            )

        /*
         * Destination must exist as a simulated node
         * because forwarded packets are delivered into
         * TimedNetworkNode.
         */
        simulator.addNode(
            nodeId = "B",
            queueCapacity = 10,
            serviceTime = 1L
        )

        val packet =
            Packet(
                messageId = "MSG-2RH-RECOVERY",
                sourceId = "A",
                destinationId = "B",
                createdAt = 0L,
                ttl = 10,
                payload = "TEST"
            )

        simulator.send(
            packet = packet,
            routeProvider = twoRegimeProvider
        )

        /*
         * At t = 3 the network evidence improves.
         *
         * The packet entered LOW at t = 0 and is waiting
         * until its first reevaluation at t = 5.
         */
        simulationEngine.schedule(
            3L
        ) {

            stateStore.update(
                healthyState(
                    from = "A",
                    to = "B"
                )
            )
        }

        simulationEngine.run()

        val results =
            simulator.getResults()

        assertEquals(
            1,
            results.size
        )

        val result =
            results.single()

        assertTrue(
            result.delivered
        )

        assertFalse(
            result.dropped
        )

        assertEquals(
            null,
            result.dropReason
        )

        /*
         * Timeline:
         *
         * t=0  -> LOW
         * t=3  -> state recovers
         * t=5  -> reevaluate -> HIGH -> transmit
         * t=6  -> physical transmission completes
         * t=7  -> destination finishes service
         */
        assertEquals(
            7L,
            result.deliveredAt
        )

        /*
         * HIGH recovery must clear the packet-specific
         * fallback counter.
         */
        assertEquals(
            0,
            twoRegimeProvider
                .getCompletedReevaluations(
                    packet.messageId
                )
        )
    }

    @Test
    fun persistent_low_eventually_drops_packet() {

        val simulationEngine =
            SimulationEngine()

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

        graph.addEdge(
            from = "A",
            to = "B",
            weight = 1
        )

        val stateStore =
            MultiMetricStateStore()

        /*
         * Remain degraded for entire simulation.
         */
        stateStore.update(
            degradedState(
                from = "A",
                to = "B"
            )
        )

        val twoRegimeProvider =
            TwoRegimeRouteProvider(
                mmRouteProvider =
                    MMRouteProvider(
                        graph = graph,
                        stateStore = stateStore,
                        hysteresisFraction = 0.05
                    ),

                routeEvaluator =
                    TwoRegimeRouteEvaluator(
                        stateStore = stateStore
                    ),

                fallbackPolicy =
                    TwoRegimeFallbackPolicy(
                        maxReevaluations = 3,
                        reevaluationDelay = 5L
                    )
            )

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = simulationEngine,
                maxAttempts = 1,
                delayPerAttempt = 1L,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            _,
                            _ ->

                        graph.containsEdge(
                            from,
                            to
                        )
                    }
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter
            )

        simulator.addNode(
            nodeId = "B",
            queueCapacity = 10,
            serviceTime = 1L
        )

        val packet =
            Packet(
                messageId = "MSG-2RH-DROP",
                sourceId = "A",
                destinationId = "B",
                createdAt = 0L,
                ttl = 10,
                payload = "TEST"
            )

        simulator.send(
            packet = packet,
            routeProvider = twoRegimeProvider
        )

        simulationEngine.run()

        val results =
            simulator.getResults()

        assertEquals(
            1,
            results.size
        )

        val result =
            results.single()

        assertFalse(
            result.delivered
        )

        assertTrue(
            result.dropped
        )

        /*
         * Current common schema maps exhausted 2RH
         * fallback to NO_ROUTE.
         */
        assertTrue(
            result.dropReason != null
        )

        /*
         * Decisions:
         *
         * t=0  carry #1
         * t=5  carry #2
         * t=10 carry #3
         * t=15 DROP
         */
        assertEquals(
            15L,
            result.droppedAt
        )

        assertEquals(
            0,
            twoRegimeProvider
                .getCompletedReevaluations(
                    packet.messageId
                )
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
            fromNodeId = from,
            toNodeId = to,
            successRate = 1.0,
            observedDelay = 1.0,
            delayReference = 10.0,
            queueOccupancy = 0,
            queueCapacity = 10,
            recentLinkChanges = 0,
            instabilityReference = 5,
            energyPenaltyNormalized = 0.0
        )
    }

    private fun degradedState(
        from: String,
        to: String
    ): MultiMetricLinkState {

        return MultiMetricLinkState(
            fromNodeId = from,
            toNodeId = to,
            successRate = 0.40,
            observedDelay = 8.0,
            delayReference = 10.0,
            queueOccupancy = 8,
            queueCapacity = 10,
            recentLinkChanges = 3,
            instabilityReference = 5,
            energyPenaltyNormalized = 0.30
        )
    }
}
