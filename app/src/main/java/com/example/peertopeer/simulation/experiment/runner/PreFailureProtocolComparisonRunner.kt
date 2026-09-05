package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleRegimeEventRecord
import com.example.peertopeer.routing.carble.CarbleRouteEvaluator
import com.example.peertopeer.routing.carble.CarbleTelemetrySnapshot
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeTelemetrySnapshot
import com.example.peertopeer.routing.mm.MultiMetricObservationTracker
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.B0DynamicRouteProvider
import com.example.peertopeer.simulation.CarbleRouteProvider
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import com.example.peertopeer.simulation.TwoRegimeRouteProvider
import com.example.peertopeer.simulation.experiment.environment.PairedLinkOutcomeOracle
import com.example.peertopeer.simulation.experiment.environment.PhysicalLinkEventScheduler
import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.instrumentation.MMInstrumentation
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import java.io.File

/**
 * Final paired pre-failure comparison.
 *
 * Conditions:
 * PF-A  -> M1 / pre-failure detection
 * PF-B1 -> M2 / sequential backup recovery
 * PF-B2 -> M3 / delayed backup recovery
 *
 * Protocols:
 * B0, MM, 2RH, CARBLE
 *
 * The calibrated network conditions are frozen here.
 * This runner does not change CARBLE thresholds, weights,
 * copy budget, or controller logic.
 */
class PreFailureProtocolComparisonRunner {

    enum class Condition {
        PF_A,
        PF_B1,
        PF_B2,
        PF_C
    }

    enum class Protocol {
        B0,
        MM,
        TWO_RH,
        CARBLE
    }

    data class ComparisonResult(
        val condition: Condition,
        val protocol: Protocol,
        val seed: Long,
        val runId: String,
        val generated: Int,
        val delivered: Int,
        val dropped: Int,
        val pdr: Double,
        val meanLatency: Double,
        val medianLatency: Double,
        val physicalAttempts: Long,
        val attemptsPerGenerated: Double,
        val attemptsPerDelivered: Double,
        val retransmissions: Long,

        // 2RH-only mechanism telemetry
        val twoRhHighDecisions: Long = 0L,
        val twoRhLowDecisions: Long = 0L,

        // CARBLE-only mechanism telemetry
        val highDecisions: Long = 0L,
        val mediumDecisions: Long = 0L,
        val lowDecisions: Long = 0L,
        val m1Decisions: Long = 0L,
        val m2Decisions: Long = 0L,
        val m3Decisions: Long = 0L,
        val backupPrepared: Long = 0L,
        val backupActivations: Long = 0L,
        val backupSuccesses: Long = 0L,
        val backupFailures: Long = 0L,
        val duplicateSuppressions: Long = 0L,
        val carryDecisions: Long = 0L,
        val probeDecisions: Long = 0L,
        val probeSuccesses: Long = 0L,
        val probeFailures: Long = 0L,
        val fallbackDrops: Long = 0L,
        val mediumToLowEscalations: Long = 0L,
        val minCurrentHopConfidence: Double? = null
    )

    private data class MMContext(
        val store: MultiMetricStateStore,
        val tracker: MultiMetricObservationTracker,
        val instrumentation: MMInstrumentation,
        val mm: MMRouteProvider
    )

    fun run(
        condition: Condition,
        protocol: Protocol,
        seed: Long
    ): ComparisonResult {
        return when (condition) {
            Condition.PF_A -> runPfA(protocol, seed)
            Condition.PF_B1 -> runPfB(
                protocol = protocol,
                seed = seed,
                condition = Condition.PF_B1,
                scenarioSalt = "PF_B1",
                runPrefix = "PFB1",
                physicalEvents = emptyList()
            )
            Condition.PF_B2 -> runPfB(
                protocol = protocol,
                seed = seed,
                condition = Condition.PF_B2,
                scenarioSalt = "PF_B2",
                runPrefix = "PFB2",
                physicalEvents = pfB2CalibrationEvents()
            )
            Condition.PF_C -> runPfB(
                protocol = protocol,
                seed = seed,
                condition = Condition.PF_C,
                scenarioSalt = "PF_C",
                runPrefix = "PFC",
                physicalEvents = pfCCalibrationEvents()
            )
        }
    }

    // =====================================================
    // PF-A — M1
    // =====================================================

