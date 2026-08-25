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
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import com.example.peertopeer.simulation.experiment.result.ExperimentAggregator
import com.example.peertopeer.simulation.experiment.result.RunSummary
import com.example.peertopeer.simulation.experiment.record.TopologyEventType

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

    fun runHealthyLine(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 2) {
            "Healthy line requires at least 2 nodes."
        }

        // =================================================
        // 1. SIMULATION + RESEARCH RECORDING
        // =================================================

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

        // =================================================
        // 2. GRAPH
        // =================================================

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

        // =================================================
        // 3. B0 ROUTING
        // =================================================

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

        // =================================================
        // 4. LINK MODEL
        // =================================================

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

                        /*
                         * Healthy B0 reference condition:
                         *
                         * every graph edge succeeds.
                         */
                        graph.containsEdge(
                            fromNodeId,
                            toNodeId
                        )
                    },
                runId = config.runId,
                instrumentation = instrumentation
            )

        // =================================================
        // 5. NETWORK SIMULATOR
        // =================================================

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter,
                runId = config.runId,
                instrumentation = instrumentation
            )

        /*
         * Source is injected directly into the network,
         * so only receiving/forwarding nodes need
         * TimedNetworkNode instances.
         */
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

        // =================================================
        // 6. TRAFFIC GENERATION
        // =================================================

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

        // =================================================
        // 7. EXECUTE RUN
        // =================================================

        simulationEngine.run()

        // =================================================
        // 8. RAW RECORDS
        // =================================================

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


        // =================================================
        // 9. HARD RESEARCH VALIDATION
        // =================================================

        validatePacketAccounting(
            config = config,
            packets = packetRecords
        )

        validateTransmissionAccounting(
            transmissions =
                transmissionRecords
        )

        // =================================================
        // 10. AGGREGATION
        // =================================================

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

    fun runControlledRetryDegradation(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 5) {
            "Controlled retry degradation currently requires at least 5 nodes."
        }

        // =================================================
        // 1. SIMULATION + RECORDING
        // =================================================

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

        // =================================================
        // 2. GRAPH
        // =================================================

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

        // =================================================
        // 3. B0 ROUTING
        // =================================================

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

        // =================================================
        // 4. CONTROLLED DEGRADED LINK
        // =================================================

        /*
         * Only this link is degraded:
         *
         * N2 -> N3
         *
         * For every even-numbered packet:
         *
         * attempt 1 fails
         * attempt 2 succeeds
         *
         * Odd-numbered packets succeed immediately.
         *
         * The topology itself NEVER changes.
         */

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

        // =================================================
        // 5. NETWORK
        // =================================================

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

        // =================================================
        // 6. TRAFFIC
        // =================================================

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
                    packet =
                        packet,
                    routeProvider =
                        routeProvider
                )
            }
        }

        // =================================================
        // 7. RUN
        // =================================================

        simulationEngine.run()

        // =================================================
        // 8. RAW RECORDS
        // =================================================

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
        // =================================================
        // 9. VALIDATION
        // =================================================

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

        // =================================================
        // 10. SUMMARY
        // =================================================

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


    fun runControlledCongestion(
        config: ExperimentConfig
    ): RunOutput {

        require(config.protocol == "B0") {
            "B0ExperimentRunner only runs protocol B0."
        }

        require(config.scenario.nodeCount >= 2) {
            "Controlled congestion requires at least 2 nodes."
        }

        // =================================================
        // 1. SIMULATION + RECORDING
        // =================================================

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

        // =================================================
        // 2. STATIC LINE GRAPH
        // =================================================

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

        // =================================================
        // 3. B0 ROUTING
        // =================================================

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

        // =================================================
        // 4. PERFECTLY HEALTHY LINKS
        // =================================================

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

        // =================================================
        // 5. NETWORK
        // =================================================

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
         * Source N0 sends directly into the network.
         *
         * All downstream nodes have bounded queues.
         */
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

        // =================================================
        // 6. HIGH-LOAD TRAFFIC
        // =================================================

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
                    packet =
                        packet,
                    routeProvider =
                        routeProvider
                )
            }
        }

        // =================================================
        // 7. RUN
        // =================================================

        simulationEngine.run()

        // =================================================
        // 8. RAW RECORDS
        // =================================================

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

        val resourceSampleRecords =
            recorder.getResourceSampleRecords()

        // =================================================
        // 9. VALIDATE ACCOUNTING
        // =================================================

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

        // =================================================
        // 10. AGGREGATE
        // =================================================

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

        // -------------------------------------------------
        // Topology
        //
        //       N2
        //      /  \
        // N0--N1   N4
        //      \  /
        //       N3
        //
        // Initial preferred route:
        // N0 -> N1 -> N2 -> N4
        //
        // Backup:
        // N0 -> N1 -> N3 -> N4
        // -------------------------------------------------

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

        /*
         * Deliberately chosen between packets for the
         * standard interval=10 configuration.
         *
         * This isolates rerouting from an in-flight
         * transmission failure.
         */
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

        // -------------------------------------------------
        // N0 -- N1 -- N2 -- N3
        //
        // N2-N3 disappears at t=20.
        // It returns at t=50.
        // -------------------------------------------------

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

        // -------------------------------------------------
        // Same dual-path topology as E04.
        //
        // Stress mechanisms:
        //
        // 1. High offered load
        // 2. Controlled retries on N1 -> N2
        // 3. N2 -> N4 topology failure
        //
        // After failure the alternate N1 -> N3 -> N4
        // route remains available.
        // -------------------------------------------------

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

                        /*
                         * A physically absent graph edge
                         * cannot succeed.
                         */
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

                            /*
                             * Before rerouting, N1->N2 is
                             * deliberately degraded.
                             *
                             * Even-index packets fail on
                             * their first attempt only.
                             */
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
    // TOPOLOGY
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


    /*
     * Gives readable IDs:
     *
     * N0
     * N1
     * N2
     * ...
     *
     * Better for scalable experiments than relying
     * on A, B, C only.
     */
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

        /*
         * Current Packet stores payload as String.
         *
         * For simulation, this gives us a deterministic
         * payload of approximately the requested byte
         * count for ASCII characters.
         *
         * Later physical BLE experiments must record
         * actual encoded byte length.
         */
        return "X".repeat(
            payloadBytes
        )
    }


    // =====================================================
    // RESEARCH VALIDATION
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


    private fun validateTransmissionAccounting(
        transmissions:
        List<TransmissionRecord>
    ) {

        /*
         * Every recorded physical transmission must
         * belong to a logical hop.
         *
         * We fixed this before building the runner.
         */
        require(
            transmissions.all {
                it.logicalHopIndex != null
            }
        ) {
            "Transmission record missing logicalHopIndex."
        }

        /*
         * Within one logical hop:
         *
         * attempt numbers must begin at 1 and proceed
         * without gaps.
         */
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

    private fun buildFinalResourceSamples(
        config: ExperimentConfig,
        packets: List<PacketRecord>,
        transmissions: List<TransmissionRecord>,
        queueEvents: List<QueueEventRecord>,
        sampleTime: Long
    ): List<ResourceSampleRecord> {

        val packetByMessageId =
            packets.associateBy {
                it.messageId
            }

        /*
         * A logical hop may contain multiple physical
         * attempts.
         *
         * Group all attempts belonging to the same
         * message + logical hop.
         */
        val logicalHopGroups =
            transmissions.groupBy {
                Pair(
                    it.messageId,
                    it.logicalHopIndex
                )
            }

        /*
         * A logical hop counts as successfully transmitted
         * only if one of its physical attempts succeeded.
         */
        val successfulLogicalHops =
            logicalHopGroups.values
                .mapNotNull { attempts ->

                    val successfulAttempt =
                        attempts.firstOrNull {
                            it.success
                        }
                            ?: return@mapNotNull null

                    successfulAttempt
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

                // -----------------------------------------
                // SUCCESSFUL LOGICAL TRANSMISSIONS
                // -----------------------------------------

                val transmittedLogicalHops =
                    successfulLogicalHops.count {
                        it.fromNodeId == nodeId
                    }

                // -----------------------------------------
                // SUCCESSFUL LOGICAL RECEIVES
                // -----------------------------------------

                val receivedLogicalHops =
                    successfulLogicalHops.count {
                        it.toNodeId == nodeId
                    }

                // -----------------------------------------
                // FORWARDED PACKETS
                //
                // A relay transmission is an outgoing
                // successful logical hop made by a node
                // that was not the original packet source.
                // -----------------------------------------

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

                // -----------------------------------------
                // PHYSICAL ATTEMPTS
                // -----------------------------------------

                val nodePhysicalAttempts =
                    transmissions.count {
                        it.fromNodeId == nodeId
                    }

                // -----------------------------------------
                // RETRANSMISSIONS
                //
                // Attempt number 1 is the original attempt.
                // Anything > 1 is a retransmission.
                // -----------------------------------------

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

                    /*
                     * Final queue occupancy is not used as
                     * the congestion measure.
                     *
                     * QueueEventRecord remains the canonical
                     * source for occupancy and waiting-time
                     * behavior.
                     */
                    queueOccupancy =
                        0,

                    /*
                     * Current B0 telemetry is run-level.
                     * We do not fabricate per-node routing
                     * calculation attribution.
                     */
                    routingCalculations =
                        0L
                )
            }
    }
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

        // =================================================
        // DERIVED RESOURCE PROXY SAMPLES
        // =================================================

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

        // =================================================
        // VALIDATION
        // =================================================

        validatePacketAccounting(
            config = config,
            packets = packetRecords
        )

        validateTransmissionAccounting(
            transmissions = transmissionRecords
        )

        // =================================================
        // AGGREGATION
        // =================================================

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
