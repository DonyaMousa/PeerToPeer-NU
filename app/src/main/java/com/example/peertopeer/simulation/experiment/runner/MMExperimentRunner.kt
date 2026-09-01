package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.mm.MultiMetricObservationTracker
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
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

class MMExperimentRunner(

    private val hysteresisFraction:
    Double = 0.05

) {

    init {

        require(
            hysteresisFraction in 0.0..1.0
        ) {
            "hysteresisFraction must be between 0.0 and 1.0."
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
        List<ResourceSampleRecord>
    )

    /*
     * -----------------------------------------------------
     * MM experimental observation parameters
     * -----------------------------------------------------
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

        requireMMProtocol(
            config
        )

        require(
            config.scenario.nodeCount >= 2
        ) {
            "MM S01 requires at least 2 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "MM S01 requires link.successProbability."
            }

        require(
            successProbability in 0.0..1.0
        )

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val graph =
            createLineGraph(
                config.scenario.nodeCount
            )

        val mmContext =
            createMMContext(
                config = config,
                graph = graph,
                simulationEngine = simulationEngine,
                recorder = recorder
            )

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
                    mmContext.instrumentation
            )

        val simulator =
            createSimulator(
                config = config,
                simulationEngine = simulationEngine,
                transmitter = transmitter,
                instrumentation =
                    mmContext.instrumentation
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
                            config = config,
                            packetIndex = packetIndex,
                            generationTime =
                                generationTime,
                            sourceId = sourceId,
                            destinationId =
                                destinationId
                        ),

                    routeProvider =
                        mmContext.routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider =
                mmContext.routeProvider
        )
    }

    // =====================================================
    // S02 — TOPOLOGY ONLY
    // =====================================================

    fun runSeededTopologyScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireMMProtocol(
            config
        )

        require(
            config.scenario.nodeCount == 5
        ) {
            "MM S02 requires exactly 5 nodes."
        }

        val failureProbability =
            requireNotNull(
                config.scenario
                    .topologyFailureProbability
            ) {
                "MM S02 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario
                .topologyDecisionTimes

        require(
            topologyDecisionTimes
                .isNotEmpty()
        ) {
            "MM S02 requires topologyDecisionTimes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val graph =
            createDualPathGraph()

        val mmContext =
            createMMContext(
                config = config,
                graph = graph,
                simulationEngine = simulationEngine,
                recorder = recorder
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
                        )
                    },

                runId =
                    config.runId,

                instrumentation =
                    mmContext.instrumentation
            )

        val simulator =
            createSimulator(
                config = config,
                simulationEngine =
                    simulationEngine,
                transmitter = transmitter,
                instrumentation =
                    mmContext.instrumentation
            )

        val topologyRandom =
            Random(
                config.seed
            )

        scheduleTopologyDynamics(
            config = config,
            graph = graph,
            simulationEngine =
                simulationEngine,
            instrumentation =
                mmContext.instrumentation,
            observationTracker =
                mmContext.observationTracker,
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
                            config = config,
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
                        mmContext.routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider =
                mmContext.routeProvider
        )
    }

    // =====================================================
    // S03 — CONGESTION ONLY
    // =====================================================

    fun runSeededCongestionScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireMMProtocol(
            config
        )

        require(
            config.scenario.nodeCount >= 2
        )

        requireNotNull(
            config.traffic.burstProbability
        ) {
            "MM S03 requires burstProbability."
        }

        requireNotNull(
            config.traffic.burstSize
        ) {
            "MM S03 requires burstSize."
        }

        requireNotNull(
            config.traffic.burstSpacing
        ) {
            "MM S03 requires burstSpacing."
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

        val mmContext =
            createMMContext(
                config = config,
                graph = graph,
                simulationEngine =
                    simulationEngine,
                recorder = recorder
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
                        )
                    },

                runId =
                    config.runId,

                instrumentation =
                    mmContext.instrumentation
            )

        val simulator =
            createSimulator(
                config = config,
                simulationEngine =
                    simulationEngine,
                transmitter = transmitter,
                instrumentation =
                    mmContext.instrumentation
            )

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
            config = config,
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
                        config = config,
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
                    mmContext.routeProvider
            )
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider =
                mmContext.routeProvider
        )
    }

    // =====================================================
    // S04 — RELIABILITY + TOPOLOGY
    // =====================================================

    fun runSeededReliabilityTopologyScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireMMProtocol(
            config
        )

        require(
            config.scenario.nodeCount == 5
        ) {
            "MM S04 requires exactly 5 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "MM S04 requires link.successProbability."
            }

        val failureProbability =
            requireNotNull(
                config.scenario
                    .topologyFailureProbability
            ) {
                "MM S04 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario
                .topologyDecisionTimes

        require(
            topologyDecisionTimes
                .isNotEmpty()
        )

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val graph =
            createDualPathGraph()

        val mmContext =
            createMMContext(
                config = config,
                graph = graph,
                simulationEngine =
                    simulationEngine,
                recorder = recorder
            )

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
                    mmContext.instrumentation
            )

        val simulator =
            createSimulator(
                config = config,
                simulationEngine =
                    simulationEngine,
                transmitter = transmitter,
                instrumentation =
                    mmContext.instrumentation
            )

        val topologyRandom =
            Random(
                config.seed +
                        4_100_000L
            )

        scheduleTopologyDynamics(
            config = config,
            graph = graph,
            simulationEngine =
                simulationEngine,
            instrumentation =
                mmContext.instrumentation,
            observationTracker =
                mmContext.observationTracker,
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
                            config = config,
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
                        mmContext.routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider =
                mmContext.routeProvider
        )
    }

    // =====================================================
    // S05 — COMBINED STRESS
    // =====================================================

    fun runSeededCombinedScenario(
        config: ExperimentConfig
    ): RunOutput {

        requireMMProtocol(
            config
        )

        require(
            config.scenario.nodeCount == 5
        ) {
            "MM S05 requires exactly 5 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "MM S05 requires successProbability."
            }

        val failureProbability =
            requireNotNull(
                config.scenario
                    .topologyFailureProbability
            ) {
                "MM S05 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario
                .topologyDecisionTimes

        require(
            topologyDecisionTimes
                .isNotEmpty()
        )

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

        val mmContext =
            createMMContext(
                config = config,
                graph = graph,
                simulationEngine =
                    simulationEngine,
                recorder = recorder
            )

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
                    mmContext.instrumentation
            )

        val simulator =
            createSimulator(
                config = config,
                simulationEngine =
                    simulationEngine,
                transmitter = transmitter,
                instrumentation =
                    mmContext.instrumentation
            )

        scheduleTopologyDynamics(
            config = config,
            graph = graph,
            simulationEngine =
                simulationEngine,
            instrumentation =
                mmContext.instrumentation,
            observationTracker =
                mmContext.observationTracker,
            topologyRandom =
                topologyRandom,
            failureProbability =
                failureProbability,
            topologyDecisionTimes =
                topologyDecisionTimes
        )

        scheduleSeededTraffic(
            config = config,
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
                        config = config,
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
                    mmContext.routeProvider
            )
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider =
                mmContext.routeProvider
        )
    }

    // =====================================================
    // MM CONTEXT
    // =====================================================

    private data class MMContext(

        val stateStore:
        MultiMetricStateStore,

        val observationTracker:
        MultiMetricObservationTracker,

        val instrumentation:
        MMInstrumentation,

        val routeProvider:
        MMRouteProvider
    )

    private fun createMMContext(
        config: ExperimentConfig,
        graph: Graph,
        simulationEngine: SimulationEngine,
        recorder: ExperimentRecorder
    ): MMContext {

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

        graph.getEdges()
            .forEach { edge ->

                observationTracker
                    .registerEdge(
                        fromNodeId =
                            edge.from,

                        toNodeId =
                            edge.to,

                        queueCapacity =
                            config.scenario
                                .queueCapacity
                    )

                observationTracker
                    .registerEdge(
                        fromNodeId =
                            edge.to,

                        toNodeId =
                            edge.from,

                        queueCapacity =
                            config.scenario
                                .queueCapacity
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
                        config.scenario
                            .queueCapacity
                    )
                }
            }

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

        val routeProvider =
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

        return MMContext(
            stateStore =
                stateStore,

            observationTracker =
                observationTracker,

            instrumentation =
                instrumentation,

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
                    config.scenario
                        .queueCapacity,

                serviceTime =
                    config.scenario
                        .serviceTime
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
                    config.traffic
                        .payloadBytes
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
                            config.traffic
                                .packetInterval

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
                if (generateBurst) {

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
                config.traffic
                    .packetInterval
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
            "Invalid MM run ${config.runId}: " +
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
            "Invalid MM run ${config.runId}: " +
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
            "Invalid MM run ${config.runId}: " +
                    "delivered + dropped does not equal generated."
        }

        require(
            packets.none {
                it.delivered &&
                        it.dropped
            }
        ) {
            "Invalid MM run ${config.runId}: " +
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
            "MM transmission missing logicalHopIndex."
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
                "Invalid MM transmission attempt sequence: " +
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
        )

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
        )

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
        )

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
        routeProvider: MMRouteProvider
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
            existingResourceSamples
                .isEmpty()
        ) {
            "MM runner unexpectedly already contains resource samples."
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
                            it.deliveredAt ?:
                            it.droppedAt
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
                resourceSampleRecords
        )
    }

    // =====================================================
    // PROTOCOL VALIDATION
    // =====================================================

    private fun requireMMProtocol(
        config: ExperimentConfig
    ) {

        require(
            config.protocol == "MM"
        ) {
            "MMExperimentRunner only runs protocol MM. " +
                    "Received ${config.protocol}."
        }
    }
}