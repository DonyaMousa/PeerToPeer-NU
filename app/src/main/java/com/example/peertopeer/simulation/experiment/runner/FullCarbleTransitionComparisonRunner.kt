package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleRegime
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
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import java.io.File

/**
 * FULL CARBLE TRANSITION COMPARISON
 *
 * One continuous physical degradation timeline intended to
 * exercise the whole controller:
 *
 * HIGH -> M1 -> M2 -> M3 -> LOW
 *
 * The exact same physical traffic/topology/reliability schedule
 * is then executed with:
 *
 * B0, MM, 2RH, CARBLE
 *
 * CARBLE thresholds/weights/controller are unchanged.
 *
 * Topology:
 *
 *          N2
 *         /  \
 * N0 -- N1    N4
 *         \  /
 *          N3
 *
 * Both candidate first hops degrade together:
 * N1 <-> N2
 * N1 <-> N3
 *
 * Reliability phases (150 time units each):
 * .90, .75, .60, .45, .30, .15, .05
 *
 * Calibration physical-event schedule:
 *
 * Before t=700:
 * reliability degradation alone is allowed to exercise
 * HIGH -> M1 -> M2.
 *
 * Around t=700:
 * one real flap per candidate branch introduces moderate
 * instability intended to exercise M3.
 *
 * Around t=880:
 * stronger physical instability and a temporary partition
 * are introduced to exercise LOW.
 *
 * All topology evidence comes from actual Graph changes
 * experienced by every compared protocol.
 */
