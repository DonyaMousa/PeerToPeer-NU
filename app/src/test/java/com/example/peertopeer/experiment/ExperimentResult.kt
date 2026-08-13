package com.example.peertopeer.experiment

data class ExperimentResult(
    val experimentId: String,
    val routeRequests: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val routeCalculations: Int,
    val cacheInvalidations: Int,
    val successfulRoutes: Int,
    val unreachableRoutes: Int
)