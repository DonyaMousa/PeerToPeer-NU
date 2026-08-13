package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node

interface RoutingEngine {

    fun findRoute(
        graph: Graph,
        source: Node,
        destination: Node
    ): RouteResult?
}
