package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleController
import com.example.peertopeer.routing.carble.CarbleRegime
import com.example.peertopeer.routing.carble.CarbleRegimeEventRecord
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
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import java.io.File
import kotlin.random.Random

/**
 * CARBLE THRESHOLD ROBUSTNESS STUDY
 *
 * Research purpose:
 * determine whether the full-transition conclusions depend excessively
 * on the exact nominal regime thresholds.
 *
 * This is a pre-specified robustness perturbation, NOT threshold tuning.
 *
 * Only the four regime boundaries change.
 *
 * Confidence weights, signal construction, route evaluation, topology,
 * traffic, queue/service parameters, retry behavior, RNG streams,
 * reliability degradation, and instability schedule remain identical
 * to the frozen full-transition CARBLE experiment.
 *
 * Threshold configurations:
 *
 * EARLY:
 *   HIGH/M1 = .80
 *   M1/M2   = .70
 *   M2/M3   = .60
 *   M3/LOW  = .50
 *
 * NOMINAL:
 *   .75 / .65 / .55 / .45
 *
 * LATE:
 *   .70 / .60 / .50 / .40
 *
 * The NOMINAL configuration remains CARBLE-v1.0 regardless of results.
 */
class CarbleThresholdRobustnessRunner(
    private val queueCapacity: Int = 20,
    private val serviceTime: Long = 1L,
    private val packetInterval: Long = 5L,
    private val maxAttempts: Int = 3,
    private val retryDelay: Long = 1L,
    private val packetTtl: Int = 80
) {

    enum class ThresholdConfig(
        val highThreshold: Double,
        val m1LowerThreshold: Double,
        val m2LowerThreshold: Double,
        val lowThreshold: Double
    ) {
        EARLY(
            highThreshold = 0.80,
            m1LowerThreshold = 0.70,
            m2LowerThreshold = 0.60,
            lowThreshold = 0.50
        ),

        NOMINAL(
            highThreshold = 0.75,
            m1LowerThreshold = 0.65,
            m2LowerThreshold = 0.55,
            lowThreshold = 0.45
        ),

        LATE(
            highThreshold = 0.70,
            m1LowerThreshold = 0.60,
            m2LowerThreshold = 0.50,
            lowThreshold = 0.40
        )
    }

    data class Result(
        val thresholdConfig: ThresholdConfig,
        val seed: Long,
        val runId: String,

        val generated: Int,
        val delivered: Int,
        val dropped: Int,

        val pdr: Double,
        val conditionalMeanLatency: Double,

        val physicalAttempts: Long,
        val retransmissions: Long,
        val attemptsPerDelivered: Double,
        val retransmissionsPerDelivered: Double,

        val high: Long,
        val medium: Long,
        val low: Long,
        val m1: Long,
        val m2: Long,
        val m3: Long,

        val backupPrepared: Long,
        val backupActivated: Long,
        val backupSuccess: Long,
        val backupFailure: Long,

        val carry: Long,
        val probe: Long,
        val probeSuccess: Long,
        val probeFailure: Long,
        val fallbackDrops: Long,

        val firstM1Time: Long?,
        val firstM2Time: Long?,
        val firstM3Time: Long?,
        val firstLowTime: Long?,

        val minCurrentHopConfidence: Double?,
        val minRouteConfidence: Double?,

        val hasAllStages: Boolean,
        val strictFirstEntryOrder: Boolean
    )

    fun run(
        thresholdConfig: ThresholdConfig,
        seed: Long
    ): Result {

        val runId =
            "CARBLE-THRESHOLD-${thresholdConfig.name}-SEED-$seed"

        val engine =
            SimulationEngine()

        val recorder =
            ExperimentRecorder(runId)

        val graph =
            createGraph()

        // =================================================
        // FROZEN PHYSICAL FULL-TRANSITION CONDITION
        // =================================================

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

        val phaseDuration =
            150L

        val finalTime =
            phaseProbabilities.size *
                    phaseDuration

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

        /*
         * EXACT same per-link RNG structure/base offset as
         * the frozen full-transition comparison.
         *
         * Across threshold configurations, equal seeds receive
         * equal physical RNG streams.
         */
        val base =
            20_000_000L

        val upperFirst =
            Random(seed + base)

        val lowerFirst =
            Random(seed + base + 100_000L)

        val upperTail =
            Random(seed + base + 200_000L)

        val lowerTail =
            Random(seed + base + 300_000L)

        val sourceRandom =
            Random(seed + base + 400_000L)

        val attemptDecision:
                (String, String) -> Boolean =
            { from, to ->

                if (
                    !graph.containsEdge(
                        from,
                        to
                    )
                ) {
                    false
                } else {
                    when {

                        match(
                            from,
                            to,
                            "N1",
                            "N2"
                        ) ->
                            upperFirst.nextDouble() <
                                    probabilityAt(
                                        engine.currentTime
                                    )

                        match(
                            from,
                            to,
                            "N1",
                            "N3"
                        ) ->
                            lowerFirst.nextDouble() <
                                    probabilityAt(
                                        engine.currentTime
                                    )

                        match(
                            from,
                            to,
                            "N2",
                            "N4"
                        ) ->
                            upperTail.nextDouble() <
                                    0.97

                        match(
                            from,
                            to,
                            "N3",
                            "N4"
                        ) ->
                            lowerTail.nextDouble() <
                                    0.97

                        else ->
                            sourceRandom.nextDouble() <
                                    0.99
                    }
                }
            }

        // =================================================
        // FROZEN MM OBSERVATION PIPELINE
        // =================================================

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
                stateStore = store,
                runId = runId,
                instrumentation = instrumentation,
                timeProvider = {
                    engine.currentTime
                },
                hysteresisFraction = 0.05
            )

        // =================================================
        // ONLY EXPERIMENTAL VARIABLE: THRESHOLDS
        // =================================================

        val controller =
            CarbleController(
                highThreshold =
                    thresholdConfig.highThreshold,
                lowThreshold =
                    thresholdConfig.lowThreshold,
                m1LowerThreshold =
                    thresholdConfig.m1LowerThreshold,
                m2LowerThreshold =
                    thresholdConfig.m2LowerThreshold
            )

        val evaluator =
            CarbleRouteEvaluator(
                stateStore = store,
                controller = controller
            )

        val provider =
            CarbleRouteProvider(
                mmRouteProvider = mm,
                routeEvaluator = evaluator,
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

        // =================================================
        // FROZEN STAGED INSTABILITY EVIDENCE
        // =================================================

        engine.schedule(600L) {

            repeat(2) {

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

        engine.schedule(750L) {

            repeat(3) {

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

                        attemptDecision(
                            from,
                            to
                        )
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

        graph.getNodes()
            .map {
                it.nodeId
            }
            .filter {
                it != "N0"
            }
            .forEach { nodeId ->

                simulator.addNode(
                    nodeId = nodeId,
                    queueCapacity = queueCapacity,
                    serviceTime = serviceTime
                )
            }

        // =================================================
        // FROZEN TRAFFIC
        // =================================================

        var packetIndex =
            0

        var generationTime =
            0L

        while (
            generationTime <
            finalTime
        ) {

            val g =
                generationTime

            val index =
                packetIndex++

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
                        provider
                )
            }

            generationTime +=
                packetInterval
        }

        engine.run()

        // =================================================
        // RESULT
        // =================================================

        val packets =
            recorder.getPacketRecords()

        val transmissions =
            recorder.getTransmissionRecords()

        val events =
            provider.getRegimeEvents()

        val telemetry =
            provider.adaptationTelemetry
                .snapshot()

        val delivered =
            packets.count {
                it.delivered
            }

        val dropped =
            packets.count {
                it.dropped
            }

        require(
            delivered +
                    dropped ==
                    packets.size
        ) {
            "$runId terminal packet reconciliation failed."
        }

        val latencies =
            packets.mapNotNull {
                it.endToEndLatency
            }

        val firstM1 =
            firstMediumStageTime(
                events,
                "M1"
            )

        val firstM2 =
            firstMediumStageTime(
                events,
                "M2"
            )

        val firstM3 =
            firstMediumStageTime(
                events,
                "M3"
            )

        val firstLow =
            events
                .filter {
                    it.regime ==
                            CarbleRegime.LOW
                }
                .minOfOrNull {
                    it.eventTime
                }

        val hasAllStages =
            telemetry.highDecisions > 0 &&
                    telemetry.m1Decisions > 0 &&
                    telemetry.m2Decisions > 0 &&
                    telemetry.m3Decisions > 0 &&
                    telemetry.lowDecisions > 0

        val strictOrder =
            firstM1 != null &&
                    firstM2 != null &&
                    firstM3 != null &&
                    firstLow != null &&
                    firstM1 <
                    firstM2 &&
                    firstM2 <
                    firstM3 &&
                    firstM3 <
                    firstLow

        return Result(
            thresholdConfig =
                thresholdConfig,
            seed =
                seed,
            runId =
                runId,
            generated =
                packets.size,
            delivered =
                delivered,
            dropped =
                dropped,
            pdr =
                if (
                    packets.isEmpty()
                ) {
                    0.0
                } else {
                    delivered.toDouble() /
                            packets.size.toDouble()
                },
            conditionalMeanLatency =
                if (
                    latencies.isEmpty()
                ) {
                    0.0
                } else {
                    latencies.average()
                },
            physicalAttempts =
                transmissions.size.toLong(),
            retransmissions =
                transmissions.count {
                    it.attemptNumber >
                            1
                }.toLong(),
            attemptsPerDelivered =
                if (
                    delivered > 0
                ) {
                    transmissions.size.toDouble() /
                            delivered.toDouble()
                } else {
                    Double.NaN
                },
            retransmissionsPerDelivered =
                if (
                    delivered > 0
                ) {
                    transmissions.count {
                        it.attemptNumber >
                                1
                    }.toDouble() /
                            delivered.toDouble()
                } else {
                    Double.NaN
                },

            high =
                telemetry.highDecisions,
            medium =
                telemetry.mediumDecisions,
            low =
                telemetry.lowDecisions,
            m1 =
                telemetry.m1Decisions,
            m2 =
                telemetry.m2Decisions,
            m3 =
                telemetry.m3Decisions,

            backupPrepared =
                telemetry.backupPrepared,
            backupActivated =
                telemetry.backupActivations,
            backupSuccess =
                telemetry.backupSuccesses,
            backupFailure =
                telemetry.backupFailures,

            carry =
                telemetry.carryDecisions,
            probe =
                telemetry.probeDecisions,
            probeSuccess =
                telemetry.probeSuccesses,
            probeFailure =
                telemetry.probeFailures,
            fallbackDrops =
                telemetry.fallbackDrops,

            firstM1Time =
                firstM1,
            firstM2Time =
                firstM2,
            firstM3Time =
                firstM3,
            firstLowTime =
                firstLow,

            minCurrentHopConfidence =
                events
                    .mapNotNull {
                        it.currentHopConfidence
                    }
                    .minOrNull(),

            minRouteConfidence =
                events
                    .mapNotNull {
                        it.routeConfidence
                    }
                    .minOrNull(),

            hasAllStages =
                hasAllStages,
            strictFirstEntryOrder =
                strictOrder
        )
    }

    fun exportCsv(
        results: List<Result>,
        outputDirectory: File
    ): File {

        if (
            !outputDirectory.exists()
        ) {
            outputDirectory.mkdirs()
        }

        val file =
            File(
                outputDirectory,
                "carble_threshold_robustness_runs.csv"
            )

        file.bufferedWriter()
            .use { writer ->

                writer.appendLine(
                    "thresholdConfig,seed,runId,highThreshold,m1LowerThreshold,m2LowerThreshold,lowThreshold," +
                            "generated,delivered,dropped,pdr,conditionalMeanLatency,physicalAttempts,retransmissions," +
                            "attemptsPerDelivered,retransmissionsPerDelivered,HIGH,MEDIUM,LOW,M1,M2,M3," +
                            "backupPrepared,backupActivated,backupSuccess,backupFailure,carry,probe,probeSuccess,probeFailure," +
                            "fallbackDrops,firstM1Time,firstM2Time,firstM3Time,firstLowTime,minCurrentHopConfidence," +
                            "minRouteConfidence,hasAllStages,strictFirstEntryOrder"
                )

                results
                    .sortedWith(
                        compareBy<Result> {
                            it.thresholdConfig.ordinal
                        }
                            .thenBy {
                                it.seed
                            }
                    )
                    .forEach { r ->

                        writer.appendLine(
                            listOf(
                                r.thresholdConfig,
                                r.seed,
                                r.runId,

                                r.thresholdConfig
                                    .highThreshold,

                                r.thresholdConfig
                                    .m1LowerThreshold,

                                r.thresholdConfig
                                    .m2LowerThreshold,

                                r.thresholdConfig
                                    .lowThreshold,

                                r.generated,
                                r.delivered,
                                r.dropped,
                                r.pdr,
                                r.conditionalMeanLatency,
                                r.physicalAttempts,
                                r.retransmissions,
                                r.attemptsPerDelivered,
                                r.retransmissionsPerDelivered,

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

                                r.carry,
                                r.probe,
                                r.probeSuccess,
                                r.probeFailure,
                                r.fallbackDrops,

                                r.firstM1Time ?: "",
                                r.firstM2Time ?: "",
                                r.firstM3Time ?: "",
                                r.firstLowTime ?: "",

                                r.minCurrentHopConfidence
                                    ?: "",

                                r.minRouteConfidence
                                    ?: "",

                                r.hasAllStages,
                                r.strictFirstEntryOrder
                            )
                                .joinToString(",")
                        )
                    }
            }

        return file
    }

    private fun firstMediumStageTime(
        events:
        List<CarbleRegimeEventRecord>,
        stage:
        String
    ): Long? {

        return events
            .filter {
                it.regime ==
                        CarbleRegime.MEDIUM &&
                        it.mediumStage
                            ?.toString() ==
                        stage
            }
            .minOfOrNull {
                it.eventTime
            }
    }

    private fun createGraph():
        Graph {

        val graph =
            Graph()

        repeat(5) {

            val id =
                "N$it"

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

    private fun match(
        from: String,
        to: String,
        a: String,
        b: String
    ): Boolean {

        return (
                from ==
                        a &&
                        to ==
                        b
                ) ||
                (
                        from ==
                                b &&
                                to ==
                                a
                        )
    }
}
