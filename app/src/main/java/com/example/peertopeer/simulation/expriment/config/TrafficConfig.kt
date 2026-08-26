package com.example.peertopeer.simulation.experiment.config

data class TrafficConfig(

    /*
     * Exact number of application packets generated
     * during one independent run.
     */
    val packetCount: Int,

    /*
     * Normal spacing between traffic opportunities.
     *
     * Simulation time units.
     */
    val packetInterval: Long,

    /*
     * Forwarding hop budget.
     */
    val packetTtl: Int,

    /*
     * Simulated application payload size.
     */
    val payloadBytes: Int,

    /*
     * Number of traffic sources.
     *
     * Current B0 scenarios use one source.
     */
    val sourceCount: Int = 1,

    /*
     * Probability that a normal traffic opportunity
     * becomes a burst.
     *
     * null = deterministic/non-bursty traffic.
     */
    val burstProbability: Double? = null,

    /*
     * Number of packets generated in a burst.
     *
     * The total packetCount remains exact.
     */
    val burstSize: Int? = null,

    /*
     * Spacing between packets belonging to the same burst.
     *
     * 0 means simultaneous generation.
     */
    val burstSpacing: Long? = null

) {

    init {

        require(packetCount > 0) {
            "packetCount must be greater than zero."
        }

        require(packetInterval >= 0) {
            "packetInterval cannot be negative."
        }

        require(packetTtl > 0) {
            "packetTtl must be greater than zero."
        }

        require(payloadBytes > 0) {
            "payloadBytes must be greater than zero."
        }

        require(sourceCount > 0) {
            "sourceCount must be greater than zero."
        }

        burstProbability?.let {
            require(it in 0.0..1.0) {
                "burstProbability must be between 0.0 and 1.0."
            }
        }

        burstSize?.let {
            require(it >= 2) {
                "burstSize must be at least 2 when configured."
            }
        }

        burstSpacing?.let {
            require(it >= 0L) {
                "burstSpacing cannot be negative."
            }
        }

        /*
         * Bursty traffic must be configured completely.
         */
        val burstFields =
            listOf(
                burstProbability,
                burstSize,
                burstSpacing
            )

        val configuredCount =
            burstFields.count {
                it != null
            }

        require(
            configuredCount == 0 ||
                    configuredCount == 3
        ) {
            "burstProbability, burstSize and burstSpacing " +
                    "must either all be configured or all be null."
        }
    }
}