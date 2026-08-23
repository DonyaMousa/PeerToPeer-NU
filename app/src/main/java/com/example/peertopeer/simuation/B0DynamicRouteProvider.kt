package com.example.peertopeer.simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.routing.RoutingEngine

class B0DynamicRouteProvider(
    private val graph: Graph,
    private val routingEngine: RoutingEngine
) : TimedRouteProvider {

    override fun findPath(
        currentNodeId: String,
        destinationId: String
    ): List<String>? {

        require(currentNodeId.isNotBlank()) {
            "currentNodeId must not be blank"
        }

        require(destinationId.isNotBlank()) {
            "destinationId must not be blank"
        }

        /*
         * If we are already at the destination,
         * there is no forwarding hop left.
         */
        if (currentNodeId == destinationId) {
            return listOf(currentNodeId)
        }

        val currentNode =
            graph.getNode(currentNodeId)
                ?: return null

        val destinationNode =
            graph.getNode(destinationId)
                ?: return null

        val routeResult =
            routingEngine.findRoute(
                graph = graph,
                source = currentNode,
                destination = destinationNode
            )
                ?: return null

        return routeResult.path.map {
            it.nodeId
        }
    }
}