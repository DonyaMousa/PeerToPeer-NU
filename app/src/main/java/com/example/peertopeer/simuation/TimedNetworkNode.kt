package com.example.peertopeer.simulation

import com.example.peertopeer.network.PacketState

class TimedNetworkNode(
    val nodeId: String,
    queueCapacity: Int,
    serviceTime: Long,
    simulationEngine: SimulationEngine,
    onProcessed: (
        nodeId: String,
        packetState: PacketState,
        completionTime: Long
    ) -> Unit
) {

    private val serviceNode =
        SimulatedServiceNode(
            nodeId = nodeId,
            queueCapacity = queueCapacity,
            serviceTime = serviceTime,
            simulationEngine = simulationEngine
        ) { packetState, completionTime ->

            onProcessed(
                nodeId,
                packetState,
                completionTime
            )
        }

    fun receive(
        packetState: PacketState
    ): Boolean {

        return serviceNode.receive(
            packetState
        )
    }

    val processedPackets: Int
        get() =
            serviceNode.processedPackets

    val droppedPackets: Int
        get() =
            serviceNode.droppedPackets

    val maxQueueSize: Int
        get() =
            serviceNode.maxQueueSize

    val maxQueueWaitingTime: Long
        get() =
            serviceNode.maxQueueWaitingTime

    fun averageQueueWaitingTime(): Double {

        return serviceNode
            .averageQueueWaitingTime()
    }
}