    private fun runPfA(
        protocol: Protocol,
        seed: Long
    ): ComparisonResult {

        // Frozen PF-A v2 line condition.
        val probabilities =
            listOf(
                0.95,
                0.85,
                0.75,
                0.65,
                0.55,
                0.45,
                0.35
            )

        val phaseDuration = 150L
        val finalTime = probabilities.size * phaseDuration
        val runId = "PFA-${protocol.name}-SEED-$seed"
        val engine = SimulationEngine()
        val recorder = ExperimentRecorder(runId)
        val graph = createLineGraph(4)

        val oracle =
            PairedLinkOutcomeOracle(
                seed = seed,
                experimentSalt = "PF_A"
            )

        fun probabilityAt(time: Long): Double {
            val index =
                (time / phaseDuration)
                    .toInt()
                    .coerceIn(0, probabilities.lastIndex)

            return probabilities[index]
        }

        return runOneProtocol(
            condition = Condition.PF_A,
            protocol = protocol,
            seed = seed,
            runId = runId,
            engine = engine,
            recorder = recorder,
            graph = graph,
            nodeCount = 4,
            sourceId = "N0",
            destinationId = "N3",
            finalTime = finalTime,
            packetInterval = 5L,
            packetTtl = 40,
            queueCapacity = 20,
            serviceTime = 1L,
            physicalEvents = emptyList(),
            attemptPolicy =
                TimedLinkAttemptPolicy {
                        from,
                        to,
                        messageId,
                        attemptNumber,
                        attemptTime ->

                    if (!graph.containsEdge(from, to)) {
                        false
                    } else {
                        val probability =
                            if (
                                undirectedMatch(
                                    from,
                                    to,
                                    "N1",
                                    "N2"
                                )
                            ) {
                                probabilityAt(attemptTime)
                            } else {
                                0.95
                            }

                        oracle.shouldSucceed(
                            fromNodeId = from,
                            toNodeId = to,
                            messageId = messageId,
                            attemptNumber = attemptNumber,
                            attemptTime = attemptTime,
                            successProbability = probability
                        )
                    }
                }
        )
    }

    // =====================================================
    // PF-B1 / PF-B2 / PF-C — M2 / M3 / LOW
    // =====================================================

    private fun runPfB(
        protocol: Protocol,
        seed: Long,
        condition: Condition,
        scenarioSalt: String,
        runPrefix: String,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>
    ): ComparisonResult {

        val probabilities =
            listOf(
                0.90,
                0.75,
                0.60,
                0.45,
                0.30,
                0.15,
                0.05
            )

        val phaseDuration = 150L
        val finalTime = probabilities.size * phaseDuration

        require(
            condition == Condition.PF_B1 ||
                    condition == Condition.PF_B2 ||
                    condition == Condition.PF_C
        )

        val runId = "$runPrefix-${protocol.name}-SEED-$seed"
        val engine = SimulationEngine()
        val recorder = ExperimentRecorder(runId)
        val graph = createDualPathGraph()

        val oracle =
            PairedLinkOutcomeOracle(
                seed = seed,
                experimentSalt = scenarioSalt
            )

        fun probabilityAt(time: Long): Double {
            val index =
                (time / phaseDuration)
                    .toInt()
                    .coerceIn(0, probabilities.lastIndex)

            return probabilities[index]
        }

        return runOneProtocol(
            condition = condition,
            protocol = protocol,
            seed = seed,
            runId = runId,
            engine = engine,
            recorder = recorder,
            graph = graph,
            nodeCount = 5,
            sourceId = "N0",
            destinationId = "N4",
            finalTime = finalTime,
            packetInterval = 5L,
            packetTtl = 80,
            queueCapacity = 20,
            serviceTime = 1L,
            physicalEvents = physicalEvents,
            attemptPolicy =
                TimedLinkAttemptPolicy {
                        from,
                        to,
                        messageId,
                        attemptNumber,
                        attemptTime ->

                    if (!graph.containsEdge(from, to)) {
                        false
                    } else {
                        val probability =
                            when {
                                undirectedMatch(
                                    from,
                                    to,
                                    "N1",
                                    "N2"
                                ) ||
                                        undirectedMatch(
                                            from,
                                            to,
                                            "N1",
                                            "N3"
                                        ) ->
                                    probabilityAt(attemptTime)

                                undirectedMatch(
                                    from,
                                    to,
                                    "N2",
                                    "N4"
                                ) ||
                                        undirectedMatch(
                                            from,
                                            to,
                                            "N3",
                                            "N4"
                                        ) ->
                                    0.97

                                else ->
                                    0.99
                            }

                        oracle.shouldSucceed(
                            fromNodeId = from,
                            toNodeId = to,
                            messageId = messageId,
                            attemptNumber = attemptNumber,
                            attemptTime = attemptTime,
                            successProbability = probability
                        )
                    }
                }
        )
    }

