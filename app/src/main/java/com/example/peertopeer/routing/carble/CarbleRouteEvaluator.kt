package com.example.peertopeer.routing.carble

import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore

class CarbleRouteEvaluator(

    private val stateStore:
    MultiMetricStateStore,

    private val signalAdapter:
    CarbleSignalAdapter =
        CarbleSignalAdapter(),

    private val controller:
    CarbleController =
        CarbleController()

) {

    data class RouteEvaluation(

        val path:
        List<String>,

        /*
         * Confidence of path[0] -> path[1].
         */
        val currentHopConfidence:
        Double,

        /*
         * Minimum Q anywhere on the remaining route.
         */
        val routeConfidence:
        Double,

        val regime:
        CarbleRegime,

        val mediumStage:
        CarbleMediumStage?,

        val reason:
        CarbleDecisionReason,

        val bottleneckFromNodeId:
        String?,

        val bottleneckToNodeId:
        String?
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

                currentHopConfidence =
                    1.0,

                routeConfidence =
                    1.0,

                regime =
                    CarbleRegime.HIGH,

                mediumStage =
                    null,

                reason =
                    CarbleDecisionReason
                        .HEALTHY_ROUTE,

                bottleneckFromNodeId =
                    null,

                bottleneckToNodeId =
                    null
            )
        }


        var currentHopConfidence:
                Double? = null

        var minimumConfidence =
            Double.POSITIVE_INFINITY

        var bottleneckFrom:
                String? = null

        var bottleneckTo:
                String? = null


        // -------------------------------------------------
        // Evaluate every remaining route hop
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
                signalAdapter
                    .fromLinkState(
                        linkState
                    )


            val confidence =
                controller
                    .calculateConfidence(
                        signals
                    )


            // =============================================
            // CURRENT HOP
            // =============================================

            if (
                index == 0
            ) {

                currentHopConfidence =
                    confidence
            }


            // =============================================
            // ROUTE BOTTLENECK
            // =============================================

            if (
                confidence <
                minimumConfidence
            ) {

                minimumConfidence =
                    confidence

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


        /*
         * CARBLE controller receives BOTH pieces of
         * information:
         *
         * Qcurrent
         * Qroute
         */
        val controlDecision =
            controller.decide(

                currentHopConfidence =
                    resolvedCurrentHopConfidence,

                routeConfidence =
                    minimumConfidence
            )


        return RouteEvaluation(

            path =
                path.toList(),

            currentHopConfidence =
                resolvedCurrentHopConfidence,

            routeConfidence =
                minimumConfidence,

            regime =
                controlDecision.regime,

            mediumStage =
                controlDecision.mediumStage,

            reason =
                controlDecision.reason,

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

        /*
         * Same healthy bootstrap assumptions as frozen MM
         * and 2RH.
         */
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