class FullCarbleTransitionComparisonRunner(
    private val queueCapacity: Int = 20,
    private val serviceTime: Long = 1L,
    private val packetInterval: Long = 5L,
    private val maxAttempts: Int = 3,
    private val retryDelay: Long = 1L,
    private val packetTtl: Int = 80
) {

    enum class Protocol {
        B0,
        MM,
        TWO_RH,
        CARBLE
    }

    /**
     * Per-node resource-proxy burden derived from the same
     * transmission stream used for protocol accounting.
     *
     * physicalAttempts and retransmissions are simulation
     * resource proxies, not measured BLE energy.
     */
    data class RelayBurdenRecord(
        val nodeId: String,
        val isRelay: Boolean,
        val successfulTransmissions: Long,
        val successfulReceives: Long,
        val successfulForwards: Long,
        val physicalAttempts: Long,
        val retransmissions: Long
    )

    data class Result(
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
        val twoRhHigh: Long = 0,
        val twoRhLow: Long = 0,

        // CARBLE-only evidence
        val high: Long = 0,
        val medium: Long = 0,
        val low: Long = 0,
        val m1: Long = 0,
        val m2: Long = 0,
        val m3: Long = 0,
        val backupPrepared: Long = 0,
        val backupActivated: Long = 0,
        val backupSuccess: Long = 0,
        val backupFailure: Long = 0,
        val duplicateSuppression: Long = 0,
        val carry: Long = 0,
        val probe: Long = 0,
        val probeSuccess: Long = 0,
        val probeFailure: Long = 0,
        val fallbackDrops: Long = 0,

        val firstHighTime: Long? = null,
        val firstM1Time: Long? = null,
        val firstM2Time: Long? = null,
        val firstM3Time: Long? = null,
        val firstLowTime: Long? = null,
        val minCurrentHopConfidence: Double? = null,
        val minRouteConfidence: Double? = null,

        // CARBLE-only raw mechanism evidence retained so
        // the final analysis can export the actual Q/regime
        // timeline instead of reconstructing it from summaries.
        val regimeEvents: List<CarbleRegimeEventRecord> =
            emptyList(),

        // Common B0/MM/2RH/CARBLE resource evidence.
        val relayBurden: List<RelayBurdenRecord> =
            emptyList()
    )

    fun run(
        protocol: Protocol,
        seed: Long
    ): Result {

        val runId =
            "FULL-DEGRADATION-${protocol.name}-SEED-$seed"

        val engine = SimulationEngine()
        val recorder = ExperimentRecorder(runId)
        val graph = createGraph()

        val phaseProbabilities =
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
        val finalTime =
            phaseProbabilities.size * phaseDuration

        val probabilityAt: (Long) -> Double =
            { time ->
                val index =
                    (time / phaseDuration)
                        .toInt()
                        .coerceIn(
                            0,
                            phaseProbabilities.lastIndex
                        )

                phaseProbabilities[index]
            }

        val oracle =
            PairedLinkOutcomeOracle(
                seed = seed,
                experimentSalt = "FULL_DEGRADATION"
            )

        val physicalEvents =
            fullTransitionCalibrationEvents()

        val attemptPolicy =
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
                            match(
                                from,
                                to,
                                "N1",
                                "N2"
                            ) ||
                                    match(
                                        from,
                                        to,
                                        "N1",
                                        "N3"
                                    ) ->
                                probabilityAt(attemptTime)

                            match(
                                from,
                                to,
                                "N2",
                                "N4"
                            ) ||
                                    match(
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

        return when (protocol) {
            Protocol.B0 ->
                runB0(
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    finalTime,
                    physicalEvents,
                    attemptPolicy
                )

            Protocol.MM ->
                runMmLike(
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    finalTime,
                    physicalEvents,
                    attemptPolicy
                )

            Protocol.TWO_RH ->
                runMmLike(
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    finalTime,
                    physicalEvents,
                    attemptPolicy
                )

            Protocol.CARBLE ->
                runMmLike(
                    protocol,
                    seed,
                    runId,
                    engine,
                    recorder,
                    graph,
                    finalTime,
                    physicalEvents,
                    attemptPolicy
                )
        }
    }

    private fun runB0(
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        finalTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): Result {

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
                graph,
                attemptPolicy
            )

        scheduleTraffic(
            engine,
            simulator,
            finalTime,
            runId
        ) { packet ->
            simulator.send(
                packet = packet,
                routeProvider = provider
            )
        }

        engine.run()

        return buildResult(
            protocol = Protocol.B0,
            seed = seed,
            runId = runId,
            recorder = recorder
        )
    }

    private fun runMmLike(
        protocol: Protocol,
        seed: Long,
        runId: String,
        engine: SimulationEngine,
        recorder: ExperimentRecorder,
        graph: Graph,
        finalTime: Long,
        physicalEvents:
        List<PhysicalLinkEventScheduler.LinkEvent>,
        attemptPolicy: TimedLinkAttemptPolicy
    ): Result {

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

        graph.getEdges().forEach { edge ->
            tracker.registerEdge(
                edge.from,
                edge.to,
                queueCapacity
            )

            tracker.registerEdge(
                edge.to,
                edge.from,
                queueCapacity
            )
        }

        val instrumentation =
            MMInstrumentation(
                delegate =
                    RecorderInstrumentation(recorder),
                observationTracker = tracker,
                queueCapacityByNode =
                    buildMap {
                        repeat(5) {
                            put(
                                "N$it",
                                queueCapacity
                            )
                        }
                    },
                retryDelay = retryDelay
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
                graph,
                attemptPolicy
            )

        return when (protocol) {

            Protocol.MM -> {
                scheduleTraffic(
                    engine,
                    simulator,
                    finalTime,
                    runId
                ) { packet ->
                    simulator.send(
                        packet = packet,
                        routeProvider = mm
                    )
                }

                engine.run()

                buildResult(
                    protocol,
                    seed,
                    runId,
                    recorder
                )
            }

            Protocol.TWO_RH -> {
                val provider =
                    TwoRegimeRouteProvider(
                        mmRouteProvider = mm,
                        routeEvaluator =
                            TwoRegimeRouteEvaluator(
                                stateStore = store
                            ),
                        fallbackPolicy =
                            TwoRegimeFallbackPolicy(
                                maxReevaluations = 3,
                                reevaluationDelay = 5L
                            )
                    )

                scheduleTraffic(
                    engine,
                    simulator,
                    finalTime,
                    runId
                ) { packet ->
                    simulator.send(
                        packet = packet,
                        routeProvider = provider
                    )
                }

                engine.run()

                buildResult(
                    protocol = protocol,
                    seed = seed,
                    runId = runId,
                    recorder = recorder,
                    twoRh =
                        provider.adaptationTelemetry
                            .snapshot()
                )
            }

            Protocol.CARBLE -> {
                val provider =
                    CarbleRouteProvider(
                        mmRouteProvider = mm,
                        routeEvaluator =
                            CarbleRouteEvaluator(store),
                        candidateFactory =
                            CarbleBackupCandidateFactory(
                                graph,
                                store
                            ),
                        backupSelector =
                            CarbleBackupSelector(),
                        fallbackPolicy =
                            TwoRegimeFallbackPolicy(
                                maxReevaluations = 3,
                                reevaluationDelay = 5L
                            ),
                        retryDelay = retryDelay,
                        runId = runId,
                        timeProvider = {
                            engine.currentTime
                        }
                    )

                scheduleTraffic(
                    engine,
                    simulator,
                    finalTime,
                    runId
                ) { packet ->
                    simulator.send(
                        packet = packet,
                        routeProvider = provider
                    )
                }

                engine.run()

                buildResult(
                    protocol = protocol,
                    seed = seed,
                    runId = runId,
                    recorder = recorder,
                    telemetry =
                        provider.adaptationTelemetry
                            .snapshot(),
                    regimeEvents =
                        provider.getRegimeEvents()
                )
            }

            Protocol.B0 ->
                error("B0 must use runB0().")
        }
    }

    /**
     * Protocol-independent calibration schedule for the full
     * degradation experiment.
     *
     * Before t=700, reliability degradation alone drives HIGH/M1/M2.
     * Around t=700, each candidate branch experiences one short real flap
     * intended to create the moderate-instability window for M3.
     * Around t=880, stronger shared physical instability is introduced,
     * followed by a temporary partition at t=900 to exercise LOW
     * carry/probe behavior. These timings are calibration inputs only and
     * must be frozen before confirmatory seeds are opened.
     */
    private fun fullTransitionCalibrationEvents():
            List<PhysicalLinkEventScheduler.LinkEvent> {

        return buildList {

            /*
             * CALIBRATION DESIGN
             *
             * Before t=700:
             * reliability degradation alone is allowed to drive:
             *
             * HIGH -> M1 -> M2
             *
             * We intentionally avoid topology events before this
             * window so M2 has time to emerge naturally.
             */

            /*
             * MODERATE REAL INSTABILITY
             *
             * One short physical flap on each candidate branch.
             *
             * Each flap produces:
             * DOWN + UP = 2 topology observations per link.
             *
             * Intended to exercise CARBLE M3 after M2.
             */

            addAll(
                PhysicalLinkEventScheduler.flap(
                    "N1",
                    "N2",
                    downAt = 700L,
                    upAt = 710L
                )
            )

            addAll(
                PhysicalLinkEventScheduler.flap(
                    "N1",
                    "N3",
                    downAt = 720L,
                    upAt = 730L
                )
            )

            /*
             * SEVERE REAL INSTABILITY
             *
             * Delayed until t=880 so CARBLE has a clearer M3
             * observation window before LOW is deliberately induced.
             *
             * Together with the previous flap, the following
             * events accumulate stronger real topology instability.
             */

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 880L,
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_DOWN
                )
            )

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 880L,
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_DOWN
                )
            )

            /*
             * Brief recovery.
             */

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 890L,
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_UP
                )
            )

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 890L,
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_UP
                )
            )

            /*
             * Temporary complete partition.
             *
             * This is the final severe event intended to exercise
             * LOW / carry / probe behavior.
             */

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 900L,
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_DOWN
                )
            )

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 900L,
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_DOWN
                )
            )

            /*
             * Restore both branches after the LOW observation window.
             */

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 950L,
                    fromNodeId = "N1",
                    toNodeId = "N2",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_UP
                )
            )

            add(
                PhysicalLinkEventScheduler.LinkEvent(
                    time = 950L,
                    fromNodeId = "N1",
                    toNodeId = "N3",
                    type =
                        com.example.peertopeer.simulation.experiment.record
                            .TopologyEventType.LINK_UP
                )
            )
        }
    }

    private fun createSimulator(
        engine: SimulationEngine,
        runId: String,
        instrumentation: ExperimentInstrumentation,
        graph: Graph,
        attemptPolicy: TimedLinkAttemptPolicy
    ): TimedNetworkSimulator {

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = engine,
                maxAttempts = maxAttempts,
                delayPerAttempt = retryDelay,
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

        graph.getNodes()
            .map { it.nodeId }
            .filter { it != "N0" }
            .forEach {
                simulator.addNode(
                    nodeId = it,
                    queueCapacity = queueCapacity,
                    serviceTime = serviceTime
                )
            }

        return simulator
    }

    private fun scheduleTraffic(
        engine: SimulationEngine,
        simulator: TimedNetworkSimulator,
        finalTime: Long,
        runId: String,
        sender: (Packet) -> Unit
    ) {

        var index = 0
        var time = 0L

        while (time < finalTime) {
            val generationTime = time
            val packetIndex = index++

            engine.schedule(generationTime) {
                sender(
                    Packet(
                        messageId =
                            "$runId-MSG-$packetIndex",
                        sourceId = "N0",
                        destinationId = "N4",
                        createdAt = generationTime,
                        ttl = packetTtl,
                        payload =
                            "X".repeat(32)
                    )
                )
            }

            time += packetInterval
        }
    }

    private fun buildResult(
        protocol: Protocol,
        seed: Long,
        runId: String,
        recorder: ExperimentRecorder,
        twoRh: TwoRegimeTelemetrySnapshot? = null,
        telemetry: CarbleTelemetrySnapshot? = null,
        regimeEvents: List<CarbleRegimeEventRecord> =
            emptyList()
    ): Result {

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
            delivered + dropped == packets.size
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

        val minRouteQ =
            regimeEvents
                .mapNotNull {
                    it.routeConfidence
                }
                .minOrNull()

        val relayBurden =
            buildRelayBurden(
                packets = packets,
                transmissions = transmissions
            )

        require(
            relayBurden.sumOf {
                it.physicalAttempts
            } == transmissions.size.toLong()
        ) {
            "$runId per-node physical-attempt reconciliation failed."
        }

        require(
            relayBurden.sumOf {
                it.retransmissions
            } ==
                    transmissions.count {
                        it.attemptNumber > 1
                    }.toLong()
        ) {
            "$runId per-node retransmission reconciliation failed."
        }

        return Result(
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

            twoRhHigh =
                twoRh?.highDecisions ?: 0L,
            twoRhLow =
                twoRh?.lowDecisions ?: 0L,

            high =
                telemetry?.highDecisions ?: 0,
            medium =
                telemetry?.mediumDecisions ?: 0,
            low =
                telemetry?.lowDecisions ?: 0,
            m1 =
                telemetry?.m1Decisions ?: 0,
            m2 =
                telemetry?.m2Decisions ?: 0,
            m3 =
                telemetry?.m3Decisions ?: 0,
            backupPrepared =
                telemetry?.backupPrepared ?: 0,
            backupActivated =
                telemetry?.backupActivations ?: 0,
            backupSuccess =
                telemetry?.backupSuccesses ?: 0,
            backupFailure =
                telemetry?.backupFailures ?: 0,
            duplicateSuppression =
                telemetry?.duplicateSuppressions ?: 0,
            carry =
                telemetry?.carryDecisions ?: 0,
            probe =
                telemetry?.probeDecisions ?: 0,
            probeSuccess =
                telemetry?.probeSuccesses ?: 0,
            probeFailure =
                telemetry?.probeFailures ?: 0,
            fallbackDrops =
                telemetry?.fallbackDrops ?: 0,

            firstHighTime =
                regimeEvents
                    .filter {
                        it.regime ==
                                CarbleRegime.HIGH
                    }
                    .minOfOrNull {
                        it.eventTime
                    },

            firstM1Time =
                firstTime(
                    regimeEvents,
                    CarbleRegime.MEDIUM,
                    "M1"
                ),
            firstM2Time =
                firstTime(
                    regimeEvents,
                    CarbleRegime.MEDIUM,
                    "M2"
                ),
            firstM3Time =
                firstTime(
                    regimeEvents,
                    CarbleRegime.MEDIUM,
                    "M3"
                ),
            firstLowTime =
                regimeEvents
                    .filter {
                        it.regime ==
                                CarbleRegime.LOW
                    }
                    .minOfOrNull {
                        it.eventTime
                    },
            minCurrentHopConfidence =
                minQ,
            minRouteConfidence =
                minRouteQ,
            regimeEvents =
                regimeEvents,
            relayBurden =
                relayBurden
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

    /**
     * Derive the exact same per-node resource proxies used
     * by the frozen main S01-S05 runners:
     *
     * - successful logical transmissions
     * - successful logical receives
     * - successful forwarding hops
     * - physical attempts
     * - retransmissions
     *
     * Full-transition source/destination are fixed:
     * N0 = source, N4 = destination.
     * N1/N2/N3 are relay nodes.
     */
    private fun buildRelayBurden(
        packets: List<PacketRecord>,
        transmissions: List<TransmissionRecord>
    ): List<RelayBurdenRecord> {

        val packetByMessageId =
            packets.associateBy {
                it.messageId
            }

        val successfulLogicalHops =
            transmissions
                .groupBy {
                    Pair(
                        it.messageId,
                        it.logicalHopIndex
                    )
                }
                .values
                .mapNotNull { attempts ->
                    attempts.firstOrNull {
                        it.success
                    }
                }

        return (0..4)
            .map { index ->
                val nodeId = "N$index"

                RelayBurdenRecord(
                    nodeId = nodeId,
                    isRelay =
                        nodeId != "N0" &&
                                nodeId != "N4",
                    successfulTransmissions =
                        successfulLogicalHops
                            .count {
                                it.fromNodeId == nodeId
                            }
                            .toLong(),
                    successfulReceives =
                        successfulLogicalHops
                            .count {
                                it.toNodeId == nodeId
                            }
                            .toLong(),
                    successfulForwards =
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
                            .toLong(),
                    physicalAttempts =
                        transmissions
                            .count {
                                it.fromNodeId == nodeId
                            }
                            .toLong(),
                    retransmissions =
                        transmissions
                            .count {
                                it.fromNodeId == nodeId &&
                                        it.attemptNumber > 1
                            }
                            .toLong()
                )
            }
    }

    /*
     * Avoid depending on a specific enum string import for
     * CarbleMediumStage. The regime event CSV already exposes
     * stage names M1/M2/M3, and toString() is stable enough
     * for this research timeline helper.
     */
    private fun firstTime(
        events: List<CarbleRegimeEventRecord>,
        regime: CarbleRegime,
        stage: String
    ): Long? {

        return events
            .filter {
                it.regime == regime &&
                        it.mediumStage
                            ?.toString() == stage
            }
            .minOfOrNull {
                it.eventTime
            }
    }

    fun exportCsv(
        results: List<Result>,
        outputDirectory: File
    ): File {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "full_carble_transition_comparison.csv"
            )

        file.bufferedWriter().use { w ->
            w.appendLine(
                "protocol,seed,runId,generated,delivered,dropped,pdr,conditionalMeanLatency,conditionalMedianLatency,physicalAttempts,attemptsPerGenerated,attemptsPerDelivered,retransmissions," +
                        "twoRhHighDecisions,twoRhLowDecisions," +
                        "carbleHighDecisions,carbleMediumDecisions,carbleLowDecisions,carbleM1Decisions,carbleM2Decisions,carbleM3Decisions," +
                        "backupPrepared,backupActivated,backupSuccess,backupFailure," +
                        "duplicateSuppression,carry,probe,probeSuccess,probeFailure,fallbackDrops," +
                        "firstHighTime,firstM1Time,firstM2Time,firstM3Time,firstLowTime,minCurrentHopConfidence,minRouteConfidence"
            )

            results.forEach { r ->
                w.appendLine(
                    listOf(
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
                        r.twoRhHigh,
                        r.twoRhLow,
                        r.high,
                        r.medium,
                        r.low,
                        r.m1,
                        r.m2,
                        r.m3,
                        r.backupPrepared,
                        r.backupActivated,
                        r.backupSuccess,
                        r.backupFailure,
                        r.duplicateSuppression,
                        r.carry,
                        r.probe,
                        r.probeSuccess,
                        r.probeFailure,
                        r.fallbackDrops,
                        r.firstHighTime ?: "",
                        r.firstM1Time ?: "",
                        r.firstM2Time ?: "",
                        r.firstM3Time ?: "",
                        r.firstLowTime ?: "",
                        r.minCurrentHopConfidence ?: "",
                        r.minRouteConfidence ?: ""
                    ).joinToString(",")
                )
            }
        }

        return file
    }


    /**
     * Event-level CARBLE mechanism evidence for the full
     * transition experiment.
     *
     * One row = one recorded CARBLE regime/action event.
     *
     * This file is the source for:
     * - Qcurrent / Qroute timeline figures
     * - threshold-crossing analysis
     * - regime/stage transition evidence
     * - action/reason inspection
     *
     * B0/MM/2RH do not have CARBLE regimes, so they are not
     * represented in this mechanism-only file.
     */
    fun exportCarbleEventCsv(
        results: List<Result>,
        outputDirectory: File
    ): File {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "full_carble_transition_events.csv"
            )

        file.bufferedWriter().use { w ->

            w.appendLine(
                "protocol,seed,runId,eventTime,phaseIndex,phaseSuccessProbability,cumulativeInstabilityEvidence," +
                        "currentNodeId,destinationId,currentHopConfidence,routeConfidence,previousRegime,regime,mediumStage," +
                        "reason,bottleneckFromNodeId,bottleneckToNodeId,primaryNextHopId,backupNextHopId,action"
            )

            results
                .filter {
                    it.protocol ==
                            Protocol.CARBLE
                }
                .forEach { result ->

                    result.regimeEvents
                        .sortedBy {
                            it.eventTime
                        }
                        .forEach { event ->

                            w.appendLine(
                                listOf(
                                    result.protocol,
                                    result.seed,
                                    result.runId,
                                    event.eventTime,
                                    phaseIndexAt(
                                        event.eventTime
                                    ),
                                    phaseSuccessProbabilityAt(
                                        event.eventTime
                                    ),
                                    cumulativeInstabilityAt(
                                        event.eventTime
                                    ),
                                    event.currentNodeId,
                                    event.destinationId,
                                    event.currentHopConfidence
                                        ?: "",
                                    event.routeConfidence
                                        ?: "",
                                    event.previousRegime
                                        ?: "",
                                    event.regime,
                                    event.mediumStage
                                        ?: "",
                                    csvCell(
                                        event.reason
                                    ),
                                    event.bottleneckFromNodeId
                                        ?: "",
                                    event.bottleneckToNodeId
                                        ?: "",
                                    event.primaryNextHopId
                                        ?: "",
                                    event.backupNextHopId
                                        ?: "",
                                    csvCell(
                                        event.action
                                    )
                                ).joinToString(",")
                            )
                        }
                }
        }

        return file
    }

    /**
     * One CARBLE row per seed for direct lifecycle auditing.
     *
     * This intentionally separates mechanism evidence from
     * the B0/MM/2RH performance comparison.
     */
    fun exportTransitionAuditCsv(
        results: List<Result>,
        outputDirectory: File
    ): File {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "full_carble_transition_audit.csv"
            )

        file.bufferedWriter().use { w ->

            w.appendLine(
                "protocol,seed,runId,eventCount,hasHIGH,hasM1,hasM2,hasM3,hasLOW,hasAllStages," +
                        "strictFirstEntryOrder,firstHighTime,firstM1Time,firstM2Time,firstM3Time,firstLowTime," +
                        "m1ToM2,m2ToM3,m3ToLow,m1ToLowLeadTime,minCurrentHopConfidence,minRouteConfidence," +
                        "HIGH,M1,M2,M3,LOW,carry,probe,probeSuccess,probeFailure,fallbackDrops"
            )

            results
                .filter {
                    it.protocol ==
                            Protocol.CARBLE
                }
                .sortedBy {
                    it.seed
                }
                .forEach { r ->

                    val hasHigh =
                        r.high > 0
                    val hasM1 =
                        r.m1 > 0
                    val hasM2 =
                        r.m2 > 0
                    val hasM3 =
                        r.m3 > 0
                    val hasLow =
                        r.low > 0

                    val hasAll =
                        hasHigh &&
                                hasM1 &&
                                hasM2 &&
                                hasM3 &&
                                hasLow

                    val ordered =
                        r.firstM1Time != null &&
                                r.firstM2Time != null &&
                                r.firstM3Time != null &&
                                r.firstLowTime != null &&
                                r.firstM1Time <
                                r.firstM2Time &&
                                r.firstM2Time <
                                r.firstM3Time &&
                                r.firstM3Time <
                                r.firstLowTime

                    w.appendLine(
                        listOf(
                            r.protocol,
                            r.seed,
                            r.runId,
                            r.regimeEvents.size,
                            hasHigh,
                            hasM1,
                            hasM2,
                            hasM3,
                            hasLow,
                            hasAll,
                            ordered,
                            r.firstHighTime ?: "",
                            r.firstM1Time ?: "",
                            r.firstM2Time ?: "",
                            r.firstM3Time ?: "",
                            r.firstLowTime ?: "",
                            difference(
                                r.firstM1Time,
                                r.firstM2Time
                            ) ?: "",
                            difference(
                                r.firstM2Time,
                                r.firstM3Time
                            ) ?: "",
                            difference(
                                r.firstM3Time,
                                r.firstLowTime
                            ) ?: "",
                            difference(
                                r.firstM1Time,
                                r.firstLowTime
                            ) ?: "",
                            r.minCurrentHopConfidence
                                ?: "",
                            r.minRouteConfidence
                                ?: "",
                            r.high,
                            r.m1,
                            r.m2,
                            r.m3,
                            r.low,
                            r.carry,
                            r.probe,
                            r.probeSuccess,
                            r.probeFailure,
                            r.fallbackDrops
                        ).joinToString(",")
                    )
                }
        }

        return file
    }

    private fun difference(
        from: Long?,
        to: Long?
    ): Long? {

        if (
            from == null ||
            to == null
        ) {
            return null
        }

        return to - from
    }

    private fun phaseIndexAt(
        time: Long
    ): Int {

        return (
                time /
                        150L
                )
            .toInt()
            .coerceIn(
                0,
                6
            ) + 1
    }

    private fun phaseSuccessProbabilityAt(
        time: Long
    ): Double {

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

        return probabilities[
            phaseIndexAt(time) - 1
        ]
    }

    private fun cumulativeInstabilityAt(
        time: Long
    ): Int {

        return when {
            time < 700L ->
                0

            time < 730L ->
                1

            time < 880L ->
                2

            time < 890L ->
                3

            time < 900L ->
                4

            else ->
                5
        }
    }

    private fun csvCell(
        value: String
    ): String {

        val escaped =
            value.replace(
                "\"",
                "\"\""
            )

        return "\"$escaped\""
    }


    /**
     * One row per protocol × seed × node.
     *
     * This is the canonical full-transition node-burden
     * dataset for the secondary sustainability question.
     */
    fun exportRelayBurdenCsv(
        results: List<Result>,
        outputDirectory: File
    ): File {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "full_transition_relay_burden.csv"
            )

        file.bufferedWriter().use { w ->

            w.appendLine(
                "protocol,seed,runId,nodeId,isRelay,successfulTransmissions,successfulReceives,successfulForwards," +
                        "physicalAttempts,retransmissions,relayAttemptShare,relayForwardShare"
            )

            results.forEach { result ->

                val relays =
                    result.relayBurden
                        .filter {
                            it.isRelay
                        }

                val totalRelayAttempts =
                    relays.sumOf {
                        it.physicalAttempts
                    }

                val totalRelayForwards =
                    relays.sumOf {
                        it.successfulForwards
                    }

                result.relayBurden
                    .sortedBy {
                        it.nodeId
                    }
                    .forEach { node ->

                        val attemptShare =
                            if (
                                node.isRelay &&
                                totalRelayAttempts > 0
                            ) {
                                node.physicalAttempts
                                    .toDouble() /
                                        totalRelayAttempts
                                            .toDouble()
                            } else {
                                null
                            }

                        val forwardShare =
                            if (
                                node.isRelay &&
                                totalRelayForwards > 0
                            ) {
                                node.successfulForwards
                                    .toDouble() /
                                        totalRelayForwards
                                            .toDouble()
                            } else {
                                null
                            }

                        w.appendLine(
                            listOf(
                                result.protocol,
                                result.seed,
                                result.runId,
                                node.nodeId,
                                node.isRelay,
                                node.successfulTransmissions,
                                node.successfulReceives,
                                node.successfulForwards,
                                node.physicalAttempts,
                                node.retransmissions,
                                attemptShare ?: "",
                                forwardShare ?: ""
                            ).joinToString(",")
                        )
                    }
            }
        }

        return file
    }

    /**
     * One row per protocol × seed with run-level
     * resource-efficiency and relay-burden concentration
     * measures.
     *
     * IMPORTANT:
     * These are resource proxies. They are not joules or
     * measured battery energy.
     *
     * Jain indices are computed only over relay nodes
     * N1/N2/N3. Because this topology has structurally
     * asymmetric relay roles, the index is interpreted as
     * burden distribution/concentration, not as a normative
     * claim that equal load is always optimal.
     */
    fun exportResourceSummaryCsv(
        results: List<Result>,
        outputDirectory: File
    ): File {

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "full_transition_resource_summary.csv"
            )

        file.bufferedWriter().use { w ->

            w.appendLine(
                "protocol,seed,runId,generated,delivered,pdr,physicalAttempts,retransmissions," +
                        "attemptsPerGenerated,attemptsPerDelivered,retransmissionsPerDelivered,totalRelayAttempts,totalRelayForwards," +
                        "maxRelayAttemptShare,maxRelayForwardShare,maxMeanRelayAttemptRatio," +
                        "jainRelayAttemptFairness,jainRelayForwardFairness"
            )

            results.forEach { result ->

                val relays =
                    result.relayBurden
                        .filter {
                            it.isRelay
                        }

                val relayAttempts =
                    relays.map {
                        it.physicalAttempts
                            .toDouble()
                    }

                val relayForwards =
                    relays.map {
                        it.successfulForwards
                            .toDouble()
                    }

                val totalRelayAttempts =
                    relayAttempts.sum()

                val totalRelayForwards =
                    relayForwards.sum()

                val maxRelayAttemptShare =
                    if (totalRelayAttempts > 0.0) {
                        relayAttempts.maxOrNull()!! /
                                totalRelayAttempts
                    } else {
                        0.0
                    }

                val maxRelayForwardShare =
                    if (totalRelayForwards > 0.0) {
                        relayForwards.maxOrNull()!! /
                                totalRelayForwards
                    } else {
                        0.0
                    }

                val meanRelayAttempts =
                    if (relayAttempts.isEmpty()) {
                        0.0
                    } else {
                        relayAttempts.average()
                    }

                val maxMeanRelayAttemptRatio =
                    if (meanRelayAttempts > 0.0) {
                        relayAttempts.maxOrNull()!! /
                                meanRelayAttempts
                    } else {
                        0.0
                    }

                w.appendLine(
                    listOf(
                        result.protocol,
                        result.seed,
                        result.runId,
                        result.generated,
                        result.delivered,
                        result.pdr,
                        result.physicalAttempts,
                        result.retransmissions,
                        result.attemptsPerGenerated,
                        result.attemptsPerDelivered,
                        perDelivered(
                            result.retransmissions,
                            result.delivered
                        ),
                        totalRelayAttempts.toLong(),
                        totalRelayForwards.toLong(),
                        maxRelayAttemptShare,
                        maxRelayForwardShare,
                        maxMeanRelayAttemptRatio,
                        jainIndex(
                            relayAttempts
                        ),
                        jainIndex(
                            relayForwards
                        )
                    ).joinToString(",")
                )
            }
        }

        return file
    }

    private fun perDelivered(
        value: Long,
        delivered: Int
    ): Double {

        return if (delivered > 0) {
            value.toDouble() /
                    delivered.toDouble()
        } else {
            Double.NaN
        }
    }

    private fun jainIndex(
        values: List<Double>
    ): Double {

        if (values.isEmpty()) {
            return Double.NaN
        }

        val sum =
            values.sum()

        val denominator =
            values.size.toDouble() *
                    values.sumOf {
                        it * it
                    }

        if (denominator == 0.0) {
            return 1.0
        }

        return (sum * sum) /
                denominator
    }

    private fun createGraph():
            Graph {

        val graph = Graph()

        repeat(5) {
            val id = "N$it"

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

    private fun match(
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
