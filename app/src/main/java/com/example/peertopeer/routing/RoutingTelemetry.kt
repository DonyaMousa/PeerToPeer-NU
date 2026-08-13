package com.example.peertopeer.routing

data class RoutingTelemetry(
    var routeRequests: Int = 0,
    var cacheHits: Int = 0,
    var cacheMisses: Int = 0,
    var routeCalculations: Int = 0,
    var cacheInvalidations: Int = 0,
    var successfulRoutes: Int = 0,
    var unreachableRoutes: Int = 0
)