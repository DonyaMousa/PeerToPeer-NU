package com.example.peertopeer.simulation.experiment.prefailure

import com.example.peertopeer.routing.carble.CarbleRegimeEventRecord
import com.example.peertopeer.routing.carble.CarbleTelemetrySnapshot
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord

data class PreFailureResult(
    val runId: String,
    val seed: Long,
    val profile: PreFailureProfile,
    val packets: List<PacketRecord>,
    val transmissions: List<TransmissionRecord>,
    val regimeEvents: List<CarbleRegimeEventRecord>,
    val adaptation: CarbleTelemetrySnapshot
) {

    val generatedPackets: Int
        get() = packets.size

    val deliveredPackets: Int
        get() = packets.count { it.delivered }

    val droppedPackets: Int
        get() = packets.count { it.dropped }

    val packetDeliveryRatio: Double
        get() =
            if (generatedPackets == 0) {
                0.0
            } else {
                deliveredPackets.toDouble() /
                        generatedPackets.toDouble()
            }

    val meanLatency: Double
        get() {
            val values =
                packets.mapNotNull {
                    it.endToEndLatency
                }

            return if (values.isEmpty()) {
                0.0
            } else {
                values.average()
            }
        }

    val physicalAttempts: Long
        get() = transmissions.size.toLong()

    val retransmissions: Long
        get() =
            transmissions.count {
                it.attemptNumber > 1
            }.toLong()
}
