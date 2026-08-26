package com.example.peertopeer.simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.routing.RoutingEngine
import com.example.peertopeer.routing.RoutingTable
import com.example.peertopeer.routing.RoutingTelemetry
import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventType

class B0DynamicRouteProvider(
    private val graph: Graph,
    routingEngine: RoutingEngine,
    private val runId: String? = null,
    private val instrumentation: ExperimentInstrumentation? = null,
    private val timeProvider: () -> Long = { 0L }
) : TimedRouteProvider {

    /*
     * Canonical B0 routing cache.
     *
     * Dijkstra is only executed on cache misses.
     * The table automatically invalidates itself
     * whenever Graph.topologyVersion changes.
     */
    private val routingTable =
        RoutingTable(
            graph = graph,
            routingEngine = routingEngine
        )

    /*
     * Last successful path observed for each:
     *
     * (current node, destination)
     *
     * Used only for route-change instrumentation.
     * It does not influence routing decisions.
     */
    private val lastSuccessfulPaths =
        mutableMapOf<
                Pair<String, String>,
                List<String>
                >()

    /*
     * Expose routing telemetry to the experiment runner.
     */
    val telemetry: RoutingTelemetry
        get() = routingTable.telemetry

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

        require(currentNodeId.isNotBlank()) {
            "currentNodeId must not be blank."
        }

        require(destinationId.isNotBlank()) {
            "destinationId must not be blank."
        }

        val eventTime =
            timeProvider()

        // =================================================
        // ROUTE REQUEST
        // =================================================

        recordRoutingEvent(
            RoutingEventRecord(
                runId = effectiveRunId(),
                messageId = messageId,
                eventTime = eventTime,
                nodeId = currentNodeId,
                destinationId = destinationId,
                eventType =
                    RoutingEventType.ROUTE_REQUEST
            )
        )

        // =================================================
        // ALREADY AT DESTINATION
        // =================================================

        if (currentNodeId == destinationId) {

            val path =
                listOf(
                    currentNodeId
                )

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
                    0,

                messageId =
                    messageId
            )

            return path
        }

        // =================================================
        // RESOLVE NODES
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
        // CACHED B0 ROUTING
        // =================================================

        val routeResult =
            routingTable.getRoute(
                source =
                    currentNode,

                destination =
                    destinationNode
            )

        if (routeResult == null) {

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

        val path =
            routeResult.path.map {
                it.nodeId
            }

        // =================================================
        // ROUTE FOUND + ROUTE CHANGE DETECTION
        // =================================================

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
                routeResult.totalCost,

            messageId =
                messageId
        )

        return path
    }

    // =====================================================
    // SUCCESSFUL ROUTE INSTRUMENTATION
    // =====================================================

    private fun recordSuccessfulRoute(
        currentNodeId: String,
        destinationId: String,
        eventTime: Long,
        path: List<String>,
        totalCost: Int,
        messageId: String?
    ) {

        val key =
            Pair(
                currentNodeId,
                destinationId
            )

        val previousPath =
            lastSuccessfulPaths[key]

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
                    totalCost
            )
        )

        // -------------------------------------------------
        // ROUTE CHANGED
        //
        // Initial discovery is not a route change.
        //
        // A route change is recorded only when a previous
        // successful route existed for this exact
        // (current node, destination) pair and the new
        // successful path differs.
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
                        totalCost
                )
            )
        }

        lastSuccessfulPaths[key] =
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

    private fun effectiveRunId(): String {

        return runId
            ?: "UNINSTRUMENTED"
    }
}