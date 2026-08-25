package com.example.peertopeer.simulation.experiment.record

data class ResourceSampleRecord(
    val runId: String,
    val nodeId: String,
    val sampleTime: Long,

    /*
     * Simulation-side resource proxies.
     */
    val packetsTransmitted: Long = 0,
    val packetsReceived: Long = 0,
    val packetsForwarded: Long = 0,
    val physicalAttempts: Long = 0,
    val retransmissions: Long = 0,
    val queueOccupancy: Int = 0,
    val routingCalculations: Long = 0,

    /*
     * Physical-device fields.
     *
     * These remain null during pure simulation.
     */
    val batteryPercent: Double? = null,
    val batteryChargeMicroAh: Long? = null,
    val currentMicroAmp: Long? = null,
    val temperatureCelsius: Double? = null,
    val cpuUsagePercent: Double? = null,
    val memoryBytes: Long? = null
)
