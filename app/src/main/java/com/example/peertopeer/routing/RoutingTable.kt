package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node

class RoutingTable(
    private val graph: Graph,
    private val routingEngine: RoutingEngine,
    val telemetry: RoutingTelemetry = RoutingTelemetry()
) {
    private data class RouteKey(
        val sourceId: String,
        val destinationId: String
    )

    private val routes =
        mutableMapOf<RouteKey, RouteResult>()

    private var cachedTopologyVersion =
        graph.getTopologyVersion()

    fun getRoute(
        source: Node,
        destination: Node
    ): RouteResult? {

        telemetry.routeRequests++

        invalidateIfTopologyChanged()

        val key = RouteKey(
            sourceId = source.nodeId,
            destinationId = destination.nodeId
        )

        routes[key]?.let { cachedRoute ->

            telemetry.cacheHits++

            return cachedRoute
        }

        telemetry.cacheMisses++
        telemetry.routeCalculations++

        val calculatedRoute =
            routingEngine.findRoute(
                graph = graph,
                source = source,
                destination = destination
            )

        if (calculatedRoute == null) {

            telemetry.unreachableRoutes++

            return null
        }

        telemetry.successfulRoutes++

        routes[key] =
            calculatedRoute

        return calculatedRoute
    }

    fun clear() {
        routes.clear()

        cachedTopologyVersion =
            graph.getTopologyVersion()
    }

    fun size(): Int {
        invalidateIfTopologyChanged()

        return routes.size
    }

    private fun invalidateIfTopologyChanged() {

        val currentTopologyVersion =
            graph.getTopologyVersion()

        if (
            currentTopologyVersion !=
            cachedTopologyVersion
        ) {

            routes.clear()

            cachedTopologyVersion =
                currentTopologyVersion
            telemetry.cacheInvalidations++
        }
    }
}