    // =====================================================
    // PROTOCOL DISPATCH
    // =====================================================

    private fun runOneProtocol(
        condition: Condition,
        protocol: Protocol,
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        nodeCount: Int,
        sourceId: String,
        destinationId: String,
        finalTime: Long,
        packetInterval: Long,
        packetTtl: Int,
        queueCapacity: Int,
        serviceTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): ComparisonResult {

        return when (protocol) {
            Protocol.B0 ->
                runB0(
                    condition,
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    nodeCount,
                    sourceId,
                    destinationId,
                    finalTime,
                    packetInterval,
                    packetTtl,
                    queueCapacity,
                    serviceTime,
                    physicalEvents,
                    attemptPolicy
                )

            Protocol.MM ->
                runMM(
                    condition,
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    nodeCount,
                    sourceId,
                    destinationId,
                    finalTime,
                    packetInterval,
                    packetTtl,
                    queueCapacity,
                    serviceTime,
                    physicalEvents,
                    attemptPolicy
                )

            Protocol.TWO_RH ->
                runTwoRh(
                    condition,
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    nodeCount,
                    sourceId,
                    destinationId,
                    finalTime,
                    packetInterval,
                    packetTtl,
                    queueCapacity,
                    serviceTime,
                    physicalEvents,
                    attemptPolicy
                )

            Protocol.CARBLE ->
                runCarble(
                    condition,
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    nodeCount,
                    sourceId,
                    destinationId,
                    finalTime,
                    packetInterval,
                    packetTtl,
                    queueCapacity,
                    serviceTime,
                    physicalEvents,
                    attemptPolicy
                )
        }
    }

    // =====================================================
    // B0
    // =====================================================

    private fun runB0(
        condition: Condition,
        protocol: Protocol,
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        nodeCount: Int,
        sourceId: String,
        destinationId: String,
        finalTime: Long,
        packetInterval: Long,
        packetTtl: Int,
        queueCapacity: Int,
        serviceTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): ComparisonResult {

        val instrumentation =
            RecorderInstrumentation(recorder)

        val provider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine(),
                runId = runId,
                instrumentation = instrumentation,
                timeProvider = {
                    engine.currentTime
                }
            )

        PhysicalLinkEventScheduler.install(
            engine = engine,
            graph = graph,
            instrumentation = instrumentation,
            runId = runId,
            events = physicalEvents
        )

        val simulator =
            createSimulator(
                engine,
                runId,
                instrumentation,
                nodeCount,
                queueCapacity,
                serviceTime,
                attemptPolicy
            )

        scheduleTraffic(
            engine,
            simulator,
            sourceId,
            destinationId,
            finalTime,
            packetInterval,
            packetTtl,
            runId
        ) { packet ->
            simulator.send(
                packet = packet,
                routeProvider = provider
            )
        }

        engine.run()

