package com.example.peertopeer.network

import com.example.peertopeer.domain.model.Node

class SimulatedNode(
    val node: Node,
    queueCapacity: Int
) {

    val queue =
        PacketQueue(
            capacity = queueCapacity
        )

    fun receive(
        packetState: PacketState
    ): Boolean {

        require(
            packetState.currentNodeId ==
                    node.nodeId
        ) {
            "Packet current node does not match receiving node."
        }

        return queue.enqueue(
            packetState
        )
    }

    fun nextPacket(): PacketState? {
        return queue.dequeue()
    }

    fun queuedPackets(): Int {
        return queue.size()
    }

    fun isQueueFull(): Boolean {
        return queue.isFull()
    }
}
