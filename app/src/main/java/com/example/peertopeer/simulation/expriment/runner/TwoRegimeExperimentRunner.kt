package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeTelemetrySnapshot
import com.example.peertopeer.routing.mm.MultiMetricObservationTracker
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import com.example.peertopeer.simulation.TwoRegimeRouteProvider
import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.instrumentation.MMInstrumentation
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventType
import com.example.peertopeer.simulation.experiment.record.ResourceSampleRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventType
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import com.example.peertopeer.simulation.experiment.result.ExperimentAggregator
import com.example.peertopeer.simulation.experiment.result.RunSummary
import kotlin.random.Random

class TwoRegimeExperimentRunner(

    private val hysteresisFraction:
    Double = 0.05,

    private val maxFallbackReevaluations:
    Int = 3,

    private val fallbackReevaluationDelay:
    Long = 5L,

    /*
     * Diagnostic only.
     *
     * null in all normal experiments.
     */
    private val routeTraceObserver:
    ((TwoRegimeRouteEvaluator.HopEvaluationTrace) -> Unit)? =
        null

) {

    init {

        require(
            hysteresisFraction in 0.0..1.0
        ) {
            "hysteresisFraction must be between 0.0 and 1.0."
        }

        require(
            maxFallbackReevaluations > 0
        ) {
            "maxFallbackReevaluations must be greater than 0."
        }

        require(
            fallbackReevaluationDelay > 0L
        ) {
            "fallbackReevaluationDelay must be greater than 0."
        }
    }

    data class RunOutput(

        val summary:
        RunSummary,

        val packets:
        List<PacketRecord>,

        val transmissions:
        List<TransmissionRecord>,

        val routingEvents:
        List<RoutingEventRecord>,

        val topologyEvents:
        List<TopologyEventRecord>,

        val queueEvents:
        List<QueueEventRecord>,

        val resourceSamples:
        List<ResourceSampleRecord>,

        /*
         * 2RH-specific adaptation evidence.
         *
         * Kept separate from the common RunSummary so the
         * frozen B0/MM schema is not changed.
         */
        val adaptation:
        TwoRegimeTelemetrySnapshot
    )

    /*
     * -----------------------------------------------------
     * MM observation parameters inherited by 2RH HIGH
     * -----------------------------------------------------
     *
     * These remain identical to frozen MM-v1.0.
     */
    private val reliabilityWindowSize =
        20

    private val delayWindowSize =
        20

    private val delayReference =
        10.0

    private val instabilityReference =
        5


    // =====================================================
    // S01 — RELIABILITY ONLY
    // =====================================================

    fun runSeededRetryScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireTwoRegimeProtocol(
            config
        )

        require(
            config.scenario.nodeCount >= 2
        ) {
            "2RH S01 requires at least 2 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "2RH S01 requires link.successProbability."
            }

        require(
            successProbability in 0.0..1.0
        )

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId =
                    config.runId
            )

        val graph =
            createLineGraph(
                config.scenario.nodeCount
            )

        val context =
            createTwoRegimeContext(
                config =
                    config,

                graph =
                    graph,

                simulationEngine =
                    simulationEngine,

                recorder =
                    recorder
            )

        /*
         * EXACT SAME S01 stochastic stream as B0/MM.
         */
        val random =
            Random(
                config.seed
            )

        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    simulationEngine,

                maxAttempts =
                    config.link.maxAttempts,

                delayPerAttempt =
                    config.link.retryDelay,

                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            fromNodeId,
                            toNodeId,
                            _,
                            _,
                            _ ->

                        graph.containsEdge(
                            fromNodeId,
                            toNodeId
                        ) &&
                                random.nextDouble() <
                                successProbability
                    },

                runId =
                    config.runId,

                instrumentation =
                    context.instrumentation
            )

        val simulator =
            createSimulator(
                config =
                    config,

                simulationEngine =
                    simulationEngine,

                transmitter =
                    transmitter,

                instrumentation =
                    context.instrumentation
            )

        val sourceId =
            "N0"

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                simulator.send(
                    packet =
                        createPacket(
                            config =
                                config,

                            packetIndex =
                                packetIndex,

                            generationTime =
                                generationTime,

                            sourceId =
                                sourceId,

                            destinationId =
                                destinationId
                        ),

                    routeProvider =
                        context.routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config =
                config,

            recorder =
                recorder,

            routeProvider =
                context.routeProvider
        )
    }


    // =====================================================
    // S02 — TOPOLOGY ONLY
    // =====================================================

    fun runSeededTopologyScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireTwoRegimeProtocol(
            config
        )

        require(
            config.scenario.nodeCount == 5
        ) {
            "2RH S02 requires exactly 5 nodes."
        }

        val failureProbability =
            requireNotNull(
                config.scenario
                    .topologyFailureProbability
            ) {
                "2RH S02 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario
                .topologyDecisionTimes

        require(
            topologyDecisionTimes.isNotEmpty()
        ) {
            "2RH S02 requires topologyDecisionTimes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val graph =
            createDualPathGraph()

        val context =
            createTwoRegimeContext(
                config =
                    config,

                graph =
                    graph,

                simulationEngine =
                    simulationEngine,

                recorder =
                    recorder
            )

        /*
         * Perfect links.
         *
         * S02 isolates topology dynamics.
         */
        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    simulationEngine,

                maxAttempts =
                    config.link.maxAttempts,

                delayPerAttempt =
                    config.link.retryDelay,

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
                    },

                runId =
                    config.runId,

                instrumentation =
                    context.instrumentation
            )

        val simulator =
            createSimulator(
                config =
                    config,

                simulationEngine =
                    simulationEngine,

                transmitter =
                    transmitter,

                instrumentation =
                    context.instrumentation
            )

        /*
         * EXACT SAME S02 topology RNG as B0/MM.
         */
        val topologyRandom =
            Random(
                config.seed
            )

        scheduleTopologyDynamics(
            config =
                config,

            graph =
                graph,

            simulationEngine =
                simulationEngine,

            instrumentation =
                context.instrumentation,

            observationTracker =
                context.observationTracker,

            topologyRandom =
                topologyRandom,

            failureProbability =
                failureProbability,

            topologyDecisionTimes =
                topologyDecisionTimes
        )

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                simulator.send(
                    packet =
                        createPacket(
                            config =
                                config,

                            packetIndex =
                                packetIndex,

                            generationTime =
                                generationTime,

                            sourceId =
                                "N0",

                            destinationId =
                                "N4"
                        ),

                    routeProvider =
                        context.routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config =
                config,

            recorder =
                recorder,

            routeProvider =
                context.routeProvider
        )
    }


    // =====================================================
    // S03 — CONGESTION ONLY
    // =====================================================

    fun runSeededCongestionScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireTwoRegimeProtocol(
            config
        )

        require(
            config.scenario.nodeCount >= 2
        ) {
            "2RH S03 requires at least 2 nodes."
        }

        requireNotNull(
            config.traffic.burstProbability
        ) {
            "2RH S03 requires burstProbability."
        }

        requireNotNull(
            config.traffic.burstSize
        ) {
            "2RH S03 requires burstSize."
        }

        requireNotNull(
            config.traffic.burstSpacing
        ) {
            "2RH S03 requires burstSpacing."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val graph =
            createLineGraph(
                config.scenario.nodeCount
            )

        val context =
            createTwoRegimeContext(
                config =
                    config,

                graph =
                    graph,

                simulationEngine =
                    simulationEngine,

                recorder =
                    recorder
            )

        /*
         * Perfect links exactly like B0/MM S03.
         */
        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    simulationEngine,

                maxAttempts =
                    config.link.maxAttempts,

                delayPerAttempt =
                    config.link.retryDelay,

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
                    },

                runId =
                    config.runId,

                instrumentation =
                    context.instrumentation
            )

        val simulator =
            createSimulator(
                config =
                    config,

                simulationEngine =
                    simulationEngine,

                transmitter =
                    transmitter,

                instrumentation =
                    context.instrumentation
            )

        /*
         * EXACT SAME S03 traffic RNG as B0/MM.
         */
        val trafficRandom =
            Random(
                config.seed +
                        3_000_000L
            )

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        scheduleSeededTraffic(
            config =
                config,

            simulationEngine =
                simulationEngine,

            random =
                trafficRandom
        ) {
                packetIndex,
                generationTime ->

            simulator.send(
                packet =
                    createPacket(
                        config =
                            config,

                        packetIndex =
                            packetIndex,

                        generationTime =
                            generationTime,

                        sourceId =
                            "N0",

                        destinationId =
                            destinationId
                    ),

                routeProvider =
                    context.routeProvider
            )
        }

        simulationEngine.run()

        return buildRunOutput(
            config =
                config,

            recorder =
                recorder,

            routeProvider =
                context.routeProvider
        )
    }


    // =====================================================
    // S04 — RELIABILITY + TOPOLOGY
    // =====================================================

    fun runSeededReliabilityTopologyScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireTwoRegimeProtocol(
            config
        )

        require(
            config.scenario.nodeCount == 5
        ) {
            "2RH S04 requires exactly 5 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "2RH S04 requires link.successProbability."
            }

        val failureProbability =
            requireNotNull(
                config.scenario
                    .topologyFailureProbability
            ) {
                "2RH S04 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario
                .topologyDecisionTimes

        require(
            topologyDecisionTimes.isNotEmpty()
        ) {
            "2RH S04 requires topologyDecisionTimes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val graph =
            createDualPathGraph()

        val context =
            createTwoRegimeContext(
                config =
                    config,

                graph =
                    graph,

                simulationEngine =
                    simulationEngine,

                recorder =
                    recorder
            )

        /*
         * EXACT SAME S04 link RNG as B0/MM.
         */
        val linkRandom =
            Random(
                config.seed +
                        4_000_000L
            )

        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    simulationEngine,

                maxAttempts =
                    config.link.maxAttempts,

                delayPerAttempt =
                    config.link.retryDelay,

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
                        ) &&
                                linkRandom.nextDouble() <
                                successProbability
                    },

                runId =
                    config.runId,

                instrumentation =
                    context.instrumentation
            )

        val simulator =
            createSimulator(
                config =
                    config,

                simulationEngine =
                    simulationEngine,

                transmitter =
                    transmitter,

                instrumentation =
                    context.instrumentation
            )

        /*
         * EXACT SAME S04 topology RNG as B0/MM.
         */
        val topologyRandom =
            Random(
                config.seed +
                        4_100_000L
            )

        scheduleTopologyDynamics(
            config =
                config,

            graph =
                graph,

            simulationEngine =
                simulationEngine,

            instrumentation =
                context.instrumentation,

            observationTracker =
                context.observationTracker,

            topologyRandom =
                topologyRandom,

            failureProbability =
                failureProbability,

            topologyDecisionTimes =
                topologyDecisionTimes
        )

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                simulator.send(
                    packet =
                        createPacket(
                            config =
                                config,

                            packetIndex =
                                packetIndex,

                            generationTime =
                                generationTime,

                            sourceId =
                                "N0",

                            destinationId =
                                "N4"
                        ),

                    routeProvider =
                        context.routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config =
                config,

            recorder =
                recorder,

            routeProvider =
                context.routeProvider
        )
    }


    // =====================================================
    // S05 — COMBINED STRESS
    // =====================================================

    fun runSeededCombinedScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireTwoRegimeProtocol(
            config
        )

        require(
            config.scenario.nodeCount == 5
        ) {
            "2RH S05 requires exactly 5 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "2RH S05 requires successProbability."
            }

        val failureProbability =
            requireNotNull(
                config.scenario
                    .topologyFailureProbability
            ) {
                "2RH S05 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario
                .topologyDecisionTimes

        require(
            topologyDecisionTimes.isNotEmpty()
        ) {
            "2RH S05 requires topologyDecisionTimes."
        }

        requireNotNull(
            config.traffic.burstProbability
        )

        requireNotNull(
            config.traffic.burstSize
        )

        requireNotNull(
            config.traffic.burstSpacing
        )

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val graph =
            createDualPathGraph()

        val context =
            createTwoRegimeContext(
                config =
                    config,

                graph =
                    graph,

                simulationEngine =
                    simulationEngine,

                recorder =
                    recorder
            )

        /*
         * EXACT SAME independent S05 streams as B0/MM.
         */
        val linkRandom =
            Random(
                config.seed +
                        5_000_000L
            )

        val topologyRandom =
            Random(
                config.seed +
                        5_100_000L
            )

        val trafficRandom =
            Random(
                config.seed +
                        5_200_000L
            )

        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    simulationEngine,

                maxAttempts =
                    config.link.maxAttempts,

                delayPerAttempt =
                    config.link.retryDelay,

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
                        ) &&
                                linkRandom.nextDouble() <
                                successProbability
                    },

                runId =
                    config.runId,

                instrumentation =
                    context.instrumentation
            )

        val simulator =
            createSimulator(
                config =
                    config,

                simulationEngine =
                    simulationEngine,

                transmitter =
                    transmitter,

                instrumentation =
                    context.instrumentation
            )

        scheduleTopologyDynamics(
            config =
                config,

            graph =
                graph,

            simulationEngine =
                simulationEngine,

            instrumentation =
                context.instrumentation,

            observationTracker =
                context.observationTracker,

            topologyRandom =
                topologyRandom,

            failureProbability =
                failureProbability,

            topologyDecisionTimes =
                topologyDecisionTimes
        )

        scheduleSeededTraffic(
            config =
                config,

            simulationEngine =
                simulationEngine,

            random =
                trafficRandom
        ) {
                packetIndex,
                generationTime ->

            simulator.send(
                packet =
                    createPacket(
                        config =
                            config,

                        packetIndex =
                            packetIndex,

                        generationTime =
                            generationTime,

                        sourceId =
                            "N0",

                        destinationId =
                            "N4"
                    ),

                routeProvider =
                    context.routeProvider
            )
        }

        simulationEngine.run()

        return buildRunOutput(
            config =
                config,

            recorder =
                recorder,

            routeProvider =
                context.routeProvider
        )
    }


    // =====================================================
    // 2RH CONTEXT
    // =====================================================

    private data class TwoRegimeContext(

        val stateStore:
        MultiMetricStateStore,

        val observationTracker:
        MultiMetricObservationTracker,

        val instrumentation:
        MMInstrumentation,

        val mmRouteProvider:
        MMRouteProvider,

        val routeProvider:
        TwoRegimeRouteProvider
    )


    private fun createTwoRegimeContext(
        config: ExperimentConfig,
        graph: Graph,
        simulationEngine: SimulationEngine,
        recorder: ExperimentRecorder
    ): TwoRegimeContext {

        val stateStore =
            MultiMetricStateStore()

        val observationTracker =
            MultiMetricObservationTracker(

                stateStore =
                    stateStore,

                reliabilityWindowSize =
                    reliabilityWindowSize,

                delayWindowSize =
                    delayWindowSize,

                delayReference =
                    delayReference,

                instabilityReference =
                    instabilityReference
            )

        /*
         * Same edge-observation initialization used by MM.
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
                            config.scenario.queueCapacity
                    )

                observationTracker
                    .registerEdge(
                        fromNodeId =
                            edge.to,

                        toNodeId =
                            edge.from,

                        queueCapacity =
                            config.scenario.queueCapacity
                    )
            }

        val recorderInstrumentation =
            RecorderInstrumentation(
                recorder
            )

        val queueCapacityByNode =
            buildMap {

                repeat(
                    config.scenario.nodeCount
                ) { index ->

                    put(
                        nodeIdForIndex(
                            index
                        ),
                        config.scenario.queueCapacity
                    )
                }
            }

        /*
         * Reuse MM observation instrumentation.
         *
         * This is intentional:
         *
         * 2RH HIGH receives exactly the same metric evidence
         * as frozen MM-v1.0.
         */
        val instrumentation =
            MMInstrumentation(

                delegate =
                    recorderInstrumentation,

                observationTracker =
                    observationTracker,

                queueCapacityByNode =
                    queueCapacityByNode,

                retryDelay =
                    config.link.retryDelay
            )

        /*
         * Frozen MM-v1.0 deterministic route selector.
         */
        val mmRouteProvider =
            MMRouteProvider(

                graph =
                    graph,

                stateStore =
                    stateStore,

                runId =
                    config.runId,

                instrumentation =
                    instrumentation,

                timeProvider = {
                    simulationEngine.currentTime
                },

                hysteresisFraction =
                    hysteresisFraction
            )

        /*
         * Evaluates the MM candidate route using the
         * bottleneck route-confidence rule.
         */
        val routeEvaluator =
            TwoRegimeRouteEvaluator(

                stateStore =
                    stateStore,

                traceObserver =
                    routeTraceObserver
            )

        /*
         * Deliberately simple bounded LOW policy.
         *
         * No MEDIUM regime exists in 2RH.
         */
        val fallbackPolicy =
            TwoRegimeFallbackPolicy(

                maxReevaluations =
                    maxFallbackReevaluations,

                reevaluationDelay =
                    fallbackReevaluationDelay
            )

        val routeProvider =
            TwoRegimeRouteProvider(

                mmRouteProvider =
                    mmRouteProvider,

                routeEvaluator =
                    routeEvaluator,

                fallbackPolicy =
                    fallbackPolicy
            )

        return TwoRegimeContext(

            stateStore =
                stateStore,

            observationTracker =
                observationTracker,

            instrumentation =
                instrumentation,

            mmRouteProvider =
                mmRouteProvider,

            routeProvider =
                routeProvider
        )
    }


    // =====================================================
    // SIMULATOR
    // =====================================================

    private fun createSimulator(
        config: ExperimentConfig,
        simulationEngine: SimulationEngine,
        transmitter: EventDrivenRetryLinkTransmitter,
        instrumentation: MMInstrumentation
    ): TimedNetworkSimulator {

        val simulator =
            TimedNetworkSimulator(

                simulationEngine =
                    simulationEngine,

                eventDrivenLinkTransmitter =
                    transmitter,

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

        /*
         * Preserve exact B0/MM behavior:
         *
         * N0 is the generated packet source and therefore
         * does not require a service queue.
         */
        for (
        index in 1 until
                config.scenario.nodeCount
        ) {

            simulator.addNode(

                nodeId =
                    nodeIdForIndex(
                        index
                    ),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        return simulator
    }


    // =====================================================
    // TOPOLOGY DYNAMICS
    // =====================================================

    private fun scheduleTopologyDynamics(
        config: ExperimentConfig,
        graph: Graph,
        simulationEngine: SimulationEngine,
        instrumentation: MMInstrumentation,
        observationTracker:
        MultiMetricObservationTracker,
        topologyRandom: Random,
        failureProbability: Double,
        topologyDecisionTimes: List<Long>
    ) {

        topologyDecisionTimes
            .forEach { eventTime ->

                simulationEngine.schedule(
                    eventTime
                ) {

                    /*
                     * Same instability aging semantics as MM.
                     */
                    observationTracker
                        .decayInstability()

                    val shouldFail =
                        topologyRandom
                            .nextDouble() <
                                failureProbability

                    val currentlyUp =
                        graph.containsEdge(
                            "N2",
                            "N4"
                        )

                    if (
                        shouldFail &&
                        currentlyUp
                    ) {

                        val oldWeight =
                            graph.edgeCost(
                                "N2",
                                "N4"
                            )

                        graph.removeEdge(
                            "N2",
                            "N4"
                        )

                        instrumentation
                            .onTopologyEvent(
                                TopologyEventRecord(

                                    runId =
                                        config.runId,

                                    eventTime =
                                        eventTime,

                                    fromNodeId =
                                        "N2",

                                    toNodeId =
                                        "N4",

                                    eventType =
                                        TopologyEventType
                                            .LINK_DOWN,

                                    oldWeight =
                                        oldWeight,

                                    newWeight =
                                        null
                                )
                            )

                    } else if (
                        !shouldFail &&
                        !currentlyUp
                    ) {

                        graph.addEdge(
                            from =
                                "N2",

                            to =
                                "N4",

                            weight =
                                1
                        )

                        /*
                         * Restore MM observation state for
                         * both directed representations.
                         */
                        observationTracker
                            .registerEdge(
                                fromNodeId =
                                    "N2",

                                toNodeId =
                                    "N4",

                                queueCapacity =
                                    config.scenario
                                        .queueCapacity
                            )

                        observationTracker
                            .registerEdge(
                                fromNodeId =
                                    "N4",

                                toNodeId =
                                    "N2",

                                queueCapacity =
                                    config.scenario
                                        .queueCapacity
                            )

                        instrumentation
                            .onTopologyEvent(
                                TopologyEventRecord(

                                    runId =
                                        config.runId,

                                    eventTime =
                                        eventTime,

                                    fromNodeId =
                                        "N2",

                                    toNodeId =
                                        "N4",

                                    eventType =
                                        TopologyEventType
                                            .LINK_UP,

                                    oldWeight =
                                        null,

                                    newWeight =
                                        1
                                )
                            )
                    }
                }
            }
    }


    // =====================================================
    // TOPOLOGY HELPERS
    // =====================================================

    private fun createLineGraph(
        nodeCount: Int
    ): Graph {

        val graph =
            Graph()

        repeat(
            nodeCount
        ) { index ->

            val nodeId =
                nodeIdForIndex(
                    index
                )

            graph.addNode(
                Node(
                    nodeId =
                        nodeId,

                    displayName =
                        nodeId
                )
            )
        }

        for (
        index in 0 until
                nodeCount - 1
        ) {

            graph.addEdge(

                from =
                    nodeIdForIndex(
                        index
                    ),

                to =
                    nodeIdForIndex(
                        index + 1
                    ),

                weight =
                    1
            )
        }

        return graph
    }


    private fun createDualPathGraph():
            Graph {

        /*
         *       N2
         *      /  \
         * N0--N1   N4
         *      \  /
         *       N3
         */

        val graph =
            Graph()

        repeat(
            5
        ) { index ->

            val id =
                nodeIdForIndex(
                    index
                )

            graph.addNode(
                Node(
                    nodeId =
                        id,

                    displayName =
                        id
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


    private fun nodeIdForIndex(
        index: Int
    ): String {

        return "N$index"
    }


    // =====================================================
    // PACKET
    // =====================================================

    private fun createPacket(
        config: ExperimentConfig,
        packetIndex: Int,
        generationTime: Long,
        sourceId: String,
        destinationId: String
    ): Packet {

        return Packet(

            messageId =
                "${config.runId}-MSG-$packetIndex",

            sourceId =
                sourceId,

            destinationId =
                destinationId,

            createdAt =
                generationTime,

            ttl =
                config.traffic.packetTtl,

            payload =
                createPayload(
                    config.traffic.payloadBytes
                )
        )
    }


    private fun createPayload(
        payloadBytes: Int
    ): String {

        return "X".repeat(
            payloadBytes
        )
    }


    // =====================================================
    // SEEDED TRAFFIC
    // =====================================================

    private fun scheduleSeededTraffic(
        config: ExperimentConfig,
        simulationEngine: SimulationEngine,
        random: Random,
        sendPacket: (
            packetIndex: Int,
            generationTime: Long
        ) -> Unit
    ) {

        val burstProbability =
            config.traffic
                .burstProbability

        val burstSize =
            config.traffic
                .burstSize

        val burstSpacing =
            config.traffic
                .burstSpacing

        /*
         * Preserve MM/B0 fixed-interval behavior whenever
         * burst configuration is absent.
         */
        if (
            burstProbability == null ||
            burstSize == null ||
            burstSpacing == null
        ) {

            repeat(
                config.traffic.packetCount
            ) { packetIndex ->

                val generationTime =
                    packetIndex.toLong() *
                            config.traffic.packetInterval

                simulationEngine.schedule(
                    generationTime
                ) {

                    sendPacket(
                        packetIndex,
                        generationTime
                    )
                }
            }

            return
        }

        var packetIndex =
            0

        var baseTime =
            0L

        while (
            packetIndex <
            config.traffic.packetCount
        ) {

            val generateBurst =
                random.nextDouble() <
                        burstProbability

            val packetsThisOpportunity =
                if (
                    generateBurst
                ) {

                    minOf(
                        burstSize,
                        config.traffic.packetCount -
                                packetIndex
                    )

                } else {

                    1
                }

            repeat(
                packetsThisOpportunity
            ) { burstIndex ->

                val currentPacketIndex =
                    packetIndex

                val generationTime =
                    baseTime +
                            burstIndex *
                            burstSpacing

                simulationEngine.schedule(
                    generationTime
                ) {

                    sendPacket(
                        currentPacketIndex,
                        generationTime
                    )
                }

                packetIndex++
            }

            baseTime +=
                config.traffic.packetInterval
        }
    }


    // =====================================================
    // PACKET ACCOUNTING
    // =====================================================

    private fun validatePacketAccounting(
        config: ExperimentConfig,
        packets: List<PacketRecord>
    ) {

        val expected =
            config.traffic.packetCount

        require(
            packets.size ==
                    expected
        ) {
            "Invalid 2RH run ${config.runId}: " +
                    "expected $expected terminal packet records, " +
                    "found ${packets.size}."
        }

        val uniqueMessageCount =
            packets
                .map {
                    it.messageId
                }
                .distinct()
                .size

        require(
            uniqueMessageCount ==
                    expected
        ) {
            "Invalid 2RH run ${config.runId}: " +
                    "duplicate terminal packet records detected."
        }

        val delivered =
            packets.count {
                it.delivered
            }

        val dropped =
            packets.count {
                it.dropped
            }

        require(
            delivered + dropped ==
                    expected
        ) {
            "Invalid 2RH run ${config.runId}: " +
                    "delivered + dropped does not equal generated."
        }

        require(
            packets.none {
                it.delivered &&
                        it.dropped
            }
        ) {
            "Invalid 2RH run ${config.runId}: " +
                    "packet cannot be both delivered and dropped."
        }
    }


    // =====================================================
    // TRANSMISSION ACCOUNTING
    // =====================================================

    private fun validateTransmissionAccounting(
        transmissions:
        List<TransmissionRecord>
    ) {

        require(
            transmissions.all {
                it.logicalHopIndex != null
            }
        ) {
            "2RH transmission missing logicalHopIndex."
        }

        val groups =
            transmissions.groupBy {
                "${it.messageId}:${it.logicalHopIndex}"
            }

        for (
        (_, attempts) in groups
        ) {

            val actualNumbers =
                attempts
                    .map {
                        it.attemptNumber
                    }
                    .sorted()

            val expectedNumbers =
                (1..attempts.size)
                    .toList()

            require(
                actualNumbers ==
                        expectedNumbers
            ) {
                "Invalid 2RH transmission attempt sequence: " +
                        "$actualNumbers"
            }
        }
    }


    // =====================================================
    // RESOURCE PROXY DERIVATION
    // =====================================================

    private fun buildFinalResourceSamples(
        config: ExperimentConfig,
        packets: List<PacketRecord>,
        transmissions:
        List<TransmissionRecord>,
        queueEvents:
        List<QueueEventRecord>,
        sampleTime: Long
    ): List<ResourceSampleRecord> {

        /*
         * Keep queueEvents as part of the common research
         * evidence contract.
         */
        queueEvents.size

        val packetByMessageId =
            packets.associateBy {
                it.messageId
            }

        val logicalHopGroups =
            transmissions.groupBy {
                Pair(
                    it.messageId,
                    it.logicalHopIndex
                )
            }

        val successfulLogicalHops =
            logicalHopGroups.values
                .mapNotNull { attempts ->

                    attempts.firstOrNull {
                        it.success
                    }
                }

        val nodeIds =
            buildSet {

                repeat(
                    config.scenario.nodeCount
                ) { index ->

                    add(
                        nodeIdForIndex(
                            index
                        )
                    )
                }
            }

        return nodeIds
            .sorted()
            .map { nodeId ->

                val transmittedLogicalHops =
                    successfulLogicalHops
                        .count {
                            it.fromNodeId ==
                                    nodeId
                        }

                val receivedLogicalHops =
                    successfulLogicalHops
                        .count {
                            it.toNodeId ==
                                    nodeId
                        }

                val forwardedLogicalHops =
                    successfulLogicalHops
                        .count { transmission ->

                            val packet =
                                packetByMessageId[
                                    transmission.messageId
                                ]

                            transmission.fromNodeId ==
                                    nodeId &&
                                    packet != null &&
                                    packet.sourceId !=
                                    nodeId
                        }

                val nodePhysicalAttempts =
                    transmissions.count {
                        it.fromNodeId ==
                                nodeId
                    }

                val nodeRetransmissions =
                    transmissions.count {
                        it.fromNodeId ==
                                nodeId &&
                                it.attemptNumber > 1
                    }

                ResourceSampleRecord(

                    runId =
                        config.runId,

                    nodeId =
                        nodeId,

                    sampleTime =
                        sampleTime,

                    packetsTransmitted =
                        transmittedLogicalHops
                            .toLong(),

                    packetsReceived =
                        receivedLogicalHops
                            .toLong(),

                    packetsForwarded =
                        forwardedLogicalHops
                            .toLong(),

                    physicalAttempts =
                        nodePhysicalAttempts
                            .toLong(),

                    retransmissions =
                        nodeRetransmissions
                            .toLong(),

                    queueOccupancy =
                        0,

                    routingCalculations =
                        0L
                )
            }
    }


    // =====================================================
    // CROSS-STREAM RECONCILIATION
    // =====================================================

    private fun validateCrossStreamReconciliation(
        config: ExperimentConfig,
        packets: List<PacketRecord>,
        transmissions:
        List<TransmissionRecord>,
        queueEvents:
        List<QueueEventRecord>,
        topologyEvents:
        List<TopologyEventRecord>,
        resourceSamples:
        List<ResourceSampleRecord>
    ) {

        require(
            packets.size ==
                    config.traffic.packetCount
        )

        val transmissionAttemptCount =
            transmissions.size
                .toLong()

        val resourcePhysicalAttempts =
            resourceSamples.sumOf {
                it.physicalAttempts
            }

        require(
            transmissionAttemptCount ==
                    resourcePhysicalAttempts
        ) {
            "2RH reconciliation failed: " +
                    "transmission attempts=$transmissionAttemptCount " +
                    "resource attempts=$resourcePhysicalAttempts."
        }

        val transmissionRetransmissions =
            transmissions.count {
                it.attemptNumber > 1
            }
                .toLong()

        val resourceRetransmissions =
            resourceSamples.sumOf {
                it.retransmissions
            }

        require(
            transmissionRetransmissions ==
                    resourceRetransmissions
        ) {
            "2RH retransmission reconciliation failed."
        }

        val successfulLogicalHopCount =
            transmissions
                .groupBy {
                    Pair(
                        it.messageId,
                        it.logicalHopIndex
                    )
                }
                .count {
                        (_, attempts) ->

                    attempts.any {
                        it.success
                    }
                }
                .toLong()

        val resourceSuccessfulTransmissions =
            resourceSamples.sumOf {
                it.packetsTransmitted
            }

        val resourceSuccessfulReceives =
            resourceSamples.sumOf {
                it.packetsReceived
            }

        require(
            successfulLogicalHopCount ==
                    resourceSuccessfulTransmissions
        )

        require(
            successfulLogicalHopCount ==
                    resourceSuccessfulReceives
        )

        val enqueueCount =
            queueEvents.count {
                it.eventType ==
                        QueueEventType.ENQUEUED
            }

        val dequeueCount =
            queueEvents.count {
                it.eventType ==
                        QueueEventType.DEQUEUED
            }

        require(
            enqueueCount ==
                    dequeueCount
        ) {
            "2RH reconciliation failed: " +
                    "ENQUEUED=$enqueueCount " +
                    "DEQUEUED=$dequeueCount."
        }

        require(
            topologyEvents.all {
                it.runId ==
                        config.runId
            }
        )

        require(
            resourceSamples.all {
                it.runId ==
                        config.runId
            }
        )

        require(
            resourceSamples.size ==
                    config.scenario.nodeCount
        )

        require(
            resourceSamples
                .map {
                    it.nodeId
                }
                .distinct()
                .size ==
                    config.scenario.nodeCount
        )
    }


    // =====================================================
    // FINAL OUTPUT
    // =====================================================

    private fun buildRunOutput(
        config: ExperimentConfig,
        recorder: ExperimentRecorder,
        routeProvider: TwoRegimeRouteProvider
    ): RunOutput {

        val packetRecords =
            recorder.getPacketRecords()

        val transmissionRecords =
            recorder.getTransmissionRecords()

        val routingEventRecords =
            recorder.getRoutingEventRecords()

        val topologyEventRecords =
            recorder.getTopologyEventRecords()

        val queueEventRecords =
            recorder.getQueueEventRecords()

        val existingResourceSamples =
            recorder.getResourceSampleRecords()

        require(
            existingResourceSamples.isEmpty()
        ) {
            "2RH runner unexpectedly already contains resource samples."
        }

        val resourceSampleRecords =
            buildFinalResourceSamples(

                config =
                    config,

                packets =
                    packetRecords,

                transmissions =
                    transmissionRecords,

                queueEvents =
                    queueEventRecords,

                sampleTime =
                    packetRecords
                        .mapNotNull {
                            it.deliveredAt
                                ?: it.droppedAt
                        }
                        .maxOrNull()
                        ?: 0L
            )

        validatePacketAccounting(
            config =
                config,

            packets =
                packetRecords
        )

        validateTransmissionAccounting(
            transmissions =
                transmissionRecords
        )

        validateCrossStreamReconciliation(

            config =
                config,

            packets =
                packetRecords,

            transmissions =
                transmissionRecords,

            queueEvents =
                queueEventRecords,

            topologyEvents =
                topologyEventRecords,

            resourceSamples =
                resourceSampleRecords
        )

        /*
         * 2RH exposes MM's common routing telemetry through
         * TwoRegimeRouteProvider.telemetry.
         *
         * This preserves the shared RunSummary schema.
         */
        val summary =
            ExperimentAggregator.aggregate(

                config =
                    config,

                packets =
                    packetRecords,

                transmissions =
                    transmissionRecords,

                routingEvents =
                    routingEventRecords,

                topologyEvents =
                    topologyEventRecords,

                queueEvents =
                    queueEventRecords,

                resourceSamples =
                    resourceSampleRecords,

                routingTelemetry =
                    routeProvider.telemetry
            )

        return RunOutput(

            summary =
                summary,

            packets =
                packetRecords,

            transmissions =
                transmissionRecords,

            routingEvents =
                routingEventRecords,

            topologyEvents =
                topologyEventRecords,

            queueEvents =
                queueEventRecords,

            resourceSamples =
                resourceSampleRecords,

            adaptation =
                routeProvider
                    .adaptationTelemetry
                    .snapshot()
        )
    }


    // =====================================================
    // PROTOCOL VALIDATION
    // =====================================================

    private fun requireTwoRegimeProtocol(
        config: ExperimentConfig
    ) {

        require(
            config.protocol ==
                    "2RH"
        ) {
            "TwoRegimeExperimentRunner only runs protocol 2RH. " +
                    "Received ${config.protocol}."
        }
    }
}