        return buildResult(
            condition,
            protocol,
            seed,
            runId,
            recorder
        )
    }

    // =====================================================
    // MM
    // =====================================================

    private fun runMM(
        condition: Condition,
        protocol: Protocol,
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        nodeCount: Int,
        sourceId: String,
        destinationId: String,
        finalTime: Long,
        packetInterval: Long,
        packetTtl: Int,
        queueCapacity: Int,
        serviceTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): ComparisonResult {

        val context =
            createMMContext(
                graph,
                engine,
                recorder,
                runId,
                nodeCount,
                queueCapacity
            )

        PhysicalLinkEventScheduler.install(
            engine = engine,
            graph = graph,
            instrumentation = context.instrumentation,
            runId = runId,
            events = physicalEvents
        )

        val simulator =
            createSimulator(
                engine,
                runId,
                context.instrumentation,
                nodeCount,
                queueCapacity,
                serviceTime,
                attemptPolicy
            )

        scheduleTraffic(
            engine,
            simulator,
            sourceId,
            destinationId,
            finalTime,
            packetInterval,
            packetTtl,
            runId
        ) { packet ->
            simulator.send(
                packet = packet,
                routeProvider = context.mm
            )
        }

        engine.run()

        return buildResult(
            condition,
            protocol,
            seed,
            runId,
            recorder
        )
    }

    // =====================================================
    // 2RH
    // =====================================================

    private fun runTwoRh(
        condition: Condition,
        protocol: Protocol,
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        nodeCount: Int,
        sourceId: String,
        destinationId: String,
        finalTime: Long,
        packetInterval: Long,
        packetTtl: Int,
        queueCapacity: Int,
        serviceTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): ComparisonResult {

        val context =
            createMMContext(
                graph,
                engine,
                recorder,
                runId,
                nodeCount,
                queueCapacity
            )

        PhysicalLinkEventScheduler.install(
            engine = engine,
            graph = graph,
            instrumentation = context.instrumentation,
            runId = runId,
            events = physicalEvents
        )

        val provider =
            TwoRegimeRouteProvider(
                mmRouteProvider = context.mm,
                routeEvaluator =
                    TwoRegimeRouteEvaluator(
                        stateStore = context.store
                    ),
                fallbackPolicy =
                    TwoRegimeFallbackPolicy(
                        maxReevaluations = 3,
                        reevaluationDelay = 5L
                    )
            )

        val simulator =
            createSimulator(
                engine,
                runId,
                context.instrumentation,
                nodeCount,
                queueCapacity,
                serviceTime,
                attemptPolicy
            )

        scheduleTraffic(
            engine,
            simulator,
            sourceId,
            destinationId,
            finalTime,
            packetInterval,
            packetTtl,
            runId
        ) { packet ->
            simulator.send(
                packet = packet,
                routeProvider = provider
            )
        }

        engine.run()

        return buildResult(
            condition = condition,
            protocol = protocol,
            seed = seed,
            runId = runId,
            recorder = recorder,
            twoRh =
                provider.adaptationTelemetry.snapshot()
        )
    }

    // =====================================================
    // CARBLE
    // =====================================================

    private fun runCarble(
        condition: Condition,
        protocol: Protocol,
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        nodeCount: Int,
        sourceId: String,
        destinationId: String,
        finalTime: Long,
        packetInterval: Long,
        packetTtl: Int,
        queueCapacity: Int,
        serviceTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): ComparisonResult {

        val context =
            createMMContext(
                graph,
                engine,
                recorder,
                runId,
                nodeCount,
                queueCapacity
            )

        PhysicalLinkEventScheduler.install(
            engine = engine,
            graph = graph,
            instrumentation = context.instrumentation,
            runId = runId,
            events = physicalEvents
        )

        val provider =
            CarbleRouteProvider(
                mmRouteProvider = context.mm,
                routeEvaluator =
                    CarbleRouteEvaluator(
                        context.store
                    ),
                candidateFactory =
                    CarbleBackupCandidateFactory(
                        graph,
                        context.store
                    ),
                backupSelector =
                    CarbleBackupSelector(),
                fallbackPolicy =
                    TwoRegimeFallbackPolicy(
                        maxReevaluations = 3,
                        reevaluationDelay = 5L
                    ),
                retryDelay = 1L,
                runId = runId,
                timeProvider = {
                    engine.currentTime
                }
            )

        val simulator =
            createSimulator(
                engine,
                runId,
                context.instrumentation,
                nodeCount,
                queueCapacity,
                serviceTime,
                attemptPolicy
            )

        scheduleTraffic(
            engine,
            simulator,
            sourceId,
            destinationId,
            finalTime,
            packetInterval,
            packetTtl,
            runId
        ) { packet ->
            simulator.send(
                packet = packet,
                routeProvider = provider
            )
        }

        engine.run()

        return buildResult(
            condition = condition,
            protocol = protocol,
            seed = seed,
            runId = runId,
            recorder = recorder,
            carble =
                provider.adaptationTelemetry.snapshot(),
            regimeEvents =
                provider.getRegimeEvents()
        )
    }

    // =====================================================
    // SHARED MM CONTEXT
    // =====================================================

    private fun createMMContext(
        graph: Graph,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        runId: String,
        nodeCount: Int,
        queueCapacity: Int
    ): MMContext {

        val store =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = store,
                reliabilityWindowSize = 20,
                delayWindowSize = 20,
                delayReference = 10.0,
                instabilityReference = 5
            )

        graph.getEdges()
            .forEach { edge ->
                tracker.registerEdge(
                    fromNodeId = edge.from,
                    toNodeId = edge.to,
                    queueCapacity = queueCapacity
                )

                tracker.registerEdge(
                    fromNodeId = edge.to,
                    toNodeId = edge.from,
                    queueCapacity = queueCapacity
                )
            }

        val instrumentation =
            MMInstrumentation(
                delegate =
                    RecorderInstrumentation(
                        recorder
                    ),
                observationTracker = tracker,
                queueCapacityByNode =
                    buildMap {
                        repeat(nodeCount) { index ->
                            put(
                                "N$index",
                                queueCapacity
                            )
                        }
                    },
                retryDelay = 1L
            )

        val mm =
            MMRouteProvider(
                graph = graph,
                stateStore = store,
                runId = runId,
                instrumentation = instrumentation,
                timeProvider = {
                    engine.currentTime
                },
                hysteresisFraction = 0.05
            )

        return MMContext(
            store,
            tracker,
            instrumentation,
            mm
        )
    }

    // =====================================================
    // SIMULATOR + TRAFFIC
    // =====================================================

    private fun createSimulator(
        engine: SimulationEngine,
        runId: String,
        instrumentation:
        ExperimentInstrumentation,
        nodeCount: Int,
        queueCapacity: Int,
        serviceTime: Long,
        attemptPolicy: TimedLinkAttemptPolicy
    ): TimedNetworkSimulator {

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = engine,
                maxAttempts = 3,
                delayPerAttempt = 1L,
                attemptPolicy = attemptPolicy,
                runId = runId,
                instrumentation = instrumentation
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = engine,
                eventDrivenLinkTransmitter = transmitter,
                runId = runId,
                instrumentation = instrumentation
            )

        // N0 is source and is injected directly.
        for (index in 1 until nodeCount) {
            simulator.addNode(
                nodeId = "N$index",
                queueCapacity = queueCapacity,
                serviceTime = serviceTime
            )
        }

        return simulator
    }

    private fun scheduleTraffic(
        engine: SimulationEngine,
        simulator: TimedNetworkSimulator,
        sourceId: String,
        destinationId: String,
        finalTime: Long,
        packetInterval: Long,
        packetTtl: Int,
        runId: String,
        sender: (Packet) -> Unit
    ) {

        var packetIndex = 0
        var opportunityTime = 0L

        while (opportunityTime < finalTime) {
            val generationTime = opportunityTime
            val currentPacketIndex = packetIndex++

            engine.schedule(generationTime) {
                sender(
                    Packet(
                        messageId =
                            "$runId-MSG-$currentPacketIndex",
                        sourceId = sourceId,
                        destinationId = destinationId,
                        createdAt = generationTime,
                        ttl = packetTtl,
                        payload = "X".repeat(32)
                    )
                )
            }

            opportunityTime += packetInterval
        }
    }

    // =====================================================
    // RESULT
    // =====================================================

    private fun buildResult(
        condition: Condition,
        protocol: Protocol,
        seed: Long,
        runId: String,
        recorder: ExperimentRecorder,
        twoRh:
        TwoRegimeTelemetrySnapshot? = null,
        carble:
        CarbleTelemetrySnapshot? = null,
        regimeEvents:
        List<CarbleRegimeEventRecord> =
            emptyList()
    ): ComparisonResult {

        val packets =
            recorder.getPacketRecords()

        val transmissions =
            recorder.getTransmissionRecords()

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
                    packets.size
        ) {
            "$runId terminal packet reconciliation failed."
        }

        val latencies =
            packets.mapNotNull {
                it.endToEndLatency
            }

        val minQ =
            regimeEvents
                .mapNotNull {
                    it.currentHopConfidence
                }
                .minOrNull()

        return ComparisonResult(
            condition = condition,
            protocol = protocol,
            seed = seed,
            runId = runId,
            generated = packets.size,
            delivered = delivered,
            dropped = dropped,
            pdr =
                if (packets.isEmpty()) {
                    0.0
                } else {
                    delivered.toDouble() /
                            packets.size.toDouble()
                },
            meanLatency =
                if (latencies.isEmpty()) {
                    0.0
                } else {
                    latencies.average()
                },
            medianLatency =
                medianOrZero(latencies),
            physicalAttempts =
                transmissions.size.toLong(),
            attemptsPerGenerated =
                if (packets.isEmpty()) {
                    0.0
                } else {
                    transmissions.size.toDouble() /
                            packets.size.toDouble()
                },
            attemptsPerDelivered =
                if (delivered == 0) {
                    Double.NaN
                } else {
                    transmissions.size.toDouble() /
                            delivered.toDouble()
                },
            retransmissions =
                transmissions.count {
                    it.attemptNumber > 1
                }.toLong(),

            twoRhHighDecisions =
                twoRh?.highDecisions ?: 0L,
            twoRhLowDecisions =
                twoRh?.lowDecisions ?: 0L,

            highDecisions =
                carble?.highDecisions ?: 0L,
            mediumDecisions =
                carble?.mediumDecisions ?: 0L,
            lowDecisions =
                carble?.lowDecisions ?: 0L,
            m1Decisions =
                carble?.m1Decisions ?: 0L,
            m2Decisions =
                carble?.m2Decisions ?: 0L,
            m3Decisions =
                carble?.m3Decisions ?: 0L,
            backupPrepared =
                carble?.backupPrepared ?: 0L,
            backupActivations =
                carble?.backupActivations ?: 0L,
            backupSuccesses =
                carble?.backupSuccesses ?: 0L,
            backupFailures =
                carble?.backupFailures ?: 0L,
            duplicateSuppressions =
                carble?.duplicateSuppressions ?: 0L,
            carryDecisions =
                carble?.carryDecisions ?: 0L,
            probeDecisions =
                carble?.probeDecisions ?: 0L,
            probeSuccesses =
                carble?.probeSuccesses ?: 0L,
            probeFailures =
                carble?.probeFailures ?: 0L,
            fallbackDrops =
                carble?.fallbackDrops ?: 0L,
            mediumToLowEscalations =
                carble?.mediumToLowEscalations ?: 0L,
            minCurrentHopConfidence = minQ
        )
    }

    private fun medianOrZero(
        values: List<Long>
    ): Double {

        if (values.isEmpty()) {
            return 0.0
        }

        val sorted = values.sorted()
        val middle = sorted.size / 2

        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1].toDouble() +
                    sorted[middle].toDouble()) / 2.0
        }
    }

    // =====================================================
    // CSV
    // =====================================================

    fun exportCsv(
        results: List<ComparisonResult>,
        outputDirectory: File
    ): File {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "prefailure_protocol_comparison.csv"
            )

        file.bufferedWriter()
            .use { writer ->
                writer.appendLine(
                    "condition,protocol,seed,runId,generated,delivered,dropped,pdr,conditionalMeanLatency,conditionalMedianLatency,physicalAttempts,attemptsPerGenerated,attemptsPerDelivered,retransmissions," +
                            "twoRhHighDecisions,twoRhLowDecisions," +
                            "carbleHighDecisions,carbleMediumDecisions,carbleLowDecisions,carbleM1Decisions,carbleM2Decisions,carbleM3Decisions," +
                            "backupPrepared,backupActivations,backupSuccesses,backupFailures," +
                            "duplicateSuppressions,carryDecisions,probeDecisions,probeSuccesses,probeFailures,fallbackDrops,mediumToLowEscalations,minCurrentHopConfidence"
                )

                results.forEach { r ->
                    writer.appendLine(
                        listOf(
                            r.condition,
                            r.protocol,
                            r.seed,
                            r.runId,
                            r.generated,
                            r.delivered,
                            r.dropped,
                            r.pdr,
                            r.meanLatency,
                            r.medianLatency,
                            r.physicalAttempts,
                            r.attemptsPerGenerated,
                            r.attemptsPerDelivered,
                            r.retransmissions,
                            r.twoRhHighDecisions,
                            r.twoRhLowDecisions,
                            r.highDecisions,
                            r.mediumDecisions,
                            r.lowDecisions,
                            r.m1Decisions,
                            r.m2Decisions,
                            r.m3Decisions,
                            r.backupPrepared,
                            r.backupActivations,
                            r.backupSuccesses,
                            r.backupFailures,
                            r.duplicateSuppressions,
                            r.carryDecisions,
                            r.probeDecisions,
                            r.probeSuccesses,
                            r.probeFailures,
                            r.fallbackDrops,
                            r.mediumToLowEscalations,
                            r.minCurrentHopConfidence ?: ""
                        ).joinToString(",")
                    )
                }
            }

        return file
    }

    // =====================================================
    // PHYSICAL EVENT CALIBRATION PLANS
    // =====================================================

    /**
     * Initial protocol-independent PF-B2 calibration plan.
     *
     * Each degraded branch experiences one short physical flap (DOWN -> UP),
     * giving two real topology observations per branch while keeping at least
     * one candidate route available throughout the disturbance period.
     *
     * These timings are calibration inputs only. They must be frozen before
     * confirmatory seeds are run.
     */
    private fun pfB2CalibrationEvents():
            List<PhysicalLinkEventScheduler.LinkEvent> {

        return buildList {
            addAll(
                PhysicalLinkEventScheduler.flap(
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    downAt = 602L,
                    upAt = 612L
                )
            )
            addAll(
                PhysicalLinkEventScheduler.flap(
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    downAt = 622L,
                    upAt = 632L
                )
            )
        }
    }

    /**
     * Initial protocol-independent PF-C calibration plan.
     *
     * Repeated branch flaps build severe instability evidence, followed by a
     * short complete partition. Both links are restored so LOW carry/probe
     * behavior can be observed without turning the remainder of the run into
     * a permanent no-route condition.
     *
     * Like PF-B2, this plan is calibrated only on calibration seeds and then
     * frozen before confirmatory evaluation.
     */
    private fun pfCCalibrationEvents():
            List<PhysicalLinkEventScheduler.LinkEvent> {

        return buildList {
            addAll(
                PhysicalLinkEventScheduler.flap(
                    "N1", "N2",
                    downAt = 602L,
                    upAt = 612L
                )
            )
            addAll(
                PhysicalLinkEventScheduler.flap(
                    "N1", "N3",
                    downAt = 622L,
                    upAt = 632L
                )
            )
            addAll(
                PhysicalLinkEventScheduler.flap(
                    "N1", "N2",
                    downAt = 662L,
                    upAt = 672L
                )
            )
            addAll(
                PhysicalLinkEventScheduler.flap(
                    "N1", "N3",
                    downAt = 682L,
                    upAt = 692L
                )
            )

            // Temporary complete partition, then recovery.
            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 752L,
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    type = com.example.peertopeer.simulation.experiment.record.TopologyEventType.LINK_DOWN
                )
            )
            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 752L,
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    type = com.example.peertopeer.simulation.experiment.record.TopologyEventType.LINK_DOWN
                )
            )
            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 792L,
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    type = com.example.peertopeer.simulation.experiment.record.TopologyEventType.LINK_UP
                )
            )
            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 792L,
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    type = com.example.peertopeer.simulation.experiment.record.TopologyEventType.LINK_UP
                )
            )
        }
    }

    // =====================================================
    // GRAPH HELPERS
    // =====================================================

    private fun createLineGraph(
        nodeCount: Int
    ): Graph {

        val graph = Graph()

        repeat(nodeCount) { index ->
            val id = "N$index"

            graph.addNode(
                Node(
                    nodeId = id,
                    displayName = id
                )
            )
        }

        for (index in 0 until nodeCount - 1) {
            graph.addEdge(
                "N$index",
                "N${index + 1}",
                1
            )
        }

        return graph
    }

    private fun createDualPathGraph():
            Graph {

        val graph = Graph()

        repeat(5) { index ->
            val id = "N$index"

            graph.addNode(
                Node(
                    nodeId = id,
                    displayName = id
                )
            )
        }

        graph.addEdge("N0", "N1", 1)
        graph.addEdge("N1", "N2", 1)
        graph.addEdge("N2", "N4", 1)
        graph.addEdge("N1", "N3", 1)
        graph.addEdge("N3", "N4", 1)

        return graph
    }

    private fun undirectedMatch(
        from: String,
        to: String,
        a: String,
        b: String
    ): Boolean {
        return (
                from == a &&
                        to == b
                ) ||
                (
                        from == b &&
                                to == a
                        )
    }
}