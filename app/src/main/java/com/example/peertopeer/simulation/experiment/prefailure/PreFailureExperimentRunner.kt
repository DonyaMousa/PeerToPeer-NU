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
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureResult
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import kotlin.random.Random

/**
 * PF-A — Confidence transition experiment
 *
 * Purpose:
 *
 * Force CARBLE to observe progressive degradation on an
 * unavoidable route so that we can measure where confidence
 * naturally transitions:
 *
 * HIGH -> MEDIUM -> LOW
 *
 * Topology:
 *
 * N0 ---- N1 ---- N2 ---- N3
 *          |
 *          |
 *   degrading link
 *      N1 <-> N2
 *
 * The N1 <-> N2 link progressively degrades according to:
 *
 * 0.95 -> 0.85 -> 0.75 -> 0.65 ->
 * 0.55 -> 0.45 -> 0.35
 *
 * All other links remain stable.
 *
 * IMPORTANT:
 *
 * There is intentionally NO alternate path in PF-A.
 * This isolates confidence-transition behavior from
 * MM route switching.
 */
class PreFailureExperimentRunner(

    private val hysteresisFraction:
    Double = 0.05,

    private val maxFallbackReevaluations:
    Int = 3,

    private val fallbackReevaluationDelay:
    Long = 5L,

    private val stableLinkSuccessProbability:
    Double = 0.95,

    private val packetInterval:
    Long = 5L,

    private val packetTtl:
    Int = 40,

    private val queueCapacity:
    Int = 20,

    private val serviceTime:
    Long = 1L,

    private val maxAttempts:
    Int = 3,

    private val retryDelay:
    Long = 1L

) {

    init {

        require(
            hysteresisFraction in 0.0..1.0
        )

        require(
            maxFallbackReevaluations > 0
        )

        require(
            fallbackReevaluationDelay > 0L
        )

        require(
            stableLinkSuccessProbability in 0.0..1.0
        )

        require(
            packetInterval > 0L
        )

        require(
            packetTtl > 0
        )

        require(
            queueCapacity > 0
        )

        require(
            serviceTime > 0L
        )

        require(
            maxAttempts > 0
        )

        require(
            retryDelay > 0L
        )
    }


    // =====================================================
    // RUN
    // =====================================================

    fun run(
        seed: Long,
        profile:
        PreFailureProfile =
            PreFailureProfile
                .defaultProfile()
    ): PreFailureResult {

        val runId =
            "CARBLE-PFA-SEED-$seed"


        val simulationEngine =
            SimulationEngine()


        val recorder =
            ExperimentRecorder(
                runId
            )


        val graph =
            createLineGraph()


        // =================================================
        // MM STATE + OBSERVATION TRACKER
        // =================================================

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
         * Register both directed representations for every
         * undirected edge.
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
                            queueCapacity
                    )


                observationTracker
                    .registerEdge(

                        fromNodeId =
                            edge.to,

                        toNodeId =
                            edge.from,

                        queueCapacity =
                            queueCapacity
                    )
            }


        // =================================================
        // INSTRUMENTATION
        // =================================================

        val recorderInstrumentation =
            RecorderInstrumentation(
                recorder
            )


        val queueCapacityByNode =
            buildMap {

                repeat(
                    4
                ) { index ->

                    put(
                        "N$index",
                        queueCapacity
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
                    retryDelay
            )


        // =================================================
        // MM PRIMARY ROUTE PROVIDER
        // =================================================

        val mmRouteProvider =
            MMRouteProvider(

                graph =
                    graph,

                stateStore =
                    stateStore,

                runId =
                    runId,

                instrumentation =
                    instrumentation,

                timeProvider = {
                    simulationEngine
                        .currentTime
                },

                hysteresisFraction =
                    hysteresisFraction
            )


        // =================================================
        // CARBLE
        // =================================================

        val routeProvider =
            CarbleRouteProvider(

                mmRouteProvider =
                    mmRouteProvider,

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

                        maxReevaluations =
                            maxFallbackReevaluations,

                        reevaluationDelay =
                            fallbackReevaluationDelay
                    ),

                retryDelay =
                    retryDelay,

                runId =
                    runId,

                timeProvider = {
                    simulationEngine
                        .currentTime
                }
            )


        // =================================================
        // STOCHASTIC STREAMS
        // =================================================

        /*
         * Keep degrading and stable links on separate random
         * streams so the experiment is reproducible.
         */
        val degradingLinkRandom =
            Random(
                seed +
                        9_000_000L
            )


        val stableLinkRandom =
            Random(
                seed +
                        9_100_000L
            )


        // =================================================
        // LINK TRANSMITTER
        // =================================================

        val transmitter =
            EventDrivenRetryLinkTransmitter(

                simulationEngine =
                    simulationEngine,

                maxAttempts =
                    maxAttempts,

                delayPerAttempt =
                    retryDelay,

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

                        } else if (
                            isDegradingLink(
                                fromNodeId,
                                toNodeId
                            )
                        ) {

                            /*
                             * Only N1 <-> N2 follows the
                             * gradual degradation profile.
                             */
                            val probability =
                                profile
                                    .probabilityAt(
                                        simulationEngine
                                            .currentTime
                                    )


                            degradingLinkRandom
                                .nextDouble() <
                                    probability

                        } else {

                            /*
                             * N0 <-> N1 and N2 <-> N3 remain
                             * healthy throughout PF-A.
                             */
                            stableLinkRandom
                                .nextDouble() <
                                    stableLinkSuccessProbability
                        }
                    },

                runId =
                    runId,

                instrumentation =
                    instrumentation
            )


        // =================================================
        // SIMULATOR
        // =================================================

        val simulator =
            TimedNetworkSimulator(

                simulationEngine =
                    simulationEngine,

                eventDrivenLinkTransmitter =
                    transmitter,

                runId =
                    runId,

                instrumentation =
                    instrumentation
            )


        /*
         * N0 is the source.
         *
         * Add service queues only for receiving/forwarding
         * nodes, matching the existing experiment runners.
         */
        for (
        index in 1..3
        ) {

            simulator.addNode(

                nodeId =
                    "N$index",

                queueCapacity =
                    queueCapacity,

                serviceTime =
                    serviceTime
            )
        }


        // =================================================
        // TRAFFIC
        // =================================================

        val finalTime =
            profile
                .phases
                .last()
                .endTimeExclusive


        /*
         * Default profile:
         *
         * 7 phases
         * 150 units/phase
         * total = 1050
         *
         * packet interval = 5
         *
         * 1050 / 5 = 210 packets
         */
        val packetCount =
            (
                    finalTime /
                            packetInterval
                    )
                .toInt()


        repeat(
            packetCount
        ) { packetIndex ->

            val generationTime =
                packetIndex
                    .toLong() *
                        packetInterval


            simulationEngine.schedule(
                generationTime
            ) {

                simulator.send(

                    packet =
                        Packet(

                            messageId =
                                "$runId-MSG-$packetIndex",

                            sourceId =
                                "N0",

                            destinationId =
                                "N3",

                            createdAt =
                                generationTime,

                            ttl =
                                packetTtl,

                            payload =
                                "X".repeat(
                                    32
                                )
                        ),

                    routeProvider =
                        routeProvider
                )
            }
        }


        // =================================================
        // EXECUTE
        // =================================================

        simulationEngine.run()


        // =================================================
        // RESULTS
        // =================================================

        val packets =
            recorder
                .getPacketRecords()


        val transmissions =
            recorder
                .getTransmissionRecords()


        require(
            packets.size ==
                    packetCount
        ) {

            "PF-A run $runId expected " +
                    "$packetCount terminal packets but " +
                    "found ${packets.size}."
        }


        require(
            packets.all {
                it.runId ==
                        runId
            }
        )


        require(
            transmissions.all {
                it.runId ==
                        runId
            }
        )


        /*
         * The experiment must have produced controller
         * evidence. We do NOT require MEDIUM/LOW here,
         * because whether those states appear is itself
         * part of the result being measured.
         */
        val regimeEvents =
            routeProvider
                .getRegimeEvents()


        require(
            regimeEvents.isNotEmpty()
        ) {
            "PF-A produced no CARBLE regime events."
        }


        return PreFailureResult(

            runId =
                runId,

            seed =
                seed,

            profile =
                profile,

            packets =
                packets,

            transmissions =
                transmissions,

            regimeEvents =
                regimeEvents,

            adaptation =
                routeProvider
                    .adaptationTelemetry
                    .snapshot()
        )
    }


    // =====================================================
    // DEGRADING LINK
    // =====================================================

    private fun isDegradingLink(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        /*
         * The degradation is symmetric in the simulator.
         *
         * Normal traffic travels N1 -> N2, but registering
         * both directions keeps the physical link model
         * conceptually symmetric.
         */
        return (
                fromNodeId ==
                        "N1" &&
                        toNodeId ==
                        "N2"
                ) ||
                (
                        fromNodeId ==
                                "N2" &&
                                toNodeId ==
                                "N1"
                        )
    }


    // =====================================================
    // LINE TOPOLOGY
    // =====================================================

    private fun createLineGraph():
            Graph {

        /*
         *
         * N0 ---- N1 ---- N2 ---- N3
         *
         *          ^
         *          |
         *      degrading
         *       N1-N2
         *
         * There is no alternate path.
         */

        val graph =
            Graph()


        repeat(
            4
        ) { index ->

            val id =
                "N$index"


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
            "N3",
            1
        )


        return graph
    }
}