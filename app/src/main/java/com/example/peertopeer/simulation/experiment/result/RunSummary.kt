package com.example.peertopeer.simulation.experiment.result

data class RunSummary(

    // -----------------------------------------------------
    // RUN IDENTITY
    // -----------------------------------------------------

    val runId: String,
    val protocol: String,
    val scenarioId: String,
    val seed: Long,
    val runIndex: Int,

    // -----------------------------------------------------
    // TRAFFIC
    // -----------------------------------------------------

    val generatedPackets: Int,
    val deliveredPackets: Int,
    val droppedPackets: Int,

    /*
     * Stored as 0.0 .. 1.0
     *
     * Example:
     * 0.90 = 90%
     */
    val packetDeliveryRatio: Double,

    // -----------------------------------------------------
    // LATENCY
    //
    // Delivered packets only.
    // -----------------------------------------------------

    val meanLatency: Double?,
    val p50Latency: Double?,
    val p95Latency: Long?,
    val p99Latency: Long?,
    val maxLatency: Long?,

    // -----------------------------------------------------
    // FAILED-PACKET TERMINATION
    //
    // Measured from packet creation until terminal drop.
    // -----------------------------------------------------

    val meanFailureTerminationTime: Double?,
    val maxFailureTerminationTime: Long?,

    // -----------------------------------------------------
    // DROP BREAKDOWN
    // -----------------------------------------------------

    val noRouteDrops: Int,
    val retryExhaustedDrops: Int,
    val queueFullDrops: Int,
    val ttlExpiredDrops: Int,
    val linkUnavailableDrops: Int,

    // -----------------------------------------------------
    // TRANSMISSION EFFICIENCY
    // -----------------------------------------------------

    /*
     * Number of distinct logical hop transmissions.
     *
     * Retries do not create new logical hops.
     */
    val logicalHopAttempts: Long,

    /*
     * Every actual physical attempt, including retries.
     */
    val physicalAttempts: Long,

    val successfulPhysicalAttempts: Long,
    val failedPhysicalAttempts: Long,

    /*
     * physicalAttempts - logicalHopAttempts
     *
     * under the current retry model.
     */
    val retransmissions: Long,

    /*
     * Simulation resource proxy only.
     *
     * This is NOT an energy measurement.
     */
    val physicalAttemptsPerDeliveredPacket: Double?,

    // -----------------------------------------------------
    // BYTE EFFICIENCY
    // -----------------------------------------------------

    /*
     * Application payload bytes that actually reached
     * their destinations.
     */
    val usefulDeliveredBytes: Long,

    /*
     * Resource proxy:
     *
     * physical attempts / useful delivered payload byte
     *
     * This is intentionally NOT called energy/byte.
     */
    val physicalAttemptsPerUsefulDeliveredByte: Double?,

    // -----------------------------------------------------
    // ROUTING
    // -----------------------------------------------------
    val routeRequests: Long,
    val routesFound: Long,
    val routeChanges: Long,
    val noRouteEvents: Long,

    val cacheHits: Long,
    val cacheMisses: Long,
    val routeCalculations: Long,
    val cacheInvalidations: Long,
    val successfulRouteCalculations: Long,
    val unreachableRouteCalculations: Long,

    // -----------------------------------------------------
    // TOPOLOGY
    // -----------------------------------------------------

    val topologyEvents: Long,
    val linkUpEvents: Long,
    val linkDownEvents: Long,
    val linkWeightChangeEvents: Long,

    // -----------------------------------------------------
    // QUEUE
    // -----------------------------------------------------

    val queueEnqueueEvents: Long,
    val queueDequeueEvents: Long,
    val queueFullEvents: Long,

    val maximumQueueOccupancy: Int?,
    val meanQueueWait: Double?,
    val p95QueueWait: Long?,
    val maxQueueWait: Long?,

    // -----------------------------------------------------
    // RESOURCE / SUSTAINABILITY PROXIES
    // -----------------------------------------------------

    /*
     * Maximum number of forwarding operations performed
     * by any one node.
     */
    val worstNodeForwardingBurden: Long?,

    /*
     * Mean forwarding burden across sampled nodes.
     */
    val meanNodeForwardingBurden: Double?,

    /*
     * Ratio:
     *
     * worst node forwarding burden
     * ----------------------------
     * mean node forwarding burden
     *
     * Larger values imply less-balanced relay work.
     *
     * This is a resource-burden balance indicator,
     * not a battery metric.
     */
    val forwardingBurdenImbalance: Double?,

    // -----------------------------------------------------
    // NOTES
    // -----------------------------------------------------

    val notes: String = ""
)
