package com.example.peertopeer.simulation.experiment.prefailure

data class PreFailurePhase(
    val phaseIndex: Int,
    val startTime: Long,
    val endTimeExclusive: Long,
    val successProbability: Double
) {
    init {
        require(phaseIndex >= 1)
        require(startTime >= 0L)
        require(endTimeExclusive > startTime)
        require(successProbability in 0.0..1.0)
    }

    fun contains(time: Long): Boolean {
        return time >= startTime &&
                time < endTimeExclusive
    }
}
