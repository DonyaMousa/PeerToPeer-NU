package com.example.peertopeer.simulation.experiment.config

data class TrafficConfig(

    /*
     * Number of application packets generated
     * during one independent experimental run.
     */
    val packetCount: Int,

    /*
     * Time between packet-generation events
     * in simulation time units.
     */
    val packetInterval: Long,

    /*
     * Packet TTL / forwarding-hop budget.
     */
    val packetTtl: Int,

    /*
     * Payload size recorded for experimental
     */
    val payloadBytes: Int,

    /*
     * Number of active traffic sources.
     */
    val sourceCount: Int = 1
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
    }
}
