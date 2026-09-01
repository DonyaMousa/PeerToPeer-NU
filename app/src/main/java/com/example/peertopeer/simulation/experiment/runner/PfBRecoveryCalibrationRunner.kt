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
import com.example.peertopeer.simulation.experiment.prefailure.PfBRecoveryCalibrationCase
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureProfile
import com.example.peertopeer.simulation.experiment.prefailure.PreFailureResult
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import kotlin.random.Random

/**
 * PF-B CALIBRATION — recovery-stage discovery.
 *
 * This is NOT the final PF-B measurement experiment.
 *
 * Purpose:
 * find a controlled dual-path condition under which the
 * unchanged CARBLE controller naturally exercises M2 and
 * M3. Once such a condition is identified, it is frozen
 * and used for the actual multi-seed comparison.
 *
 * Topology:
 *
 *          N2
 *         /  \
 * N0 -- N1    N4
 *         \  /
 *          N3
 *
 * Primary branch:
 * N1 -> N2 -> N4
 *
 * Alternate branch:
 * N1 -> N3 -> N4
 *
 * N1 <-> N2 follows the gradual degradation profile.
 * N2 <-> N4 remains healthy.
 * The alternate branch stays usable at a configurable
 * probability.
 *
 * CARBLE thresholds, confidence weights and routing logic
 * are NOT modified by this calibration.
 */
class PfBRecoveryCalibrationRunner(

    private val hysteresisFraction:
        Double = 0.05,

    private val maxFallbackReevaluations:
        Int = 3,

    private val fallbackReevaluationDelay:
        Long = 5L,

    private val stablePrimaryTailProbability:
        Double = 0.95,

    private val maxAttempts:
        Int = 3,

    private val retryDelay:
        Long = 1L,

    private val packetTtl:
        Int = 60

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
            stablePrimaryTailProbability in 0.0..1.0
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
            PfBRecoveryCalibrationCase,
        profile:
            PreFailureProfile =
                PreFailureProfile
                    .defaultProfile()
    ): PreFailureResult {

        val runId =
            "CARBLE-PFB-${calibrationCase.caseId}-SEED-$seed"


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

        val degradingRandom =
            Random(
                seed +
                        10_000_000L
            )


        val primaryTailRandom =
            Random(
                seed +
                        10_100_000L
            )


        val backupRandom =
            Random(
                seed +
                        10_200_000L
            )


        val commonRandom =
            Random(
                seed +
                        10_300_000L
            )


        // =================================================
        // PHYSICAL LINK MODEL
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

                                    /*
                                     * This is the CURRENT
                                     * branch decision at N1.
                                     *
                                     * Unlike PF-A's
                                     * downstream-only
                                     * degradation, PF-B puts
                                     * degradation directly
                                     * on N1 -> N2 where a
                                     * backup next hop N3
                                     * exists.
                                     */
                                    isDegradingPrimaryHop(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        profile
                                            .probabilityAt(
                                                simulationEngine
                                                    .currentTime
                                            )
                                    }


                                    isPrimaryTail(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        stablePrimaryTailProbability
                                    }


                                    isBackupBranch(
                                        fromNodeId,
                                        toNodeId
                                    ) -> {

                                        calibrationCase
                                            .backupLinkSuccessProbability
                                    }


                                    else -> {

                                        0.98
                                    }
                                }


                            val random =
                                when {

                                    isDegradingPrimaryHop(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        degradingRandom


                                    isPrimaryTail(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        primaryTailRandom


                                    isBackupBranch(
                                        fromNodeId,
                                        toNodeId
                                    ) ->
                                        backupRandom


                                    else ->
                                        commonRandom
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
         * N0 is the source.
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
        // CONTROLLED TRAFFIC PRESSURE
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
             * IMPORTANT:
             *
             * Capture the current opportunity time before
             * scheduling closures.
             *
             * opportunityTime is mutated by the while loop.
             * Using it directly inside the scheduled lambda
             * would make Packet.createdAt observe a later
             * value than the event's actual scheduled time.
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
        // EXECUTE + VALIDATE
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

            "PF-B calibration $runId expected " +
                    "$packetIndex terminal packets, " +
                    "found ${packets.size}."
        }


        val regimeEvents =
            routeProvider
                .getRegimeEvents()


        require(
            regimeEvents.isNotEmpty()
        ) {
            "PF-B calibration produced no CARBLE regime events."
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
    // LINK CLASSES
    // =====================================================

    private fun isDegradingPrimaryHop(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

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


    private fun isPrimaryTail(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        return (
                fromNodeId ==
                    "N2" &&
                        toNodeId ==
                            "N4"
                ) ||
                (
                        fromNodeId ==
                            "N4" &&
                                toNodeId ==
                                    "N2"
                        )
    }


    private fun isBackupBranch(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {

        return (
                fromNodeId ==
                    "N1" &&
                        toNodeId ==
                            "N3"
                ) ||
                (
                        fromNodeId ==
                            "N3" &&
                                toNodeId ==
                                    "N1"
                        ) ||
                (
                        fromNodeId ==
                            "N3" &&
                                toNodeId ==
                                    "N4"
                        ) ||
                (
                        fromNodeId ==
                            "N4" &&
                                toNodeId ==
                                    "N3"
                        )
    }


    // =====================================================
    // DUAL-PATH TOPOLOGY
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


        /*
         * Add primary branch before backup branch to keep
         * equal-state startup deterministic in the same
         * direction used by the existing dual-path tests.
         */
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
