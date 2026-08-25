package com.example.peertopeer.simulation

data class SimulationEvent(
    val scheduledTime: Long,
    val sequenceNumber: Long,
    val action: () -> Unit
) {

    init {
        require(scheduledTime >= 0L) {
            "scheduledTime cannot be negative."
        }

        require(sequenceNumber >= 0L) {
            "sequenceNumber cannot be negative."
        }
    }
}
