package com.example.peertopeer.routing.hybrid

import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore

class TwoRegimeRouteEvaluator(

    private val stateStore:
    MultiMetricStateStore,

    private val signalAdapter:
    TwoRegimeSignalAdapter =
        TwoRegimeSignalAdapter(),

    private val controller:
    TwoRegimeController =
        TwoRegimeController(),

    /*
     * Diagnostic only.
     */
    private val traceObserver:
    ((HopEvaluationTrace) -> Unit)? =
        null

) {

    data class RouteEvaluation(

        val path:
        List<String>,

        /*
         * Minimum confidence anywhere on the remaining route.
         *
         * Useful as downstream route-health evidence.
         */
        val routeConfidence:
        Double,

        /*
         * Classification of the whole route bottleneck.
         *
         * Kept for diagnostics/research compatibility.
         */
        val state:
        TwoRegimeState,

        /*
         * Confidence of the hop that the packet would
         * physically use RIGHT NOW:
         *
         * path[0] -> path[1]
         *
         * 2RH forwarding decisions use THIS value.
         */
        val currentHopConfidence:
        Double,

        val currentHopState:
        TwoRegimeState,

        /*
         * Hop producing routeConfidence.
         */
        val bottleneckFromNodeId:
        String?,

        val bottleneckToNodeId:
        String?
    )


    // =====================================================
    // TRACE RECORD
    // =====================================================

    data class HopEvaluationTrace(

        val fromNodeId:
        String,

        val toNodeId:
        String,

        val successRate:
        Double,

        val observedDelay:
        Double,

        val queueOccupancy:
        Int,

        val queueCapacity:
        Int,

        val recentLinkChanges:
        Int,

        val energyPenaltyNormalized:
        Double,

        val deliverySuccess:
        Double,

        val freshness:
        Double,

        val stability:
        Double,

        val timeliness:
        Double,

        val signalReliability:
        Double,

        val resourceSuitability:
        Double,

        val confidence:
        Double,

        val state:
        TwoRegimeState
    )


    // =====================================================
    // EVALUATE
    // =====================================================

    fun evaluate(
        path: List<String>
    ): RouteEvaluation {

        require(
            path.isNotEmpty()
        ) {
            "path must not be empty."
        }

        /*
         * Already at destination.
         */
        if (
            path.size == 1
        ) {

            return RouteEvaluation(

                path =
                    path.toList(),

                routeConfidence =
                    1.0,

                state =
                    TwoRegimeState.HIGH,

                currentHopConfidence =
                    1.0,

                currentHopState =
                    TwoRegimeState.HIGH,

                bottleneckFromNodeId =
                    null,

                bottleneckToNodeId =
                    null
            )
        }


        var minimumConfidence =
            Double.POSITIVE_INFINITY

        var bottleneckFrom:
                String? = null

        var bottleneckTo:
                String? = null


        /*
         * The first edge is the forwarding opportunity
         * the packet is facing NOW.
         */
        var currentHopConfidence:
                Double? = null

        var currentHopState:
                TwoRegimeState? = null


        // -------------------------------------------------
        // Evaluate every remaining hop
        // -------------------------------------------------

        for (
        index in 0 until
                path.lastIndex
        ) {

            val fromNodeId =
                path[
                    index
                ]

            val toNodeId =
                path[
                    index + 1
                ]

            val linkState =
                stateStore.get(
                    fromNodeId,
                    toNodeId
                )
                    ?: defaultState(
                        fromNodeId =
                            fromNodeId,

                        toNodeId =
                            toNodeId
                    )

            val signals =
                signalAdapter.fromLinkState(
                    linkState
                )

            val decision =
                controller.decide(
                    signals
                )


            // =============================================
            // CURRENT HOP
            // =============================================

            if (
                index == 0
            ) {

                currentHopConfidence =
                    decision.confidence

                currentHopState =
                    decision.state
            }


            // =============================================
            // TRACE
            // =============================================

            traceObserver?.invoke(

                HopEvaluationTrace(

                    fromNodeId =
                        fromNodeId,

                    toNodeId =
                        toNodeId,

                    successRate =
                        linkState.successRate,

                    observedDelay =
                        linkState.observedDelay,

                    queueOccupancy =
                        linkState.queueOccupancy,

                    queueCapacity =
                        linkState.queueCapacity,

                    recentLinkChanges =
                        linkState.recentLinkChanges,

                    energyPenaltyNormalized =
                        linkState.energyPenaltyNormalized,

                    deliverySuccess =
                        signals.deliverySuccess,

                    freshness =
                        signals.freshness,

                    stability =
                        signals.stability,

                    timeliness =
                        signals.timeliness,

                    signalReliability =
                        signals.signalReliability,

                    resourceSuitability =
                        signals.resourceSuitability,

                    confidence =
                        decision.confidence,

                    state =
                        decision.state
                )
            )


            // =============================================
            // ROUTE BOTTLENECK
            // =============================================

            if (
                decision.confidence <
                minimumConfidence
            ) {

                minimumConfidence =
                    decision.confidence

                bottleneckFrom =
                    fromNodeId

                bottleneckTo =
                    toNodeId
            }
        }


        val resolvedCurrentHopConfidence =
            requireNotNull(
                currentHopConfidence
            )

        val resolvedCurrentHopState =
            requireNotNull(
                currentHopState
            )


        // =================================================
        // WHOLE-ROUTE CLASSIFICATION
        // =================================================

        val routeState =
            if (
                minimumConfidence >=
                controller.highThreshold
            ) {

                TwoRegimeState.HIGH

            } else {

                TwoRegimeState.LOW
            }


        return RouteEvaluation(

            path =
                path.toList(),

            routeConfidence =
                minimumConfidence,

            state =
                routeState,

            currentHopConfidence =
                resolvedCurrentHopConfidence,

            currentHopState =
                resolvedCurrentHopState,

            bottleneckFromNodeId =
                bottleneckFrom,

            bottleneckToNodeId =
                bottleneckTo
        )
    }


    // =====================================================
    // BOOTSTRAP STATE
    // =====================================================

    private fun defaultState(
        fromNodeId: String,
        toNodeId: String
    ): MultiMetricLinkState {

        return MultiMetricLinkState(

            fromNodeId =
                fromNodeId,

            toNodeId =
                toNodeId,

            successRate =
                1.0,

            observedDelay =
                1.0,

            delayReference =
                10.0,

            queueOccupancy =
                0,

            queueCapacity =
                10,

            recentLinkChanges =
                0,

            instabilityReference =
                5,

            energyPenaltyNormalized =
                0.0
        )
    }
}