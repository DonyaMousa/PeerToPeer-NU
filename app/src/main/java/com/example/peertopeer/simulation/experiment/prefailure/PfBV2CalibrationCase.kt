package com.example.peertopeer.simulation.experiment.prefailure

data class PfBV2CalibrationCase(

    val caseId: String,

    /*
     * Queue/timeliness pressure only.
     *
     * Both candidate first hops use the SAME degradation
     * profile so MM cannot escape to a fully healthy first
     * hop while CARBLE still has a legitimate alternate.
     */
    val queueCapacity: Int,

    val serviceTime: Long,

    val packetInterval: Long,

    val packetsPerOpportunity: Int = 1
) {

    init {

        require(
            caseId.isNotBlank()
        )

        require(
            queueCapacity > 0
        )

        require(
            serviceTime > 0L
        )

        require(
            packetInterval > 0L
        )

        require(
            packetsPerOpportunity > 0
        )
    }
}
