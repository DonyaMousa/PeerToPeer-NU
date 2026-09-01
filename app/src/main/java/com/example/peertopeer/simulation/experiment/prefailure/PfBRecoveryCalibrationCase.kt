package com.example.peertopeer.simulation.experiment.prefailure

data class PfBRecoveryCalibrationCase(

    val caseId: String,

    /*
     * Success probability of the alternate N1-N3-N4 path.
     *
     * It remains fixed during one calibration run.
     */
    val backupLinkSuccessProbability: Double,

    /*
     * Queue / timing pressure is varied only at the
     * experiment level. CARBLE itself remains unchanged.
     */
    val queueCapacity: Int,

    val serviceTime: Long,

    val packetInterval: Long,

    /*
     * Number of packets generated at each traffic
     * opportunity. 1 = no burst.
     */
    val packetsPerOpportunity: Int = 1
) {

    init {

        require(
            caseId.isNotBlank()
        )

        require(
            backupLinkSuccessProbability in 0.0..1.0
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
