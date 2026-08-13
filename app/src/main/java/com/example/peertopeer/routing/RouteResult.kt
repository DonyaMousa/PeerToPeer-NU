package com.example.peertopeer.routing
import com.example.peertopeer.domain.model.Node

data class RouteResult(
    val path: List<Node>,
    val totalCost: Int,
    val nextHop: Node?
)