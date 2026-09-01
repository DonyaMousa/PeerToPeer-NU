package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.simulation.B0DynamicRouteProvider
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.ResourceSampleRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventType
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import com.example.peertopeer.simulation.experiment.result.ExperimentAggregator
import com.example.peertopeer.simulation.experiment.result.RunSummary
import kotlin.random.Random

class B0ExperimentRunner {

    data class RunOutput(
        val summary: RunSummary,
        val packets: List<PacketRecord>,
        val transmissions: List<TransmissionRecord>,
        val routingEvents: List<RoutingEventRecord>,
        val topologyEvents: List<TopologyEventRecord>,
        val queueEvents: List<QueueEventRecord>,
        val resourceSamples: List<ResourceSampleRecord>
    )

    // =====================================================
    // E01 — HEALTHY LINE
    // =====================================================

    fun runHealthyLine(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 2) {
            "Healthy line requires at least 2 nodes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            createLineGraph(
                nodeCount = config.scenario.nodeCount
            )

        val sourceId =
            nodeIdForIndex(0)

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
            )

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = simulationEngine,
                maxAttempts = config.link.maxAttempts,
                delayPerAttempt = config.link.retryDelay,
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
                        )
                    },
                runId = config.runId,
                instrumentation = instrumentation
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter,
                runId = config.runId,
                instrumentation = instrumentation
            )

        for (
        index in 1 until
                config.scenario.nodeCount
        ) {

            simulator.addNode(
                nodeId =
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
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

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // E02 — CONTROLLED RETRY DEGRADATION
    // =====================================================

    fun runControlledRetryDegradation(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 5) {
            "Controlled retry degradation currently requires at least 5 nodes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            createLineGraph(
                nodeCount =
                    config.scenario.nodeCount
            )

        val sourceId =
            nodeIdForIndex(0)

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
            )

        val degradedFrom =
            "N2"

        val degradedTo =
            "N3"

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
                            messageId,
                            attemptNumber,
                            _ ->

                        val packetIndex =
                            extractPacketIndex(
                                messageId
                            )

                        val isDegradedLink =
                            fromNodeId ==
                                    degradedFrom &&
                                    toNodeId ==
                                    degradedTo

                        val shouldInjectFailure =
                            isDegradedLink &&
                                    packetIndex % 2 == 0 &&
                                    attemptNumber == 1

                        !shouldInjectFailure
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
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

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // E03 — CONTROLLED CONGESTION
    // =====================================================

    fun runControlledCongestion(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 2) {
            "Controlled congestion requires at least 2 nodes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            createLineGraph(
                nodeCount =
                    config.scenario.nodeCount
            )

        val sourceId =
            nodeIdForIndex(0)

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
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
                        )
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
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

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // E04 — ALTERNATE-ROUTE FAILURE
    // =====================================================

    fun runAlternateRouteFailure(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount == 5) {
            "E04 alternate-route scenario requires exactly 5 nodes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            Graph()

        repeat(5) { index ->

            val nodeId =
                nodeIdForIndex(index)

            graph.addNode(
                Node(
                    nodeId = nodeId,
                    displayName = nodeId
                )
            )
        }

        graph.addEdge(
            from = "N0",
            to = "N1",
            weight = 1
        )

        graph.addEdge(
            from = "N1",
            to = "N2",
            weight = 1
        )

        graph.addEdge(
            from = "N2",
            to = "N4",
            weight = 1
        )

        graph.addEdge(
            from = "N1",
            to = "N3",
            weight = 1
        )

        graph.addEdge(
            from = "N3",
            to = "N4",
            weight = 1
        )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
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
                        )
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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

        for (index in 1..4) {

            simulator.addNode(
                nodeId =
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        val failureTime =
            27L

        simulationEngine.schedule(
            failureTime
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

            instrumentation.onTopologyEvent(
                TopologyEventRecord(
                    config.runId,
                    failureTime,
                    "N2",
                    "N4",
                    TopologyEventType.LINK_DOWN,
                    oldWeight,
                    null
                )
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
                        messageId =
                            "${config.runId}-MSG-$packetIndex",

                        sourceId =
                            "N0",

                        destinationId =
                            "N4",

                        createdAt =
                            generationTime,

                        ttl =
                            config.traffic.packetTtl,

                        payload =
                            createPayload(
                                config.traffic.payloadBytes
                            )
                    )

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // E05 — PARTITION + RECOVERY
    // =====================================================

    fun runPartitionRecovery(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount == 4) {
            "E05 partition/recovery scenario requires exactly 4 nodes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            createLineGraph(
                nodeCount = 4
            )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
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
                        )
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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

        for (index in 1..3) {

            simulator.addNode(
                nodeId =
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        val partitionTime =
            20L

        val recoveryTime =
            50L

        simulationEngine.schedule(
            partitionTime
        ) {

            val oldWeight =
                graph.edgeCost(
                    "N2",
                    "N3"
                )

            graph.removeEdge(
                "N2",
                "N3"
            )

            instrumentation.onTopologyEvent(
                TopologyEventRecord(
                    config.runId,
                    partitionTime,
                    "N2",
                    "N3",
                    TopologyEventType.LINK_DOWN,
                    oldWeight,
                    null
                )
            )
        }

        simulationEngine.schedule(
            recoveryTime
        ) {

            graph.addEdge(
                from = "N2",
                to = "N3",
                weight = 1
            )

            instrumentation.onTopologyEvent(
                TopologyEventRecord(
                    config.runId,
                    recoveryTime,
                    "N2",
                    "N3",
                    TopologyEventType.LINK_UP,
                    null,
                    1
                )
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
                        messageId =
                            "${config.runId}-MSG-$packetIndex",

                        sourceId =
                            "N0",

                        destinationId =
                            "N3",

                        createdAt =
                            generationTime,

                        ttl =
                            config.traffic.packetTtl,

                        payload =
                            createPayload(
                                config.traffic.payloadBytes
                            )
                    )

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // E06 — COMBINED STRESS
    // =====================================================

    fun runCombinedStress(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount == 5) {
            "E06 combined-stress scenario requires exactly 5 nodes."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            Graph()

        repeat(5) { index ->

            val nodeId =
                nodeIdForIndex(index)

            graph.addNode(
                Node(
                    nodeId = nodeId,
                    displayName = nodeId
                )
            )
        }

        graph.addEdge(
            from = "N0",
            to = "N1",
            weight = 1
        )

        graph.addEdge(
            from = "N1",
            to = "N2",
            weight = 1
        )

        graph.addEdge(
            from = "N2",
            to = "N4",
            weight = 1
        )

        graph.addEdge(
            from = "N1",
            to = "N3",
            weight = 1
        )

        graph.addEdge(
            from = "N3",
            to = "N4",
            weight = 1
        )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
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
                            messageId,
                            attemptNumber,
                            _ ->

                        if (
                            !graph.containsEdge(
                                fromNodeId,
                                toNodeId
                            )
                        ) {

                            false

                        } else {

                            val packetIndex =
                                extractPacketIndex(
                                    messageId
                                )

                            val degradedLink =
                                fromNodeId == "N1" &&
                                        toNodeId == "N2"

                            val injectFailure =
                                degradedLink &&
                                        packetIndex % 2 == 0 &&
                                        attemptNumber == 1

                            !injectFailure
                        }
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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

        for (index in 1..4) {

            simulator.addNode(
                nodeId =
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        val failureTime =
            15L

        simulationEngine.schedule(
            failureTime
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

            instrumentation.onTopologyEvent(
                TopologyEventRecord(
                    config.runId,
                    failureTime,
                    "N2",
                    "N4",
                    TopologyEventType.LINK_DOWN,
                    oldWeight,
                    null
                )
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
                        messageId =
                            "${config.runId}-MSG-$packetIndex",

                        sourceId =
                            "N0",

                        destinationId =
                            "N4",

                        createdAt =
                            generationTime,

                        ttl =
                            config.traffic.packetTtl,

                        payload =
                            createPayload(
                                config.traffic.payloadBytes
                            )
                    )

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // S01 — SEEDED STOCHASTIC RETRY
    // =====================================================

    fun runSeededRetryScenario(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 2) {
            "Seeded retry scenario requires at least 2 nodes."
        }

        /*
         * SINGLE SOURCE OF TRUTH:
         *
         * The simulation uses the exact probability stored
         * inside ExperimentConfig.
         */
        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "S01 requires link.successProbability."
            }

        require(successProbability in 0.0..1.0) {
            "successProbability must be between 0.0 and 1.0."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            createLineGraph(
                nodeCount =
                    config.scenario.nodeCount
            )

        val sourceId =
            nodeIdForIndex(0)

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
            )

        /*
         * Every stochastic decision for the run comes
         * from the configured seed.
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

                        if (
                            !graph.containsEdge(
                                fromNodeId,
                                toNodeId
                            )
                        ) {

                            false

                        } else {

                            random.nextDouble() <
                                    successProbability
                        }
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
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

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // S02 — SEEDED STOCHASTIC TOPOLOGY
    // =====================================================

    fun runSeededTopologyScenario(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount == 5) {
            "Seeded topology scenario requires exactly 5 nodes."
        }

        /*
         * SINGLE SOURCE OF TRUTH:
         *
         * These values come directly from ScenarioConfig.
         */
        val failureProbability =
            requireNotNull(
                config.scenario.topologyFailureProbability
            ) {
                "S02 requires scenario.topologyFailureProbability."
            }

        require(failureProbability in 0.0..1.0) {
            "topologyFailureProbability must be between 0.0 and 1.0."
        }

        val topologyDecisionTimes =
            config.scenario.topologyDecisionTimes

        require(topologyDecisionTimes.isNotEmpty()) {
            "S02 requires topologyDecisionTimes."
        }

        require(
            topologyDecisionTimes.all {
                it >= 0L
            }
        ) {
            "topologyDecisionTimes cannot contain negative values."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        /*
         *       N2
         *      /  \
         * N0--N1   N4
         *      \  /
         *       N3
         */
        val graph =
            Graph()

        repeat(5) { index ->

            val id =
                nodeIdForIndex(index)

            graph.addNode(
                Node(
                    nodeId = id,
                    displayName = id
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

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
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
                        )
                    },

                runId =
                    config.runId,

                instrumentation =
                    instrumentation
            )

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

        for (index in 1..4) {

            simulator.addNode(
                nodeId =
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        val random =
            Random(
                config.seed
            )

        /*
         * IMPORTANT:
         *
         * No hardcoded topology schedule here anymore.
         *
         * The schedule comes directly from:
         *
         * config.scenario.topologyDecisionTimes
         */
        topologyDecisionTimes.forEach { eventTime ->

            simulationEngine.schedule(
                eventTime
            ) {

                val shouldFail =
                    random.nextDouble() <
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

                    instrumentation.onTopologyEvent(
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
                                TopologyEventType.LINK_DOWN,

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

                    instrumentation.onTopologyEvent(
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
                                TopologyEventType.LINK_UP,

                            oldWeight =
                                null,

                            newWeight =
                                1
                        )
                    )
                }
            }
        }

        repeat(
            config.traffic.packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex.toLong() *
                        config.traffic.packetInterval

            simulationEngine.schedule(
                generationTime
            ) {

                val packet =
                    Packet(
                        messageId =
                            "${config.runId}-MSG-$packetIndex",

                        sourceId =
                            "N0",

                        destinationId =
                            "N4",

                        createdAt =
                            generationTime,

                        ttl =
                            config.traffic.packetTtl,

                        payload =
                            createPayload(
                                config.traffic.payloadBytes
                            )
                    )

                simulator.send(
                    packet = packet,
                    routeProvider = routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }
    fun runSeededCongestionScenario(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 2)

        requireNotNull(
            config.traffic.burstProbability
        ) {
            "S03 requires burstProbability."
        }

        requireNotNull(
            config.traffic.burstSize
        ) {
            "S03 requires burstSize."
        }

        requireNotNull(
            config.traffic.burstSpacing
        ) {
            "S03 requires burstSpacing."
        }

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                runId = config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        val graph =
            createLineGraph(
                config.scenario.nodeCount
            )

        val sourceId =
            "N0"

        val destinationId =
            nodeIdForIndex(
                config.scenario.nodeCount - 1
            )

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
            )

        /*
         * S03 intentionally uses perfect links.
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
                    instrumentation
            )

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
                    nodeIdForIndex(index),

                queueCapacity =
                    config.scenario.queueCapacity,

                serviceTime =
                    config.scenario.serviceTime
            )
        }

        val trafficRandom =
            Random(
                config.seed + 3_000_000L
            )

        scheduleSeededTraffic(
            config = config,
            simulationEngine = simulationEngine,
            random = trafficRandom
        ) { packetIndex, generationTime ->

            simulator.send(
                packet =
                    Packet(
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
                    ),

                routeProvider =
                    routeProvider
            )
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }
    fun runSeededReliabilityTopologyScenario(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0")

        require(config.scenario.nodeCount == 5) {
            "S04 requires exactly 5 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            ) {
                "S04 requires link.successProbability."
            }

        val failureProbability =
            requireNotNull(
                config.scenario.topologyFailureProbability
            ) {
                "S04 requires topologyFailureProbability."
            }

        val topologyDecisionTimes =
            config.scenario.topologyDecisionTimes

        require(
            topologyDecisionTimes.isNotEmpty()
        )

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder
            )

        val graph =
            Graph()

        repeat(5) { index ->

            val id =
                nodeIdForIndex(index)

            graph.addNode(
                Node(
                    id,
                    id
                )
            )
        }

        /*
         * Dual-path topology.
         */
        graph.addEdge("N0", "N1", 1)

        graph.addEdge("N1", "N2", 1)
        graph.addEdge("N2", "N4", 1)

        graph.addEdge("N1", "N3", 1)
        graph.addEdge("N3", "N4", 1)

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
            )

        /*
         * Independent random stream for physical-link
         * attempt success.
         */
        val linkRandom =
            Random(
                config.seed + 4_000_000L
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
                    instrumentation
            )

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

        for (index in 1..4) {

            simulator.addNode(
                nodeId = "N$index",
                queueCapacity =
                    config.scenario.queueCapacity,
                serviceTime =
                    config.scenario.serviceTime
            )
        }

        /*
         * Independent topology RNG.
         */
        val topologyRandom =
            Random(
                config.seed + 4_100_000L
            )

        topologyDecisionTimes.forEach { eventTime ->

            simulationEngine.schedule(
                eventTime
            ) {

                val shouldFail =
                    topologyRandom.nextDouble() <
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

                    instrumentation.onTopologyEvent(
                        TopologyEventRecord(
                            config.runId,
                            eventTime,
                            "N2",
                            "N4",
                            TopologyEventType.LINK_DOWN,
                            oldWeight,
                            null
                        )
                    )

                } else if (
                    !shouldFail &&
                    !currentlyUp
                ) {

                    graph.addEdge(
                        "N2",
                        "N4",
                        1
                    )

                    instrumentation.onTopologyEvent(
                        TopologyEventRecord(
                            config.runId,
                            eventTime,
                            "N2",
                            "N4",
                            TopologyEventType.LINK_UP,
                            null,
                            1
                        )
                    )
                }
            }
        }

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
                        Packet(
                            messageId =
                                "${config.runId}-MSG-$packetIndex",
                            sourceId =
                                "N0",
                            destinationId =
                                "N4",
                            createdAt =
                                generationTime,
                            ttl =
                                config.traffic.packetTtl,
                            payload =
                                createPayload(
                                    config.traffic.payloadBytes
                                )
                        ),
                    routeProvider =
                        routeProvider
                )
            }
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }
    fun runSeededCombinedScenario(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0")

        require(config.scenario.nodeCount == 5) {
            "S05 requires exactly 5 nodes."
        }

        val successProbability =
            requireNotNull(
                config.link.successProbability
            )

        val failureProbability =
            requireNotNull(
                config.scenario.topologyFailureProbability
            )

        val topologyDecisionTimes =
            config.scenario.topologyDecisionTimes

        require(
            topologyDecisionTimes.isNotEmpty()
        )

        requireNotNull(
            config.traffic.burstProbability
        )

        val simulationEngine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(
                config.runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder
            )

        val graph =
            Graph()

        repeat(5) { index ->

            val id =
                nodeIdForIndex(index)

            graph.addNode(
                Node(
                    id,
                    id
                )
            )
        }

        graph.addEdge("N0", "N1", 1)

        graph.addEdge("N1", "N2", 1)
        graph.addEdge("N2", "N4", 1)

        graph.addEdge("N1", "N3", 1)
        graph.addEdge("N3", "N4", 1)

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = config.runId,
                instrumentation = instrumentation,
                timeProvider = {
                    simulationEngine.currentTime
                }
            )

        /*
         * Separate stochastic streams keep topology,
         * link reliability and traffic independent.
         */
        val linkRandom =
            Random(
                config.seed + 5_000_000L
            )

        val topologyRandom =
            Random(
                config.seed + 5_100_000L
            )

        val trafficRandom =
            Random(
                config.seed + 5_200_000L
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
                    instrumentation
            )

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

        for (index in 1..4) {

            simulator.addNode(
                nodeId = "N$index",
                queueCapacity =
                    config.scenario.queueCapacity,
                serviceTime =
                    config.scenario.serviceTime
            )
        }

        topologyDecisionTimes.forEach { eventTime ->

            simulationEngine.schedule(
                eventTime
            ) {

                val shouldFail =
                    topologyRandom.nextDouble() <
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

                    instrumentation.onTopologyEvent(
                        TopologyEventRecord(
                            config.runId,
                            eventTime,
                            "N2",
                            "N4",
                            TopologyEventType.LINK_DOWN,
                            oldWeight,
                            null
                        )
                    )

                } else if (
                    !shouldFail &&
                    !currentlyUp
                ) {

                    graph.addEdge(
                        "N2",
                        "N4",
                        1
                    )

                    instrumentation.onTopologyEvent(
                        TopologyEventRecord(
                            config.runId,
                            eventTime,
                            "N2",
                            "N4",
                            TopologyEventType.LINK_UP,
                            null,
                            1
                        )
                    )
                }
            }
        }

        scheduleSeededTraffic(
            config = config,
            simulationEngine = simulationEngine,
            random = trafficRandom
        ) { packetIndex, generationTime ->

            simulator.send(
                packet =
                    Packet(
                        messageId =
                            "${config.runId}-MSG-$packetIndex",
                        sourceId =
                            "N0",
                        destinationId =
                            "N4",
                        createdAt =
                            generationTime,
                        ttl =
                            config.traffic.packetTtl,
                        payload =
                            createPayload(
                                config.traffic.payloadBytes
                            )
                    ),
                routeProvider =
                    routeProvider
            )
        }

        simulationEngine.run()

        return buildRunOutput(
            config = config,
            recorder = recorder,
            routeProvider = routeProvider
        )
    }

    // =====================================================
    // TOPOLOGY HELPERS
    // =====================================================

    private fun createLineGraph(
        nodeCount: Int
    ): Graph {

        val graph =
            Graph()

        repeat(nodeCount) { index ->

            val nodeId =
                nodeIdForIndex(index)

            graph.addNode(
                Node(
                    nodeId = nodeId,
                    displayName = nodeId
                )
            )
        }

        for (
        index in 0 until
                nodeCount - 1
        ) {

            graph.addEdge(
                from =
                    nodeIdForIndex(index),

                to =
                    nodeIdForIndex(index + 1),

                weight =
                    1
            )
        }

        return graph
    }

    private fun nodeIdForIndex(
        index: Int
    ): String {

        return "N$index"
    }

    // =====================================================
    // PAYLOAD
    // =====================================================

    private fun createPayload(
        payloadBytes: Int
    ): String {

        return "X".repeat(
            payloadBytes
        )
    }

    // =====================================================
    // PACKET ACCOUNTING VALIDATION
    // =====================================================

    private fun validatePacketAccounting(
        config: ExperimentConfig,
        packets: List<PacketRecord>
    ) {

        val expected =
            config.traffic.packetCount

        require(
            packets.size == expected
        ) {
            "Invalid run ${config.runId}: " +
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
            "Invalid run ${config.runId}: " +
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
            "Invalid run ${config.runId}: " +
                    "delivered + dropped does not equal generated packets."
        }

        require(
            packets.none {
                it.delivered &&
                        it.dropped
            }
        ) {
            "Invalid run ${config.runId}: " +
                    "a packet cannot be both delivered and dropped."
        }
    }

    // =====================================================
    // CROSS-STREAM RECONCILIATION
    // =====================================================

    private fun validateCrossStreamReconciliation(
        config: ExperimentConfig,
        packets: List<PacketRecord>,
        transmissions: List<TransmissionRecord>,
        queueEvents: List<QueueEventRecord>,
        topologyEvents: List<TopologyEventRecord>,
        resourceSamples: List<ResourceSampleRecord>
    ) {

        require(
            packets.size ==
                    config.traffic.packetCount
        ) {
            "Cross-stream validation failed: " +
                    "terminal packet count does not match generated packet count."
        }

        val transmissionAttemptCount =
            transmissions.size.toLong()

        val resourcePhysicalAttempts =
            resourceSamples.sumOf {
                it.physicalAttempts
            }

        require(
            transmissionAttemptCount ==
                    resourcePhysicalAttempts
        ) {
            "Cross-stream validation failed: " +
                    "TransmissionRecord count=$transmissionAttemptCount " +
                    "but resource physicalAttempts=$resourcePhysicalAttempts."
        }

        val transmissionRetransmissions =
            transmissions.count {
                it.attemptNumber > 1
            }.toLong()

        val resourceRetransmissions =
            resourceSamples.sumOf {
                it.retransmissions
            }

        require(
            transmissionRetransmissions ==
                    resourceRetransmissions
        ) {
            "Cross-stream validation failed: " +
                    "transmission retransmissions=" +
                    "$transmissionRetransmissions " +
                    "but resource retransmissions=" +
                    "$resourceRetransmissions."
        }

        val successfulLogicalHopCount =
            transmissions
                .groupBy {
                    Pair(
                        it.messageId,
                        it.logicalHopIndex
                    )
                }
                .count { (_, attempts) ->

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
        ) {
            "Cross-stream validation failed: " +
                    "successful logical hops=" +
                    "$successfulLogicalHopCount " +
                    "but resource packetsTransmitted=" +
                    "$resourceSuccessfulTransmissions."
        }

        require(
            successfulLogicalHopCount ==
                    resourceSuccessfulReceives
        ) {
            "Cross-stream validation failed: " +
                    "successful logical hops=" +
                    "$successfulLogicalHopCount " +
                    "but resource packetsReceived=" +
                    "$resourceSuccessfulReceives."
        }

        val enqueueCount =
            queueEvents.count {
                it.eventType ==
                        com.example.peertopeer.simulation.experiment.record.QueueEventType.ENQUEUED
            }

        val dequeueCount =
            queueEvents.count {
                it.eventType ==
                        com.example.peertopeer.simulation.experiment.record.QueueEventType.DEQUEUED
            }

        require(
            enqueueCount ==
                    dequeueCount
        ) {
            "Cross-stream validation failed: " +
                    "queue ENQUEUED=$enqueueCount " +
                    "but DEQUEUED=$dequeueCount."
        }

        require(
            topologyEvents.all {
                it.runId ==
                        config.runId
            }
        ) {
            "Cross-stream validation failed: " +
                    "topology event belongs to another run."
        }

        require(
            resourceSamples.all {
                it.runId ==
                        config.runId
            }
        ) {
            "Cross-stream validation failed: " +
                    "resource sample belongs to another run."
        }

        require(
            resourceSamples.size ==
                    config.scenario.nodeCount
        ) {
            "Cross-stream validation failed: " +
                    "expected ${config.scenario.nodeCount} " +
                    "resource samples, found ${resourceSamples.size}."
        }

        require(
            resourceSamples
                .map {
                    it.nodeId
                }
                .distinct()
                .size ==
                    config.scenario.nodeCount
        ) {
            "Cross-stream validation failed: " +
                    "duplicate or missing node resource samples."
        }
    }

    // =====================================================
    // TRANSMISSION VALIDATION
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
            "Transmission record missing logicalHopIndex."
        }

        val groups =
            transmissions.groupBy {
                "${it.messageId}:" +
                        "${it.logicalHopIndex}"
            }

        for (
        (_, attempts)
        in groups
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
                "Invalid transmission attempt sequence: " +
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
        transmissions: List<TransmissionRecord>,
        queueEvents: List<QueueEventRecord>,
        sampleTime: Long
    ): List<ResourceSampleRecord> {

        /*
         * Queue records remain the canonical source
         * for queue behavior.
         *
         * The parameter remains here so the resource
         * derivation function has access to all raw
         * experiment streams if needed later.
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
                        nodeIdForIndex(index)
                    )
                }
            }

        return nodeIds
            .sorted()
            .map { nodeId ->

                val transmittedLogicalHops =
                    successfulLogicalHops.count {
                        it.fromNodeId == nodeId
                    }

                val receivedLogicalHops =
                    successfulLogicalHops.count {
                        it.toNodeId == nodeId
                    }

                val forwardedLogicalHops =
                    successfulLogicalHops.count { transmission ->

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
                        it.fromNodeId == nodeId
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
                        transmittedLogicalHops.toLong(),

                    packetsReceived =
                        receivedLogicalHops.toLong(),

                    packetsForwarded =
                        forwardedLogicalHops.toLong(),

                    physicalAttempts =
                        nodePhysicalAttempts.toLong(),

                    retransmissions =
                        nodeRetransmissions.toLong(),

                    queueOccupancy =
                        0,

                    routingCalculations =
                        0L
                )
            }
    }

    // =====================================================
    // BUILD FINAL RUN OUTPUT
    // =====================================================

    private fun buildRunOutput(
        config: ExperimentConfig,
        recorder: ExperimentRecorder,
        routeProvider: B0DynamicRouteProvider
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
            "B0 runner unexpectedly already contains resource samples."
        }

        val resourceSampleRecords =
            buildFinalResourceSamples(
                config = config,
                packets = packetRecords,
                transmissions = transmissionRecords,
                queueEvents = queueEventRecords,
                sampleTime =
                    packetRecords
                        .mapNotNull {
                            it.deliveredAt ?: it.droppedAt
                        }
                        .maxOrNull()
                        ?: 0L
            )

        validatePacketAccounting(
            config = config,
            packets = packetRecords
        )

        validateTransmissionAccounting(
            transmissions = transmissionRecords
        )

        validateCrossStreamReconciliation(
            config = config,
            packets = packetRecords,
            transmissions = transmissionRecords,
            queueEvents = queueEventRecords,
            topologyEvents = topologyEventRecords,
            resourceSamples = resourceSampleRecords
        )

        val summary =
            ExperimentAggregator.aggregate(
                config = config,
                packets = packetRecords,
                transmissions = transmissionRecords,
                routingEvents = routingEventRecords,
                topologyEvents = topologyEventRecords,
                queueEvents = queueEventRecords,
                resourceSamples = resourceSampleRecords,
                routingTelemetry = routeProvider.telemetry
            )

        return RunOutput(
            summary = summary,
            packets = packetRecords,
            transmissions = transmissionRecords,
            routingEvents = routingEventRecords,
            topologyEvents = topologyEventRecords,
            queueEvents = queueEventRecords,
            resourceSamples = resourceSampleRecords
        )
    }
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
            config.traffic.burstProbability

        val burstSize =
            config.traffic.burstSize

        val burstSpacing =
            config.traffic.burstSpacing

        /*
         * Non-bursty traffic remains exactly equivalent
         * to the existing fixed-interval generator.
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
                config.traffic.packetInterval
        }
    }

    // =====================================================
    // MESSAGE-ID HELPER
    // =====================================================

    private fun extractPacketIndex(
        messageId: String
    ): Int {

        val marker =
            "-MSG-"

        val markerIndex =
            messageId.lastIndexOf(
                marker
            )

        require(markerIndex >= 0) {
            "Cannot extract packet index from messageId: $messageId"
        }

        return messageId
            .substring(
                markerIndex +
                        marker.length
            )
            .toInt()
    }
}