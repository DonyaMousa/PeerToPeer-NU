package com.example.peertopeer.simulation

fun interface TimedRouteProvider {

    fun findPath(
        currentNodeId: String,
        destinationId: String
    ): List<String>?
}
