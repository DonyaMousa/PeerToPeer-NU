package com.example.peertopeer.simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.routing.RoutingTelemetry
import com.example.peertopeer.routing.mm.MultiMetricCostCalculator
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricRoutingEngine
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventType
import kotlin.math.roundToInt

class MMRouteProvider(

    private val graph: Graph,

    private val stateStore:
    MultiMetricStateStore,

    private val costCalculator:
    MultiMetricCostCalculator =
        MultiMetricCostCalculator(),

    private val routingEngine:
    MultiMetricRoutingEngine =
        MultiMetricRoutingEngine(
            costCalculator
        ),

    private val runId: String? = null,

    private val instrumentation:
    ExperimentInstrumentation? = null,

    private val timeProvider:
        () -> Long = { 0L },

    /*
     * Fractional improvement required before MM changes
     * from one still-valid path to another.
     *
     * Examples:
     *
     * 0.00 = no hysteresis
     * 0.05 = new path must be at least 5% cheaper
     * 0.10 = new path must be at least 10% cheaper
     */
    private val hysteresisFraction:
    Double = 0.0

) : TimedRouteProvider {

    init {

        require(
            hysteresisFraction in 0.0..1.0
        ) {
            "hysteresisFraction must be between 0.0 and 1.0."
        }
    }

    /*
     * MM intentionally does not use B0's topology-version
     * RoutingTable cache.
     *
     * Multi-metric costs may change even without topology
     * changes.
     */
    val telemetry =
        RoutingTelemetry()

    /*
     * Last route actually SELECTED by MM for:
     *
     * (current node, destination)
     *
     * Unlike lastSuccessfulPaths, this participates in the
     * hysteresis decision.
     */
    private val preferredPaths =
        mutableMapOf<
                Pair<String, String>,
                List<String>
                >()

    /*
     * Used only for ROUTE_CHANGED instrumentation.
     *
     * The definition remains identical to B0:
     *
     * a route change means that the successful selected path
     * for the same (current node, destination) differs from
     * the previously observed successful selected path.
     */
    private val lastSuccessfulPaths =
        mutableMapOf<
                Pair<String, String>,
                List<String>
                >()

    // =====================================================
    // TIMED ROUTE PROVIDER
    // =====================================================

    override fun findPath(
        currentNodeId: String,
        destinationId: String
    ): List<String>? {

        return findPath(
            currentNodeId = currentNodeId,
            destinationId = destinationId,
            messageId = null
        )
    }

    override fun findPath(
        currentNodeId: String,
        destinationId: String,
        messageId: String?
    ): List<String>? {

        require(
            currentNodeId.isNotBlank()
        ) {
            "currentNodeId must not be blank."
        }

        require(
            destinationId.isNotBlank()
        ) {
            "destinationId must not be blank."
        }

        val eventTime =
            timeProvider()

        // =================================================
        // ROUTE REQUEST
        // =================================================

        telemetry.routeRequests++

        recordRoutingEvent(
            RoutingEventRecord(
                runId =
                    effectiveRunId(),

                messageId =
                    messageId,

                eventTime =
                    eventTime,

                nodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                eventType =
                    RoutingEventType.ROUTE_REQUEST
            )
        )

        // =================================================
        // ALREADY AT DESTINATION
        // =================================================

        if (
            currentNodeId ==
            destinationId
        ) {

            val path =
                listOf(
                    currentNodeId
                )

            telemetry.successfulRoutes++

            recordSuccessfulRoute(
                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                eventTime =
                    eventTime,

                path =
                    path,

                totalCost =
                    0.0,

                messageId =
                    messageId
            )

            return path
        }

        // =================================================
        // NODE VALIDATION
        // =================================================

        val currentNode =
            graph.getNode(
                currentNodeId
            )

        val destinationNode =
            graph.getNode(
                destinationId
            )

        if (
            currentNode == null ||
            destinationNode == null
        ) {

            preferredPaths.remove(
                currentNodeId to
                        destinationId
            )

            telemetry.unreachableRoutes++

            recordNoRoute(
                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                eventTime =
                    eventTime,

                messageId =
                    messageId
            )

            return null
        }

        // =================================================
        // FRESH MM ROUTE CALCULATION
        // =================================================

        telemetry.cacheMisses++
        telemetry.routeCalculations++

        val candidateRoute =
            routingEngine.findPath(

                sourceId =
                    currentNodeId,

                destinationId =
                    destinationId,

                neighborProvider = {
                        nodeId ->

                    graph
                        .getNeighbors(
                            nodeId
                        )
                        .map { edge ->
                            edge.to
                        }
                },

                linkStateProvider = {
                        fromNodeId,
                        toNodeId ->

                    getLinkState(
                        fromNodeId,
                        toNodeId
                    )
                }
            )

        // =================================================
        // NO ROUTE
        // =================================================

        if (
            candidateRoute == null
        ) {

            preferredPaths.remove(
                currentNodeId to
                        destinationId
            )

            telemetry.unreachableRoutes++

            recordNoRoute(
                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                eventTime =
                    eventTime,

                messageId =
                    messageId
            )

            return null
        }

        // =================================================
        // HYSTERESIS
        // =================================================

        val selectedRoute =
            applyHysteresis(
                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                candidatePath =
                    candidateRoute.path,

                candidateCost =
                    candidateRoute.totalCost
            )

        telemetry.successfulRoutes++

        recordSuccessfulRoute(
            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            eventTime =
                eventTime,

            path =
                selectedRoute.path,

            totalCost =
                selectedRoute.totalCost,

            messageId =
                messageId
        )

        return selectedRoute.path
    }

    // =====================================================
    // HYSTERESIS
    // =====================================================

    private data class SelectedRoute(
        val path: List<String>,
        val totalCost: Double
    )

    private fun applyHysteresis(
        currentNodeId: String,
        destinationId: String,
        candidatePath: List<String>,
        candidateCost: Double
    ): SelectedRoute {

        val key =
            currentNodeId to
                    destinationId

        val previousPath =
            preferredPaths[
                key
            ]

        // -------------------------------------------------
        // FIRST ROUTE
        // -------------------------------------------------

        if (
            previousPath == null
        ) {

            preferredPaths[key] =
                candidatePath.toList()

            return SelectedRoute(
                path =
                    candidatePath,

                totalCost =
                    candidateCost
            )
        }

        // -------------------------------------------------
        // SAME ROUTE
        // -------------------------------------------------

        if (
            previousPath ==
            candidatePath
        ) {

            preferredPaths[key] =
                candidatePath.toList()

            return SelectedRoute(
                path =
                    candidatePath,

                totalCost =
                    candidateCost
            )
        }

        // -------------------------------------------------
        // PREVIOUS ROUTE BECAME INVALID
        // -------------------------------------------------

        if (
            !isPathCurrentlyUsable(
                previousPath
            )
        ) {

            preferredPaths[key] =
                candidatePath.toList()

            return SelectedRoute(
                path =
                    candidatePath,

                totalCost =
                    candidateCost
            )
        }

        // -------------------------------------------------
        // RECALCULATE CURRENT COST OF PREVIOUS PATH
        // -------------------------------------------------

        val previousCurrentCost =
            calculateCurrentPathCost(
                previousPath
            )

        /*
         * This should normally exist because we already
         * checked path usability.
         *
         * If current metric state cannot be evaluated,
         * accept the valid newly calculated candidate.
         */
        if (
            previousCurrentCost == null
        ) {

            preferredPaths[key] =
                candidatePath.toList()

            return SelectedRoute(
                path =
                    candidatePath,

                totalCost =
                    candidateCost
            )
        }

        /*
         * H = 0.05:
         *
         * candidate must cost less than:
         *
         * previousCost × 0.95
         */
        val switchThreshold =
            previousCurrentCost *
                    (
                            1.0 -
                                    hysteresisFraction
                            )

        return if (
            candidateCost <
            switchThreshold
        ) {

            /*
             * Candidate is meaningfully better.
             */
            preferredPaths[key] =
                candidatePath.toList()

            SelectedRoute(
                path =
                    candidatePath,

                totalCost =
                    candidateCost
            )

        } else {

            /*
             * Difference is too small.
             *
             * Keep the previous still-valid route.
             */
            SelectedRoute(
                path =
                    previousPath,

                totalCost =
                    previousCurrentCost
            )
        }
    }

    // =====================================================
    // CURRENT PATH COST
    // =====================================================

    private fun calculateCurrentPathCost(
        path: List<String>
    ): Double? {

        if (
            path.size <= 1
        ) {
            return 0.0
        }

        var totalCost =
            0.0

        for (
        index in 0 until
                path.lastIndex
        ) {

            val fromNodeId =
                path[index]

            val toNodeId =
                path[
                    index + 1
                ]

            if (
                !graph.containsEdge(
                    fromNodeId,
                    toNodeId
                )
            ) {
                return null
            }

            val state =
                getLinkState(
                    fromNodeId,
                    toNodeId
                )

            totalCost +=
                costCalculator
                    .calculate(
                        state
                    )
                    .totalCost
        }

        return totalCost
    }

    // =====================================================
    // PATH AVAILABILITY
    // =====================================================

    private fun isPathCurrentlyUsable(
        path: List<String>
    ): Boolean {

        if (
            path.size <= 1
        ) {
            return true
        }

        for (
        index in 0 until
                path.lastIndex
        ) {

            if (
                !graph.containsEdge(
                    path[index],
                    path[
                        index + 1
                    ]
                )
            ) {

                return false
            }
        }

        return true
    }

    // =====================================================
    // LINK STATE
    // =====================================================

    private fun getLinkState(
        fromNodeId: String,
        toNodeId: String
    ): MultiMetricLinkState {

        return stateStore.get(
            fromNodeId,
            toNodeId
        )
            ?: defaultState(
                fromNodeId =
                    fromNodeId,

                toNodeId =
                    toNodeId
            )
    }

    // =====================================================
    // DEFAULT / BOOTSTRAP STATE
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

    // =====================================================
    // SUCCESSFUL ROUTE INSTRUMENTATION
    // =====================================================

    private fun recordSuccessfulRoute(
        currentNodeId: String,
        destinationId: String,
        eventTime: Long,
        path: List<String>,
        totalCost: Double,
        messageId: String?
    ) {

        val key =
            Pair(
                currentNodeId,
                destinationId
            )

        val previousPath =
            lastSuccessfulPaths[
                key
            ]

        val recordedCost =
            convertCostForCommonTelemetry(
                totalCost
            )

        // -------------------------------------------------
        // ROUTE FOUND
        // -------------------------------------------------

        recordRoutingEvent(
            RoutingEventRecord(
                runId =
                    effectiveRunId(),

                messageId =
                    messageId,

                eventTime =
                    eventTime,

                nodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                eventType =
                    RoutingEventType.ROUTE_FOUND,

                path =
                    path,

                totalCost =
                    recordedCost
            )
        )

        // -------------------------------------------------
        // ROUTE CHANGED
        // -------------------------------------------------

        if (
            previousPath != null &&
            previousPath != path
        ) {

            recordRoutingEvent(
                RoutingEventRecord(
                    runId =
                        effectiveRunId(),

                    messageId =
                        messageId,

                    eventTime =
                        eventTime,

                    nodeId =
                        currentNodeId,

                    destinationId =
                        destinationId,

                    eventType =
                        RoutingEventType.ROUTE_CHANGED,

                    path =
                        path,

                    totalCost =
                        recordedCost
                )
            )
        }

        lastSuccessfulPaths[
            key
        ] =
            path.toList()
    }

    // =====================================================
    // NO ROUTE
    // =====================================================

    private fun recordNoRoute(
        currentNodeId: String,
        destinationId: String,
        eventTime: Long,
        messageId: String?
    ) {

        recordRoutingEvent(
            RoutingEventRecord(
                runId =
                    effectiveRunId(),

                messageId =
                    messageId,

                eventTime =
                    eventTime,

                nodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                eventType =
                    RoutingEventType.NO_ROUTE
            )
        )
    }

    // =====================================================
    // COMMON TELEMETRY COST
    // =====================================================

    private fun convertCostForCommonTelemetry(
        cost: Double
    ): Int {

        require(
            cost >= 0.0
        ) {
            "MM route cost cannot be negative."
        }

        return (
                cost *
                        1_000_000.0
                )
            .roundToInt()
    }

    // =====================================================
    // INSTRUMENTATION
    // =====================================================

    private fun recordRoutingEvent(
        record: RoutingEventRecord
    ) {

        instrumentation
            ?.onRoutingEvent(
                record
            )
    }

    private fun effectiveRunId():
            String {

        return runId
            ?: "UNINSTRUMENTED"
    }
}