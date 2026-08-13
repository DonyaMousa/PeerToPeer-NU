package com.example.peertopeer.network

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.routing.RoutingTable

class B0ForwardingSimulator(
    private val graph: Graph,
    private val routingTable: RoutingTable,
    private val nodes: Map<String, SimulatedNode>,
    private val linkTransmitter: LinkTransmitter
) {

    fun send(
        packet: Packet
    ): ForwardingResult {

        val source =
            graph.getNode(packet.sourceId)
                ?: throw IllegalArgumentException(
                    "Unknown source node: ${packet.sourceId}"
                )

        val destination =
            graph.getNode(packet.destinationId)
                ?: throw IllegalArgumentException(
                    "Unknown destination node: ${packet.destinationId}"
                )

        val route =
            routingTable.getRoute(
                source = source,
                destination = destination
            )
                ?: return unreachableResult(packet)

        var state =
            PacketState(
                packet = packet,
                currentNodeId = packet.sourceId,
                remainingTtl = packet.ttl
            )

        val visitedNodes =
            mutableListOf(
                packet.sourceId
            )

        /*
         * Number of distinct hops that
         * were physically attempted.
         */
        var attemptedHops = 0

        /*
         * Number of hops that eventually
         * completed successfully.
         */
        var successfulHops = 0

        /*
         * Total physical transmission attempts,
         * including initial attempts + retries.
         */
        var transmissionAttempts = 0

        val sourceNode =
            nodes[packet.sourceId]
                ?: throw IllegalArgumentException(
                    "Missing simulated source node."
                )

        /*
         * Packet starts in the source queue.
         */
        if (!sourceNode.receive(state)) {

            return ForwardingResult(
                finalState =
                    state.markDropped(),
                visitedNodes =
                    visitedNodes.toList(),
                attemptedHops =
                    attemptedHops,
                successfulHops =
                    successfulHops,
                transmissionAttempts =
                    transmissionAttempts
            )
        }

        /*
         * Follow the route chosen by B0.
         */
        for (
        nextHop in route.path.drop(1)
        ) {

            val currentNode =
                nodes[state.currentNodeId]
                    ?: throw IllegalStateException(
                        "Missing simulated node: ${state.currentNodeId}"
                    )

            /*
             * Current node removes the packet
             * from its queue for transmission.
             */
            val queuedState =
                currentNode.nextPacket()
                    ?: throw IllegalStateException(
                        "Expected packet in ${state.currentNodeId} queue."
                    )

            /*
             * Packet cannot continue if
             * its TTL has been exhausted.
             */
            if (queuedState.remainingTtl <= 0) {

                return ForwardingResult(
                    finalState =
                        queuedState.markDropped(),
                    visitedNodes =
                        visitedNodes.toList(),
                    attemptedHops =
                        attemptedHops,
                    successfulHops =
                        successfulHops,
                    transmissionAttempts =
                        transmissionAttempts
                )
            }

            /*
             * We are now attempting one
             * distinct network hop.
             *
             * Example:
             *
             * A -> B
             *
             * Even if this requires 3 physical
             * attempts, it is still only
             * one attempted hop.
             */
            attemptedHops++

            /*
             * Perform physical transmission.
             *
             * RetryLinkTransmitter may perform
             * multiple attempts internally.
             */
            val transmissionResult =
                linkTransmitter.transmit(
                    fromNodeId =
                        queuedState.currentNodeId,
                    toNodeId =
                        nextHop.nodeId,
                    packetState =
                        queuedState
                )

            /*
             * Count every physical attempt.
             */
            transmissionAttempts +=
                transmissionResult.attempts

            /*
             * Retry budget exhausted.
             *
             * The hop was attempted,
             * but never completed.
             */
            if (!transmissionResult.success) {

                return ForwardingResult(
                    finalState =
                        queuedState.markDropped(),
                    visitedNodes =
                        visitedNodes.toList(),
                    attemptedHops =
                        attemptedHops,
                    successfulHops =
                        successfulHops,
                    transmissionAttempts =
                        transmissionAttempts
                )
            }

            /*
             * The hop succeeded.
             */
            state =
                queuedState.forwardTo(
                    nextNodeId =
                        nextHop.nodeId
                )

            successfulHops++

            visitedNodes.add(
                nextHop.nodeId
            )

            /*
             * Destination reached.
             */
            if (
                state.currentNodeId ==
                packet.destinationId
            ) {

                state =
                    state.markDelivered()

                return ForwardingResult(
                    finalState =
                        state,
                    visitedNodes =
                        visitedNodes.toList(),
                    attemptedHops =
                        attemptedHops,
                    successfulHops =
                        successfulHops,
                    transmissionAttempts =
                        transmissionAttempts
                )
            }

            /*
             * Intermediate relay receives
             * the successfully transmitted packet.
             */
            val receivingNode =
                nodes[nextHop.nodeId]
                    ?: throw IllegalStateException(
                        "Missing simulated node: ${nextHop.nodeId}"
                    )

            val accepted =
                receivingNode.receive(state)

            /*
             * Relay queue overflow.
             */
            if (!accepted) {

                return ForwardingResult(
                    finalState =
                        state.markDropped(),
                    visitedNodes =
                        visitedNodes.toList(),
                    attemptedHops =
                        attemptedHops,
                    successfulHops =
                        successfulHops,
                    transmissionAttempts =
                        transmissionAttempts
                )
            }
        }

        /*
         * Safety fallback.
         */
        return ForwardingResult(
            finalState =
                if (state.delivered) {
                    state
                } else {
                    state.markDropped()
                },
            visitedNodes =
                visitedNodes.toList(),
            attemptedHops =
                attemptedHops,
            successfulHops =
                successfulHops,
            transmissionAttempts =
                transmissionAttempts
        )
    }

    private fun unreachableResult(
        packet: Packet
    ): ForwardingResult {

        val state =
            PacketState(
                packet = packet,
                currentNodeId = packet.sourceId,
                remainingTtl = packet.ttl
            ).markDropped()

        /*
         * No route means no physical
         * hop was even attempted.
         */
        return ForwardingResult(
            finalState =
                state,
            visitedNodes =
                listOf(packet.sourceId),
            attemptedHops = 0,
            successfulHops = 0,
            transmissionAttempts = 0
        )
    }
}