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
     * Expose a read-only reference to the telemetry object.
     *
     * The experiment runner can snapshot these values
     * after the run.
     */
    val telemetry: RoutingTelemetry
        get() = routingTable.telemetry

    override fun findPath(
        currentNodeId: String,
        destinationId: String
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
                messageId = null,
                eventTime = eventTime,
                nodeId = currentNodeId,
                destinationId = destinationId,
                eventType = RoutingEventType.ROUTE_REQUEST
            )
        )

        // =================================================
        // ALREADY AT DESTINATION
        // =================================================

        if (currentNodeId == destinationId) {

            val path =
                listOf(currentNodeId)

            recordRoutingEvent(
                RoutingEventRecord(
                    runId = effectiveRunId(),
                    messageId = null,
                    eventTime = eventTime,
                    nodeId = currentNodeId,
                    destinationId = destinationId,
                    eventType = RoutingEventType.ROUTE_FOUND,
                    path = path,
                    totalCost = 0
                )
            )

            return path
        }

        // =================================================
        // RESOLVE NODES
        // =================================================

        val currentNode =
            graph.getNode(currentNodeId)

        val destinationNode =
            graph.getNode(destinationId)

        if (
            currentNode == null ||
            destinationNode == null
        ) {

            recordNoRoute(
                currentNodeId = currentNodeId,
                destinationId = destinationId,
                eventTime = eventTime
            )

            return null
        }

        // =================================================
        // CACHED B0 ROUTING
        // =================================================

        /*
         * IMPORTANT:
         *
         * We no longer call routingEngine.findRoute()
         * directly.
         *
         * RoutingTable handles:
         *
         * route requests
         * cache hits
         * cache misses
         * Dijkstra calculations
         * topology invalidation
         * unreachable routes
         */
        val routeResult =
            routingTable.getRoute(
                source = currentNode,
                destination = destinationNode
            )

        if (routeResult == null) {

            recordNoRoute(
                currentNodeId = currentNodeId,
                destinationId = destinationId,
                eventTime = eventTime
            )

            return null
        }

        val path =
            routeResult.path.map {
                it.nodeId
            }

        // =================================================
        // ROUTE FOUND
        // =================================================

        recordRoutingEvent(
            RoutingEventRecord(
                runId = effectiveRunId(),
                messageId = null,
                eventTime = eventTime,
                nodeId = currentNodeId,
                destinationId = destinationId,
                eventType = RoutingEventType.ROUTE_FOUND,
                path = path,
                totalCost = routeResult.totalCost
            )
        )

        return path
    }

    private fun recordNoRoute(
        currentNodeId: String,
        destinationId: String,
        eventTime: Long
    ) {

        recordRoutingEvent(
            RoutingEventRecord(
                runId = effectiveRunId(),
                messageId = null,
                eventTime = eventTime,
                nodeId = currentNodeId,
                destinationId = destinationId,
                eventType = RoutingEventType.NO_ROUTE
            )
        )
    }

    private fun recordRoutingEvent(
        record: RoutingEventRecord
    ) {

        instrumentation
            ?.onRoutingEvent(record)
    }

    private fun effectiveRunId(): String {
        return runId ?: "UNINSTRUMENTED"
    }
}