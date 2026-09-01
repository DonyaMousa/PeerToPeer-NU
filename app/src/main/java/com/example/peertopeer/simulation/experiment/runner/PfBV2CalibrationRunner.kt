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
import com.example.peertopeer.simulation.experiment.prefailure.PfBV2CalibrationCase
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureResult
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import kotlin.random.Random

/**
 * PF-B v2 CALIBRATION
 *
 * Goal:
 * naturally exercise CARBLE M2 and M3 without changing
 * CARBLE thresholds, weights, copy budget, or decision logic.
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
 *
 * N1 <-> N2
 * N1 <-> N3
 *
 * while both tails remain healthy:
 *
 * N2 <-> N4
 * N3 <-> N4
 *
 * This prevents frozen MM from escaping to a completely
 * healthy first hop, while preserving a real alternate
 * route for CARBLE M2/M3 backup behavior.
 */
class PfBV2CalibrationRunner(

    private val hysteresisFraction:
        Double = 0.05,

    private val maxFallbackReevaluations:
        Int = 3,

    private val fallbackReevaluationDelay:
        Long = 5L,

    private val stableTailSuccessProbability:
        Double = 0.97,

    private val sourceLinkSuccessProbability:
        Double = 0.99,

    private val maxAttempts:
        Int = 3,

    private val retryDelay:
        Long = 1L,

    private val packetTtl:
        Int = 80

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
            stableTailSuccessProbability in 0.0..1.0
        )

        require(
            sourceLinkSuccessProbability in 0.0..1.0
        )

        require(
            maxAttempts > 0
        )

        require(
            retryDelay > 0L
        )

        require(
            packetTtl > 0
        )
    }


    fun run(
        seed: Long,
        calibrationCase:
            PfBV2CalibrationCase,
        profile:
            PreFailureProfile
    ): PreFailureResult {

        val runId =
            "CARBLE-PFBV2-${calibrationCase.caseId}-SEED-$seed"


        val simulationEngine =
            SimulationEngine()


        val recorder =
            ExperimentRecorder(
                runId
            )


        val graph =
            createDualPathGraph()


        // =================================================
        // MM OBSERVATION PIPELINE
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


        graph.getEdges()
            .forEach { edge ->

                observationTracker
                    .registerEdge(

                        fromNodeId =
                            edge.from,

                        toNodeId =
                            edge.to,

                        queueCapacity =
                            calibrationCase
                                .queueCapacity
                    )


                observationTracker
                    .registerEdge(

                        fromNodeId =
                            edge.to,

                        toNodeId =
                            edge.from,

                        queueCapacity =
                            calibrationCase
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
                    5
                ) { index ->

                    put(
                        "N$index",
                        calibrationCase
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
                    retryDelay
            )


        // =================================================
        // FROZEN MM + UNCHANGED CARBLE
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
        // INDEPENDENT RNG STREAMS
        // =================================================

        val upperFirstHopRandom =
            Random(
                seed +
                        11_000_000L
            )


        val lowerFirstHopRandom =
            Random(
                seed +
                        11_100_000L
            )


        val upperTailRandom =
            Random(
                seed +
                        11_200_000L
            )


        val lowerTailRandom =
            Random(
                seed +
                        11_300_000L
            )


        val sourceRandom =
            Random(
                seed +
                        11_400_000L
            )


        // =================================================
        // LINK MODEL
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

                        } else {

                            val probability =
                                when {

                                    isUpperFirstHop(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        profile
                                            .probabilityAt(
                                                simulationEngine
                                                    .currentTime
                                            )
                                    }


                                    isLowerFirstHop(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        profile
                                            .probabilityAt(
                                                simulationEngine
                                                    .currentTime
                                            )
                                    }


                                    isUpperTail(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        stableTailSuccessProbability
                                    }


                                    isLowerTail(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        stableTailSuccessProbability
                                    }


                                    else -> {

                                        sourceLinkSuccessProbability
                                    }
                                }


                            val random =
                                when {

                                    isUpperFirstHop(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        upperFirstHopRandom


                                    isLowerFirstHop(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        lowerFirstHopRandom


                                    isUpperTail(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        upperTailRandom


                                    isLowerTail(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        lowerTailRandom


                                    else ->
                                        sourceRandom
                                }


                            random.nextDouble() <
                                    probability
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
         * N0 is source.
         */
        for (
        index in 1..4
        ) {

            simulator.addNode(

                nodeId =
                    "N$index",

                queueCapacity =
                    calibrationCase
                        .queueCapacity,

                serviceTime =
                    calibrationCase
                        .serviceTime
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


        var packetIndex =
            0

        var opportunityTime =
            0L


        while (
            opportunityTime <
            finalTime
        ) {

            /*
             * Capture loop time before scheduling.
             */
            val generationTime =
                opportunityTime


            repeat(
                calibrationCase
                    .packetsPerOpportunity
            ) {

                val currentPacketIndex =
                    packetIndex++


                simulationEngine.schedule(
                    generationTime
                ) {

                    simulator.send(

                        packet =
                            Packet(

                                messageId =
                                    "$runId-MSG-$currentPacketIndex",

                                sourceId =
                                    "N0",

                                destinationId =
                                    "N4",

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


            opportunityTime +=
                calibrationCase
                    .packetInterval
        }


        // =================================================
        // EXECUTE
        // =================================================

        simulationEngine.run()


        val packets =
            recorder
                .getPacketRecords()


        val transmissions =
            recorder
                .getTransmissionRecords()


        require(
            packets.size ==
                    packetIndex
        ) {

            "PF-B v2 $runId expected $packetIndex terminal packets, " +
                    "found ${packets.size}."
        }


        val regimeEvents =
            routeProvider
                .getRegimeEvents()


        require(
            regimeEvents.isNotEmpty()
        )


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
    // LINK GROUPS
    // =====================================================

    private fun isUpperFirstHop(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        return undirectedMatch(
            fromNodeId,
            toNodeId,
            "N1",
            "N2"
        )
    }


    private fun isLowerFirstHop(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        return undirectedMatch(
            fromNodeId,
            toNodeId,
            "N1",
            "N3"
        )
    }


    private fun isUpperTail(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        return undirectedMatch(
            fromNodeId,
            toNodeId,
            "N2",
            "N4"
        )
    }


    private fun isLowerTail(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        return undirectedMatch(
            fromNodeId,
            toNodeId,
            "N3",
            "N4"
        )
    }


    private fun undirectedMatch(
        fromNodeId: String,
        toNodeId: String,
        a: String,
        b: String
    ): Boolean {

        return (
                fromNodeId ==
                    a &&
                        toNodeId ==
                            b
                ) ||
                (
                        fromNodeId ==
                            b &&
                                toNodeId ==
                                    a
                        )
    }


    // =====================================================
    // TOPOLOGY
    // =====================================================

    private fun createDualPathGraph():
        Graph {

        /*
         *          N2
         *         /  \
         * N0 -- N1    N4
         *         \  /
         *          N3
         */

        val graph =
            Graph()


        repeat(
            5
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
}
