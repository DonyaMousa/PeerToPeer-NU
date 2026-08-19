package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet

class TrafficGenerator(
    private val simulationEngine: SimulationEngine
) {

    fun schedulePackets(
        count: Int,
        startTime: Long,
        interval: Long,
        sourceId: String,
        destinationId: String,
        ttl: Int,
        onPacketGenerated: (Packet) -> Unit
    ) {

        require(count > 0) {
            "Packet count must be greater than zero."
        }

        require(startTime >= simulationEngine.currentTime) {
            "startTime cannot be in the past."
        }

        require(interval >= 0L) {
            "interval cannot be negative."
        }

        require(sourceId.isNotBlank()) {
            "sourceId cannot be blank."
        }

        require(destinationId.isNotBlank()) {
            "destinationId cannot be blank."
        }

        require(sourceId != destinationId) {
            "Source and destination must be different."
        }

        require(ttl > 0) {
            "TTL must be greater than zero."
        }

        for (index in 0 until count) {

            val generationTime =
                startTime +
                        (index * interval)

            simulationEngine.schedule(
                atTime = generationTime
            ) {

                val packet =
                    Packet(
                        messageId = "MSG-$sourceId-$index",
                        sourceId = sourceId,
                        destinationId = destinationId,
                        createdAt = generationTime,
                        ttl = ttl,
                        payload = "Message $index"
                    )

                onPacketGenerated(packet)
            }
        }
    }
}
