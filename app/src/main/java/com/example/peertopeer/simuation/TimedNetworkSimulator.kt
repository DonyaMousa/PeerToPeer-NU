package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.network.PacketState

class TimedNetworkSimulator(
    private val simulationEngine: SimulationEngine,
    private val linkTransmitter: TimedLinkTransmitter,
    private val transmissionTelemetry:
    TimedTransmissionTelemetry =
        TimedTransmissionTelemetry()
) {

    private val nodes =
        mutableMapOf<String, TimedNetworkNode>()

    private val routes =
        mutableMapOf<String, List<String>>()

    private val results =
        mutableListOf<TimedDeliveryResult>()

    fun addNode(
        nodeId: String,
        queueCapacity: Int,
        serviceTime: Long
    ) {

        require(nodeId.isNotBlank()) {
            "nodeId cannot be blank."
        }

        require(!nodes.containsKey(nodeId)) {
            "Node $nodeId already exists."
        }

        val timedNode =
            TimedNetworkNode(
                nodeId = nodeId,
                queueCapacity = queueCapacity,
                serviceTime = serviceTime,
                simulationEngine = simulationEngine
            ) {
                    processedNodeId,
                    packetState,
                    completionTime ->

                handleProcessedPacket(
                    nodeId = processedNodeId,
                    packetState = packetState,
                    completionTime = completionTime
                )
            }

        nodes[nodeId] =
            timedNode
    }

    fun setRoute(
        messageId: String,
        path: List<String>
    ) {

        require(messageId.isNotBlank()) {
            "messageId cannot be blank."
        }

        require(path.size >= 2) {
            "Route must contain at least source and destination."
        }

        routes[messageId] =
            path.toList()
    }

    fun send(
        packet: Packet,
        path: List<String>
    ) {

        require(path.size >= 2) {
            "Route must contain at least source and destination."
        }

        require(path.first() == packet.sourceId) {
            "Route must start at packet source."
        }

        require(path.last() == packet.destinationId) {
            "Route must end at packet destination."
        }

        setRoute(
            messageId = packet.messageId,
            path = path
        )

        val firstNextHop =
            path[1]

        val initialState =
            PacketState(
                packet = packet,
                currentNodeId = packet.sourceId,
                remainingTtl = packet.ttl,
                hopCount = 0
            )

        scheduleHop(
            state = initialState,
            nextHopId = firstNextHop,
            startTime = simulationEngine.currentTime
        )
    }

    private fun handleProcessedPacket(
        nodeId: String,
        packetState: PacketState,
        completionTime: Long
    ) {

        /*
         * Destination has completed processing.
         */
        if (
            nodeId ==
            packetState.packet.destinationId
        ) {

            results.add(
                TimedDeliveryResult(
                    messageId =
                        packetState.packet.messageId,
                    createdAt =
                        packetState.packet.createdAt,
                    deliveredAt =
                        completionTime,
                    delivered = true,
                    dropped = false
                )
            )

            return
        }

        val path =
            routes[
                packetState.packet.messageId
            ]

        if (path == null) {

            recordDrop(
                packetState = packetState,
                reason =
                    PacketDropReason.NO_ROUTE
            )

            return
        }

        val currentIndex =
            path.indexOf(nodeId)

        if (
            currentIndex == -1 ||
            currentIndex >= path.lastIndex
        ) {

            recordDrop(
                packetState = packetState,
                reason =
                    PacketDropReason.NO_ROUTE
            )

            return
        }

        if (
            packetState.remainingTtl <= 0
        ) {

            recordDrop(
                packetState = packetState,
                reason =
                    PacketDropReason.TTL_EXPIRED
            )

            return
        }

        val nextHopId =
            path[
                currentIndex + 1
            ]

        scheduleHop(
            state = packetState,
            nextHopId = nextHopId,
            startTime = completionTime
        )
    }

    private fun scheduleHop(
        state: PacketState,
        nextHopId: String,
        startTime: Long
    ) {

        val nextNode =
            nodes[nextHopId]

        if (nextNode == null) {

            recordDrop(
                packetState = state,
                reason =
                    PacketDropReason.LINK_UNAVAILABLE
            )

            return
        }

        /*
         * Ask the timed transmitter what happens
         * on this hop.
         */
        val transmission =
            linkTransmitter.transmit(
                fromNodeId =
                    state.currentNodeId,
                toNodeId =
                    nextHopId,
                messageId =
                    state.packet.messageId
            )
        transmissionTelemetry.record(
            transmission
        )

        /*
         * All retry attempts failed.
         */
        if (!transmission.success) {

            recordDrop(
                packetState = state,
                reason =
                    PacketDropReason.RETRY_EXHAUSTED
            )

            return
        }

        /*
         * The arrival time now includes the
         * physical attempt/retry delay.
         */
        val arrivalTime =
            startTime +
                    transmission.totalDelay

        simulationEngine.schedule(
            atTime = arrivalTime
        ) {

            if (
                state.remainingTtl <= 0
            ) {

                recordDrop(
                    packetState = state,
                    reason =
                        PacketDropReason.TTL_EXPIRED
                )

                return@schedule
            }

            val forwardedState =
                state.forwardTo(
                    nextNodeId = nextHopId
                )

            val accepted =
                nextNode.receive(
                    forwardedState
                )

            if (!accepted) {

                recordDrop(
                    packetState = forwardedState,
                    reason =
                        PacketDropReason.QUEUE_FULL
                )
            }
        }
    }

    private fun recordDrop(
        packetState: PacketState,
        reason: PacketDropReason
    ) {

        /*
         * Prevent the same packet from being
         * recorded as finished more than once.
         */
        val alreadyFinished =
            results.any {
                it.messageId ==
                        packetState.packet.messageId
            }

        if (alreadyFinished) {
            return
        }

        results.add(
            TimedDeliveryResult(
                messageId =
                    packetState.packet.messageId,
                createdAt =
                    packetState.packet.createdAt,
                deliveredAt = null,
                delivered = false,
                dropped = true,
                dropReason = reason
            )
        )
    }

    fun getResults():
            List<TimedDeliveryResult> {

        return results.toList()
    }

    fun getNode(
        nodeId: String
    ): TimedNetworkNode? {

        return nodes[nodeId]
    }
    fun getTransmissionTelemetry():
            TimedTransmissionTelemetry {

        return transmissionTelemetry
    }
}