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

class PfB2M3CalibrationRunner(
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
        val runId = "CARBLE-PFB2-I$instabilityChanges-SEED-$seed"
        val engine = SimulationEngine()
        val recorder = ExperimentRecorder(runId)
        val graph = createGraph()
        val stateStore = MultiMetricStateStore()

        val tracker = MultiMetricObservationTracker(
            stateStore = stateStore,
            reliabilityWindowSize = 20,
            delayWindowSize = 20,
            delayReference = 10.0,
            instabilityReference = 5
        )

        graph.getEdges().forEach { e ->
            tracker.registerEdge(e.from, e.to, queueCapacity)
            tracker.registerEdge(e.to, e.from, queueCapacity)
        }

        val instrumentation = MMInstrumentation(
            delegate = RecorderInstrumentation(recorder),
            observationTracker = tracker,
            queueCapacityByNode = buildMap {
                repeat(5) { put("N$it", queueCapacity) }
            },
            retryDelay = retryDelay
        )

        val mm = MMRouteProvider(
            graph = graph,
            stateStore = stateStore,
            runId = runId,
            instrumentation = instrumentation,
            timeProvider = { engine.currentTime },
            hysteresisFraction = 0.05
        )

        val carble = CarbleRouteProvider(
            mmRouteProvider = mm,
            routeEvaluator = CarbleRouteEvaluator(stateStore),
            candidateFactory = CarbleBackupCandidateFactory(graph, stateStore),
            backupSelector = CarbleBackupSelector(),
            fallbackPolicy = TwoRegimeFallbackPolicy(
                maxReevaluations = 3,
                reevaluationDelay = 5L
            ),
            retryDelay = retryDelay,
            runId = runId,
            timeProvider = { engine.currentTime }
        )

        val upperFirst = Random(seed + 12_000_000L)
        val lowerFirst = Random(seed + 12_100_000L)
        val upperTail = Random(seed + 12_200_000L)
        val lowerTail = Random(seed + 12_300_000L)
        val source = Random(seed + 12_400_000L)

        val transmitter = EventDrivenRetryLinkTransmitter(
            simulationEngine = engine,
            maxAttempts = maxAttempts,
            delayPerAttempt = retryDelay,
            attemptPolicy = TimedLinkAttemptPolicy { from, to, _, _, _ ->
                if (!graph.containsEdge(from, to)) {
                    false
                } else {
                    val p = when {
                        match(from, to, "N1", "N2") ||
                                match(from, to, "N1", "N3") ->
                            profile.probabilityAt(engine.currentTime)
                        match(from, to, "N2", "N4") ||
                                match(from, to, "N3", "N4") -> 0.97
                        else -> 0.99
                    }

                    val rng = when {
                        match(from, to, "N1", "N2") -> upperFirst
                        match(from, to, "N1", "N3") -> lowerFirst
                        match(from, to, "N2", "N4") -> upperTail
                        match(from, to, "N3", "N4") -> lowerTail
                        else -> source
                    }

                    rng.nextDouble() < p
                }
            },
            runId = runId,
            instrumentation = instrumentation
        )

        val simulator = TimedNetworkSimulator(
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

        // =================================================
        // CONTROLLED INSTABILITY THROUGH THE REAL TRACKER
        // =================================================

        /*
         * Phase 5 begins at t = 600.
         *
         * Instability must be recorded through
         * MultiMetricObservationTracker, not by writing
         * directly into MultiMetricStateStore.
         *
         * observeTopologyChange() updates the tracker-owned
         * MutableObservation and publishes the resulting
         * state. Later transmission observations therefore
         * preserve recentLinkChanges instead of overwriting
         * it.
         *
         * Each call updates BOTH directions of that link.
         * There is no automatic decay, so one injection at
         * the start of phase 5 is sufficient.
         */
        engine.schedule(
            600L
        ) {
            repeat(
                instabilityChanges
            ) {
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

        var packetIndex = 0
        var generationTime = 0L

        while (generationTime < 1050L) {
            val g = generationTime
            val index = packetIndex++

            engine.schedule(g) {
                simulator.send(
                    packet = Packet(
                        messageId = "$runId-MSG-$index",
                        sourceId = "N0",
                        destinationId = "N4",
                        createdAt = g,
                        ttl = packetTtl,
                        payload = "X".repeat(32)
                    ),
                    routeProvider = carble
                )
            }

            generationTime += packetInterval
        }

        engine.run()

        val packets = recorder.getPacketRecords()
        val transmissions = recorder.getTransmissionRecords()
        val events = carble.getRegimeEvents()

        require(packets.size == packetIndex)
        require(events.isNotEmpty())

        return PreFailureResult(
            runId = runId,
            seed = seed,
            profile = profile,
            packets = packets,
            transmissions = transmissions,
            regimeEvents = events,
            adaptation = carble.adaptationTelemetry.snapshot()
        )
    }

    private fun createProfile(): PreFailureProfile {
        val probs = listOf(
            0.90, 0.75, 0.60, 0.45, 0.30, 0.15, 0.05
        )
        return PreFailureProfile(
            probs.mapIndexed { index, p ->
                val start = index * 150L
                PreFailurePhase(
                    phaseIndex = index + 1,
                    startTime = start,
                    endTimeExclusive = start + 150L,
                    successProbability = p
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
        return (from == a && to == b) ||
                (from == b && to == a)
    }

    private fun createGraph(): Graph {
        val g = Graph()

        repeat(5) {
            val id = "N$it"
            g.addNode(
                Node(
                    nodeId = id,
                    displayName = id
                )
            )
        }

        g.addEdge("N0", "N1", 1)
        g.addEdge("N1", "N2", 1)
        g.addEdge("N2", "N4", 1)
        g.addEdge("N1", "N3", 1)
        g.addEdge("N3", "N4", 1)

        return g
    }
}
