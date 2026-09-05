package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
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
import com.example.peertopeer.simulation.experiment.prefailure.PreFailurePhase
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureResult
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import kotlin.random.Random

/**
 * PF-C LOW CALIBRATION
 *
 * Purpose:
 * find the least-severe controlled condition that makes
 * unchanged CARBLE naturally enter LOW (Qcurrent < 0.45)
 * and exercise bounded carry/probe/drop behavior.
 *
 * We deliberately reuse the frozen PF-B dual-path topology
 * and traffic so the only calibration knob is severe
 * instability evidence.
 *
 * Topology:
 *
 *          N2
 *         /  \
 * N0 -- N1    N4
 *         \  /
 *          N3
 *
 * BOTH candidate first hops degrade together:
 * N1 <-> N2
 * N1 <-> N3
 *
 * Reliability profile:
 * 0.90, 0.75, 0.60, 0.45, 0.30, 0.15, 0.05
 *
 * At t=600 we inject controlled topology-change evidence
 * through MultiMetricObservationTracker.
 *
 * IMPORTANT:
 * CARBLE thresholds/weights/controller are NOT changed.
 */
class PfCLowCalibrationRunner(
    private val queueCapacity: Int = 20,
    private val serviceTime: Long = 1L,
    private val packetInterval: Long = 5L,
    private val maxAttempts: Int = 3,
    private val retryDelay: Long = 1L,
    private val packetTtl: Int = 80
) {

    fun run(
        seed: Long,
        instabilityChanges: Int
    ): PreFailureResult {

        require(instabilityChanges in 0..5)

        val profile = createProfile()
        val runId =
            "CARBLE-PFC-I$instabilityChanges-SEED-$seed"

        val engine = SimulationEngine()
        val recorder = ExperimentRecorder(runId)
        val graph = createGraph()

        val stateStore =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore = stateStore,
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
                    RecorderInstrumentation(
                        recorder
                    ),
                observationTracker =
                    tracker,
                queueCapacityByNode =
                    buildMap {
                        repeat(5) {
                            put(
                                "N$it",
                                queueCapacity
                            )
                        }
                    },
                retryDelay =
                    retryDelay
            )

        val mm =
            MMRouteProvider(
                graph = graph,
                stateStore = stateStore,
                runId = runId,
                instrumentation = instrumentation,
                timeProvider = {
                    engine.currentTime
                },
                hysteresisFraction = 0.05
            )

        val carble =
            CarbleRouteProvider(
                mmRouteProvider = mm,
                routeEvaluator =
                    CarbleRouteEvaluator(
                        stateStore
                    ),
                candidateFactory =
                    CarbleBackupCandidateFactory(
                        graph,
                        stateStore
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

        /*
         * Independent physical RNG streams.
         *
         * Keep this structure identical for every
         * instability calibration case.
         */
        val upperFirst =
            Random(seed + 13_000_000L)

        val lowerFirst =
            Random(seed + 13_100_000L)

        val upperTail =
            Random(seed + 13_200_000L)

        val lowerTail =
            Random(seed + 13_300_000L)

        val source =
            Random(seed + 13_400_000L)

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = engine,
                maxAttempts = maxAttempts,
                delayPerAttempt = retryDelay,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            from,
                            to,
                            _,
                            _,
                            _ ->

                        if (!graph.containsEdge(from, to)) {
                            false
                        } else {
                            val p =
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
                                        profile.probabilityAt(
                                            engine.currentTime
                                        )

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

                            val rng =
                                when {
                                    match(
                                        from,
                                        to,
                                        "N1",
                                        "N2"
                                    ) ->
                                        upperFirst

                                    match(
                                        from,
                                        to,
                                        "N1",
                                        "N3"
                                    ) ->
                                        lowerFirst

                                    match(
                                        from,
                                        to,
                                        "N2",
                                        "N4"
                                    ) ->
                                        upperTail

                                    match(
                                        from,
                                        to,
                                        "N3",
                                        "N4"
                                    ) ->
                                        lowerTail

                                    else ->
                                        source
                                }

                            rng.nextDouble() < p
                        }
                    },
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

        for (i in 1..4) {
            simulator.addNode(
                nodeId = "N$i",
                queueCapacity = queueCapacity,
                serviceTime = serviceTime
            )
        }

        /*
         * Phase 5 starts at t=600.
         *
         * Use the REAL tracker-owned observation state.
         * Each call updates both directed observations for
         * that physical link. There is no decay here.
         */
        engine.schedule(600L) {
            repeat(instabilityChanges) {
                tracker.observeTopologyChange(
                    "N1",
                    "N2"
                )

                tracker.observeTopologyChange(
                    "N1",
                    "N3"
                )
            }
        }

        val finalTime =
            profile.phases
                .last()
                .endTimeExclusive

        var packetIndex = 0
        var generationTime = 0L

        while (generationTime < finalTime) {
            val g = generationTime
            val index = packetIndex++

            engine.schedule(g) {
                simulator.send(
                    packet =
                        Packet(
                            messageId =
                                "$runId-MSG-$index",
                            sourceId =
                                "N0",
                            destinationId =
                                "N4",
                            createdAt =
                                g,
                            ttl =
                                packetTtl,
                            payload =
                                "X".repeat(32)
                        ),
                    routeProvider =
                        carble
                )
            }

            generationTime +=
                packetInterval
        }

        engine.run()

        val packets =
            recorder.getPacketRecords()

        val transmissions =
            recorder.getTransmissionRecords()

        val events =
            carble.getRegimeEvents()

        require(
            packets.size == packetIndex
        ) {
            "$runId expected $packetIndex terminal packets, " +
                    "found ${packets.size}."
        }

        require(events.isNotEmpty()) {
            "$runId produced no CARBLE regime events."
        }

        return PreFailureResult(
            runId = runId,
            seed = seed,
            profile = profile,
            packets = packets,
            transmissions = transmissions,
            regimeEvents = events,
            adaptation =
                carble.adaptationTelemetry
                    .snapshot()
        )
    }

    private fun createProfile():
        PreFailureProfile {

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

        return PreFailureProfile(
            probabilities.mapIndexed {
                    index,
                    probability ->

                val start =
                    index * 150L

                PreFailurePhase(
                    phaseIndex = index + 1,
                    startTime = start,
                    endTimeExclusive =
                        start + 150L,
                    successProbability =
                        probability
                )
            }
        )
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
}
