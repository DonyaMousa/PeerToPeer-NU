package com.example.peertopeer.routing.mm

data class MultiMetricLinkState(

    val fromNodeId: String,
    val toNodeId: String,

    /*
     * Recent probability of a successful physical
     * transmission attempt.
     *
     * 1.0 = perfectly reliable
     * 0.0 = unusable
     */
    val successRate: Double,

    /*
     * Recently observed link / forwarding delay.
     *
     * Simulation time units for now.
     */
    val observedDelay: Double,

    /*
     * Reference delay used for normalization.
     */
    val delayReference: Double,

    /*
     * Queue state of the receiving/forwarding node.
     */
    val queueOccupancy: Int,
    val queueCapacity: Int,

    /*
     * Number of recent link-state changes.
     */
    val recentLinkChanges: Int,

    /*
     * Number of changes considered severe enough
     * to produce maximum instability penalty.
     */
    val instabilityReference: Int,

    /*
     * Resource penalty already normalized to [0, 1].
     *
     * During the initial simulator implementation
     * this stays 0.0 unless an explicit resource
     * experiment supplies a value.
     */
    val energyPenaltyNormalized: Double = 0.0

) {

    init {

        require(fromNodeId.isNotBlank())
        require(toNodeId.isNotBlank())

        require(successRate in 0.0..1.0) {
            "successRate must be between 0.0 and 1.0."
        }

        require(observedDelay >= 0.0)

        require(delayReference > 0.0) {
            "delayReference must be greater than zero."
        }

        require(queueCapacity > 0)

        require(
            queueOccupancy in 0..queueCapacity
        ) {
            "queueOccupancy must be between 0 and queueCapacity."
        }

        require(recentLinkChanges >= 0)

        require(instabilityReference > 0)

        require(
            energyPenaltyNormalized in 0.0..1.0
        )
    }
}
