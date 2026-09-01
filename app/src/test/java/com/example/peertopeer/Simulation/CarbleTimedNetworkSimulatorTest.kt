package com.example.peertopeer.Simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricObservationTracker
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.CarbleRouteProvider
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import com.example.peertopeer.simulation.experiment.instrumentation.MMInstrumentation
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbleTimedNetworkSimulatorTest {

    companion object {

        private const val RUN_ID =
            "CARBLE-INTEGRATION-TEST"

        private const val QUEUE_CAPACITY =
            10
    }


    // =====================================================
    // HIGH
    // =====================================================

    @Test
    fun high_delivers_normally_end_to_end() {

        val context =
            createContext(
                graph =
                    createLineGraph()
            )


        val simulator =
            createSimulator(
                context =
                    context,

                maxAttempts =
                    1,

                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            _,
                            _ ->

                        context.graph
                            .containsEdge(
                                from,
                                to
                            )
                    }
            )


        simulator.send(

            packet =
                packet(
                    messageId =
                        "HIGH"
                ),

            routeProvider =
                context.provider
        )


        context.engine.run()


        val result =
            simulator
                .getResults()
                .single()


        assertTrue(
            result.delivered
        )


        assertEquals(
            2,
            context.recorder
                .getTransmissionRecords()
                .size
        )


        assertEquals(
            2L,
            context.provider
                .adaptationTelemetry
                .highDecisions
        )


        assertEquals(
            0L,
            context.provider
                .adaptationTelemetry
                .mediumDecisions
        )
    }


    // =====================================================
    // M1
    // =====================================================

    @Test
    fun m1_uses_normal_primary_forwarding() {

        val graph =
            Graph()


        addNode(
            graph,
            "N0"
        )

        addNode(
            graph,
            "N1"
        )


        graph.addEdge(
            "N0",
            "N1",
            1
        )


        val context =
            createContext(
                graph
            )


        /*
         * Q ≈ .749
         *
         * Therefore:
         *
         * MEDIUM M1
         */
        putState(

            store =
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


        val simulator =
            createSimulator(

                context =
                    context,

                maxAttempts =
                    1,

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


        simulator.send(

            packet =
                Packet(

                    messageId =
                        "M1",

                    sourceId =
                        "N0",

                    destinationId =
                        "N1",

                    createdAt =
                        0L,

                    ttl =
                        10,

                    payload =
                        "X"
                ),

            routeProvider =
                context.provider
        )


        context.engine.run()


        assertTrue(
            simulator
                .getResults()
                .single()
                .delivered
        )


        assertEquals(
            1L,
            context.provider
                .adaptationTelemetry
                .m1Decisions
        )


        assertEquals(
            0L,
            context.provider
                .adaptationTelemetry
                .backupActivations
        )
    }


    // =====================================================
    // M2
    // =====================================================

    @Test
    fun m2_primary_failure_activates_sequential_backup() {

        val context =
            createContext(
                graph =
                    createDiamondGraph()
            )


        /*
         * Both immediate forwarding candidates are M2.
         *
         * MM deterministically chooses one primary and
         * CARBLE prepares the other as backup.
         */
        putM2State(
            context.stateStore,
            "N0",
            "N1"
        )

        putM2State(
            context.stateStore,
            "N0",
            "N2"
        )


        val simulator =
            createSimulator(

                context =
                    context,

                maxAttempts =
                    1,

                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            _,
                            _ ->

                        when {

                            /*
                             * Expected deterministic MM
                             * primary.
                             */
                            from == "N0" &&
                                    to == "N1" -> {

                                false
                            }

                            else -> {

                                context.graph
                                    .containsEdge(
                                        from,
                                        to
                                    )
                            }
                        }
                    }
            )


        simulator.send(

            packet =
                diamondPacket(
                    "M2"
                ),

            routeProvider =
                context.provider
        )


        context.engine.run()


        val result =
            simulator
                .getResults()
                .single()


        assertTrue(
            result.delivered
        )


        val adaptation =
            context.provider
                .adaptationTelemetry


        assertTrue(
            adaptation.m2Decisions >=
                    1L
        )

        assertEquals(
            1L,
            adaptation.backupActivations
        )

        assertEquals(
            1L,
            adaptation.backupSuccesses
        )

        assertEquals(
            0L,
            adaptation.fallbackDrops
        )


        val transmissions =
            context.recorder
                .getTransmissionRecords()


        assertTrue(
            transmissions.any {

                it.fromNodeId ==
                        "N0" &&
                        it.toNodeId ==
                        "N1" &&
                        !it.success
            }
        )


        assertTrue(
            transmissions.any {

                it.fromNodeId ==
                        "N0" &&
                        it.toNodeId ==
                        "N2" &&
                        it.success
            }
        )
    }


    // =====================================================
    // M3
    // =====================================================

    @Test
    fun m3_delayed_backup_allows_only_one_branch_to_continue() {

        val context =
            createContext(
                graph =
                    createDiamondGraph()
            )


        putM3State(
            context.stateStore,
            "N0",
            "N1"
        )

        putM3State(
            context.stateStore,
            "N0",
            "N2"
        )


        val simulator =
            createSimulator(

                context =
                    context,

                maxAttempts =
                    3,

                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            attemptNumber,
                            _ ->

                        when {

                            /*
                             * Primary remains unresolved long
                             * enough for the delayed backup
                             * to activate.
                             */
                            from == "N0" &&
                                    to == "N1" -> {

                                attemptNumber >=
                                        3
                            }


                            /*
                             * Backup succeeds immediately.
                             */
                            from == "N0" &&
                                    to == "N2" -> {

                                true
                            }


                            else -> {

                                context.graph
                                    .containsEdge(
                                        from,
                                        to
                                    )
                            }
                        }
                    }
            )


        simulator.send(

            packet =
                diamondPacket(
                    "M3"
                ),

            routeProvider =
                context.provider
        )


        context.engine.run()


        /*
         * No matter which physical branch wins the timing
         * race, the payload must terminate exactly once.
         */
        assertEquals(
            1,
            simulator
                .getResults()
                .size
        )


        assertTrue(
            simulator
                .getResults()
                .single()
                .delivered
        )


        val adaptation =
            context.provider
                .adaptationTelemetry


        assertTrue(
            adaptation.m3Decisions >=
                    1L
        )


        assertEquals(
            1L,
            adaptation.backupActivations
        )


        /*
         * Both physical opportunities were permitted to
         * exist, but exactly one continuation survives.
         *
         * A late successful branch should therefore be
         * suppressed.
         */
        assertTrue(
            adaptation.duplicateSuppressions >=
                    1L
        )


        /*
         * The radio/resource evidence must retain both
         * successful physical opportunities.
         */
        val successfulSourceBranches =
            context.recorder
                .getTransmissionRecords()
                .count {

                    it.fromNodeId ==
                            "N0" &&
                            it.success
                }


        assertEquals(
            2,
            successfulSourceBranches
        )

        // =====================================================
// M3 TRANSMISSION ACCOUNTING AUDIT
// =====================================================

        /*
         * CARBLE M3 creates two distinct physical forwarding
         * opportunities:
         *
         * primary N0 -> N1
         * backup  N0 -> N2
         *
         * They MUST be represented as distinct logical-hop
         * groups. Otherwise the frozen experiment accounting
         * would mix their attempt numbers.
         */
        val sourceAttempts =
            context.recorder
                .getTransmissionRecords()
                .filter {
                    it.fromNodeId ==
                            "N0"
                }


        /*
         * Every CARBLE transmission used by the research runner
         * must carry a logicalHopIndex.
         */
        assertTrue(
            sourceAttempts.all {
                it.logicalHopIndex != null
            }
        )


        val logicalGroups =
            sourceAttempts
                .groupBy {
                    it.logicalHopIndex
                }


        /*
         * M3 launched:
         *
         * primary
         * +
         * one delayed backup
         *
         * Therefore these must be TWO logical forwarding
         * opportunities, even though only one packet branch is
         * allowed to continue.
         */
        assertEquals(
            2,
            logicalGroups.size
        )


        /*
         * Verify that the two different next hops did not get
         * merged into the same logical-hop group.
         */
        val primaryGroup =
            sourceAttempts
                .first {
                    it.toNodeId ==
                            "N1"
                }
                .logicalHopIndex


        val backupGroup =
            sourceAttempts
                .first {
                    it.toNodeId ==
                            "N2"
                }
                .logicalHopIndex


        assertTrue(
            primaryGroup !=
                    backupGroup
        )


        /*
         * Preserve the exact accounting rule already used by
         * MM/2RH:
         *
         * attempt numbers within EACH logical forwarding
         * opportunity must be:
         *
         * 1
         *
         * or
         *
         * 1, 2
         *
         * or
         *
         * 1, 2, 3
         *
         * etc.
         */
        logicalGroups.values
            .forEach { attempts ->

                val actualAttemptNumbers =
                    attempts
                        .map {
                            it.attemptNumber
                        }
                        .sorted()


                val expectedAttemptNumbers =
                    (
                            1..
                                    attempts.size
                            )
                        .toList()


                assertEquals(
                    expectedAttemptNumbers,
                    actualAttemptNumbers
                )
            }
    }


    // =====================================================
    // LOW
    // =====================================================

    @Test
    fun low_carries_then_successfully_probes() {

        val graph =
            Graph()


        addNode(
            graph,
            "N0"
        )

        addNode(
            graph,
            "N1"
        )


        graph.addEdge(
            "N0",
            "N1",
            1
        )


        val context =
            createContext(
                graph
            )


        putLowState(
            context.stateStore,
            "N0",
            "N1"
        )


        val simulator =
            createSimulator(

                context =
                    context,

                maxAttempts =
                    1,

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


        simulator.send(

            packet =
                Packet(

                    messageId =
                        "LOW-PROBE",

                    sourceId =
                        "N0",

                    destinationId =
                        "N1",

                    createdAt =
                        0L,

                    ttl =
                        10,

                    payload =
                        "X"
                ),

            routeProvider =
                context.provider
        )


        context.engine.run()


        assertTrue(
            simulator
                .getResults()
                .single()
                .delivered
        )


        val adaptation =
            context.provider
                .adaptationTelemetry


        assertEquals(
            1L,
            adaptation.carryDecisions
        )

        assertEquals(
            1L,
            adaptation.probeDecisions
        )

        assertEquals(
            1L,
            adaptation.probeSuccesses
        )


        /*
         * Physical probe success is deliberately NOT
         * counted as confidence recovery.
         */
        assertEquals(
            0L,
            adaptation.lowToHighRecoveries
        )
    }


    // =====================================================
    // BOUNDED FAILURE
    // =====================================================

    @Test
    fun persistent_failure_cannot_cycle_forever() {

        val graph =
            Graph()


        addNode(
            graph,
            "N0"
        )

        addNode(
            graph,
            "N1"
        )


        graph.addEdge(
            "N0",
            "N1",
            1
        )


        val context =
            createContext(
                graph
            )


        putLowState(
            context.stateStore,
            "N0",
            "N1"
        )


        val simulator =
            createSimulator(

                context =
                    context,

                maxAttempts =
                    1,

                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            _,
                            _,
                            _,
                            _,
                            _ ->

                        false
                    }
            )


        simulator.send(

            packet =
                Packet(

                    messageId =
                        "BOUNDED",

                    sourceId =
                        "N0",

                    destinationId =
                        "N1",

                    createdAt =
                        0L,

                    ttl =
                        10,

                    payload =
                        "X"
                ),

            routeProvider =
                context.provider
        )


        context.engine.run()


        /*
         * If LOW->MEDIUM incorrectly reset the fallback
         * budget, this test could keep generating events
         * forever.
         *
         * Instead the packet must terminate.
         */
        assertEquals(
            1,
            simulator
                .getResults()
                .size
        )


        val result =
            simulator
                .getResults()
                .single()


        assertTrue(
            result.dropped
        )


        assertEquals(
            PacketDropReason.NO_ROUTE,
            result.dropReason
        )


        assertEquals(
            1L,
            context.provider
                .adaptationTelemetry
                .fallbackDrops
        )
    }


    // =====================================================
    // CONTEXT
    // =====================================================

    private data class TestContext(

        val engine:
        SimulationEngine,

        val graph:
        Graph,

        val stateStore:
        MultiMetricStateStore,

        val recorder:
        ExperimentRecorder,

        val instrumentation:
        MMInstrumentation,

        val provider:
        CarbleRouteProvider
    )


    private fun createContext(
        graph: Graph
    ): TestContext {

        val engine =
            SimulationEngine()


        val recorder =
            ExperimentRecorder(
                RUN_ID
            )


        val stateStore =
            MultiMetricStateStore()


        val observationTracker =
            MultiMetricObservationTracker(

                stateStore =
                    stateStore,

                reliabilityWindowSize =
                    20,

                delayWindowSize =
                    20,

                delayReference =
                    10.0,

                instabilityReference =
                    5
            )


        /*
         * Same directed edge initialization used by the
         * frozen MM and 2RH experiment runners.
         */
        graph.getEdges()
            .forEach { edge ->

                observationTracker
                    .registerEdge(

                        fromNodeId =
                            edge.from,

                        toNodeId =
                            edge.to,

                        queueCapacity =
                            QUEUE_CAPACITY
                    )


                observationTracker
                    .registerEdge(

                        fromNodeId =
                            edge.to,

                        toNodeId =
                            edge.from,

                        queueCapacity =
                            QUEUE_CAPACITY
                    )
            }


        val instrumentation =
            MMInstrumentation(

                delegate =
                    RecorderInstrumentation(
                        recorder
                    ),

                observationTracker =
                    observationTracker,

                queueCapacityByNode =
                    graph.getNodes()
                        .associate {
                            it.nodeId to
                                    QUEUE_CAPACITY
                        },

                retryDelay =
                    1L
            )


        val mmProvider =
            MMRouteProvider(

                graph =
                    graph,

                stateStore =
                    stateStore,

                runId =
                    RUN_ID,

                instrumentation =
                    instrumentation,

                timeProvider = {
                    engine.currentTime
                },

                hysteresisFraction =
                    0.05
            )


        val provider =
            CarbleRouteProvider(

                mmRouteProvider =
                    mmProvider,

                routeEvaluator =
                    CarbleRouteEvaluator(
                        stateStore
                    ),

                candidateFactory =
                    CarbleBackupCandidateFactory(

                        graph =
                            graph,

                        stateStore =
                            stateStore
                    ),

                backupSelector =
                    CarbleBackupSelector(),

                fallbackPolicy =
                    TwoRegimeFallbackPolicy(

                        maxReevaluations =
                            3,

                        reevaluationDelay =
                            1L
                    ),

                retryDelay =
                    1L
            )


        return TestContext(

            engine =
                engine,

            graph =
                graph,

            stateStore =
                stateStore,

            recorder =
                recorder,

            instrumentation =
                instrumentation,

            provider =
                provider
        )
    }


    private fun createSimulator(
        context: TestContext,
        maxAttempts: Int,
        attemptPolicy:
        TimedLinkAttemptPolicy
    ): TimedNetworkSimulator {

        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    context.engine,

                maxAttempts =
                    maxAttempts,

                delayPerAttempt =
                    1L,

                attemptPolicy =
                    attemptPolicy,

                runId =
                    RUN_ID,

                instrumentation =
                    context.instrumentation
            )


        val simulator =
            TimedNetworkSimulator(

                simulationEngine =
                    context.engine,

                eventDrivenLinkTransmitter =
                    transmitter,

                runId =
                    RUN_ID,

                instrumentation =
                    context.instrumentation
            )


        /*
         * N0 is the generated packet source.
         */
        context.graph
            .getNodes()
            .filter {
                it.nodeId !=
                        "N0"
            }
            .forEach { node ->

                simulator.addNode(

                    nodeId =
                        node.nodeId,

                    queueCapacity =
                        QUEUE_CAPACITY,

                    serviceTime =
                        1L
                )
            }


        return simulator
    }


    // =====================================================
    // GRAPH HELPERS
    // =====================================================

    private fun createLineGraph():
            Graph {

        val graph =
            Graph()


        addNode(
            graph,
            "N0"
        )

        addNode(
            graph,
            "N1"
        )

        addNode(
            graph,
            "N2"
        )


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


    private fun createDiamondGraph():
            Graph {

        /*
         *       N1
         *      /  \
         *    N0    N3
         *      \  /
         *       N2
         */

        val graph =
            Graph()


        repeat(
            4
        ) { index ->

            addNode(
                graph,
                "N$index"
            )
        }


        graph.addEdge(
            "N0",
            "N1",
            1
        )

        graph.addEdge(
            "N1",
            "N3",
            1
        )

        graph.addEdge(
            "N0",
            "N2",
            1
        )

        graph.addEdge(
            "N2",
            "N3",
            1
        )


        return graph
    }


    private fun addNode(
        graph: Graph,
        nodeId: String
    ) {

        graph.addNode(

            Node(

                nodeId =
                    nodeId,

                displayName =
                    nodeId
            )
        )
    }


    // =====================================================
    // PACKETS
    // =====================================================

    private fun packet(
        messageId: String
    ): Packet {

        return Packet(

            messageId =
                messageId,

            sourceId =
                "N0",

            destinationId =
                "N2",

            createdAt =
                0L,

            ttl =
                10,

            payload =
                "X"
        )
    }


    private fun diamondPacket(
        messageId: String
    ): Packet {

        return Packet(

            messageId =
                messageId,

            sourceId =
                "N0",

            destinationId =
                "N3",

            createdAt =
                0L,

            ttl =
                10,

            payload =
                "X"
        )
    }


    // =====================================================
    // STATE HELPERS
    // =====================================================

    private fun putState(
        store:
        MultiMetricStateStore,
        from: String,
        to: String,
        successRate: Double,
        observedDelay: Double,
        queueOccupancy: Int = 0,
        recentLinkChanges: Int = 0,
        energyPenalty: Double = 0.0
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
                    queueOccupancy,

                queueCapacity =
                    QUEUE_CAPACITY,

                recentLinkChanges =
                    recentLinkChanges,

                instabilityReference =
                    5,

                energyPenaltyNormalized =
                    energyPenalty
            )
        )
    }


    private fun putM2State(
        store: MultiMetricStateStore,
        from: String,
        to: String
    ) {

        /*
         * Q ≈ .625
         */
        putState(

            store =
                store,

            from =
                from,

            to =
                to,

            successRate =
                0.10,

            observedDelay =
                2.0
        )
    }


    private fun putM3State(
        store: MultiMetricStateStore,
        from: String,
        to: String
    ) {

        /*
         * Approx:
         *
         * D = .10
         * F = 1
         * R = .60
         * T = .25
         * S = .10
         * B = 1
         *
         * Q ≈ .468
         *
         * Therefore M3.
         */
        putState(

            store =
                store,

            from =
                from,

            to =
                to,

            successRate =
                0.10,

            observedDelay =
                10.0,

            queueOccupancy =
                5,

            recentLinkChanges =
                2
        )
    }


    private fun putLowState(
        store: MultiMetricStateStore,
        from: String,
        to: String
    ) {

        putState(

            store =
                store,

            from =
                from,

            to =
                to,

            successRate =
                0.0,

            observedDelay =
                10.0,

            queueOccupancy =
                10,

            recentLinkChanges =
                5,

            energyPenalty =
                1.0
        )
    }
}