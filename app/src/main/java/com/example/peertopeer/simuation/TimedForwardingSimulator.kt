package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketState

class TimedForwardingSimulator(
    private val simulationEngine: SimulationEngine,
    private val relayNode: SimulatedServiceNode,
    private val relayToDestinationDelay: Long
) {

    init {
        require(relayToDestinationDelay >= 0L) {
            "relayToDestinationDelay cannot be negative."
        }
    }

    private val results =
        mutableListOf<TimedDeliveryResult>()

    fun sendThroughRelay(
        packet: Packet,
        relayNodeId: String
    ) {

        /*
         * We treat the packet as having
         * already completed the source -> relay hop.
         */
        val packetAtRelay =
            PacketState(
                packet = packet,
                currentNodeId = relayNodeId,
                remainingTtl = packet.ttl - 1,
                hopCount = 1
            )

        val accepted =
            relayNode.receive(
                packetAtRelay
            )

        /*
         * Relay queue overflow.
         */
        if (!accepted) {

            results.add(
                TimedDeliveryResult(
                    messageId = packet.messageId,
                    createdAt = packet.createdAt,
                    deliveredAt = null,
                    delivered = false,
                    dropped = true
                )
            )
        }
    }

    fun recordRelayProcessed(
        packetState: PacketState,
        completionTime: Long
    ) {

        /*
         * After B finishes processing,
         * schedule B -> D delivery.
         */
        val deliveryTime =
            completionTime +
                    relayToDestinationDelay

        simulationEngine.schedule(
            atTime = deliveryTime
        ) {

            results.add(
                TimedDeliveryResult(
                    messageId =
                        packetState.packet.messageId,
                    createdAt =
                        packetState.packet.createdAt,
                    deliveredAt =
                        deliveryTime,
                    delivered = true,
                    dropped = false
                )
            )
        }
    }

    fun getResults(): List<TimedDeliveryResult> {
        return results.toList()
    }
}
