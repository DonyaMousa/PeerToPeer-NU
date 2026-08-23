package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.network.PacketState

class TimedNetworkSimulator(
    private val simulationEngine: SimulationEngine,
    private val eventDrivenLinkTransmitter: EventDrivenTimedLinkTransmitter,
    private val transmissionTelemetry:
    TimedTransmissionTelemetry = TimedTransmissionTelemetry()
) {

    /*
     * Compatibility constructor.
     *
     * Existing tests using the old synchronous
     * TimedLinkTransmitter continue to work.
     */
    constructor(
        simulationEngine: SimulationEngine,
        linkTransmitter: TimedLinkTransmitter,
        transmissionTelemetry:
        TimedTransmissionTelemetry = TimedTransmissionTelemetry()
    ) : this(
        simulationEngine = simulationEngine,
        eventDrivenLinkTransmitter =
            LegacyEventDrivenTimedLinkAdapter(
                simulationEngine = simulationEngine,
                legacyTransmitter = linkTransmitter
            ),
        transmissionTelemetry = transmissionTelemetry
    )

    private val nodes =
        mutableMapOf<String, TimedNetworkNode>()

    /*
     * Legacy / fixed B0 routes.
     *
     * messageId -> complete route
     *
     * Example:
     * MSG-1 -> [A, B, D]
     */
    private val routes =
        mutableMapOf<String, List<String>>()

    /*
     * Dynamic routing mode.
     *
     * Instead of keeping one fixed path for the whole
     * packet lifetime, the simulator can ask a provider
     * for a fresh route at every forwarding decision.
     */
    private val dynamicRouteProviders =
        mutableMapOf<String, TimedRouteProvider>()

    private val results =
        mutableListOf<TimedDeliveryResult>()


    // =====================================================
    // NODES
    // =====================================================

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


    // =====================================================
    // FIXED ROUTING
    // =====================================================

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

        /*
         * A packet uses either fixed routing
         * OR dynamic routing.
         */
        dynamicRouteProviders.remove(messageId)
    }


    /*
     * Original send API.
     *
     * Existing tests continue using:
     *
     * simulator.send(
     *     packet,
     *     listOf("A", "B", "D")
     * )
     */
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

        val initialState =
            createInitialState(packet)

        val firstNextHop =
            path[1]

        scheduleHop(
            state = initialState,
            nextHopId = firstNextHop,
            startTime = simulationEngine.currentTime
        )
    }


    // =====================================================
    // DYNAMIC ROUTING
    // =====================================================

    /*
     * New send API.
     *
     * Usage:
     *
     * simulator.send(
     *     packet = packet,
     *     routeProvider = provider
     * )
     *
     * The provider will be consulted again after every
     * intermediate node processes the packet.
     */
    fun send(
        packet: Packet,
        routeProvider: TimedRouteProvider
    ) {

        dynamicRouteProviders[
            packet.messageId
        ] = routeProvider

        /*
         * Make sure an old fixed route cannot accidentally
         * remain attached to the same message ID.
         */
        routes.remove(
            packet.messageId
        )

        val initialState =
            createInitialState(packet)

        /*
         * Calculate the route using the graph as it exists
         * at the moment the packet is sent.
         */
        val initialPath =
            routeProvider.findPath(
                currentNodeId = packet.sourceId,
                destinationId = packet.destinationId
            )

        if (
            !isValidDynamicPath(
                path = initialPath,
                currentNodeId = packet.sourceId,
                destinationId = packet.destinationId
            )
        ) {

            recordDrop(
                packetState = initialState,
                reason = PacketDropReason.NO_ROUTE
            )

            return
        }

        val firstNextHop =
            initialPath!![1]

        scheduleHop(
            state = initialState,
            nextHopId = firstNextHop,
            startTime = simulationEngine.currentTime
        )
    }


    // =====================================================
    // PACKET PROCESSING
    // =====================================================

    private fun handleProcessedPacket(
        nodeId: String,
        packetState: PacketState,
        completionTime: Long
    ) {

        val packet =
            packetState.packet

        /*
         * Destination has completed processing.
         */
        if (
            nodeId ==
            packet.destinationId
        ) {

            results.add(
                TimedDeliveryResult(
                    messageId =
                        packet.messageId,
                    createdAt =
                        packet.createdAt,
                    deliveredAt =
                        completionTime,
                    delivered = true,
                    dropped = false
                )
            )

            clearRoutingState(
                packet.messageId
            )

            return
        }

        /*
         * Packet cannot continue forwarding.
         */
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

        /*
         * First check whether this packet is using
         * dynamic routing.
         */
        val dynamicProvider =
            dynamicRouteProviders[
                packet.messageId
            ]

        val nextHopId =
            if (dynamicProvider != null) {

                resolveDynamicNextHop(
                    provider = dynamicProvider,
                    currentNodeId = nodeId,
                    destinationId =
                        packet.destinationId
                )

            } else {

                resolveFixedNextHop(
                    messageId =
                        packet.messageId,
                    currentNodeId = nodeId
                )
            }

        /*
         * No usable route currently exists.
         *
         * For B0 this is terminal because B0 has no
         * store-carry-forward behavior.
         */
        if (nextHopId == null) {

            recordDrop(
                packetState = packetState,
                reason =
                    PacketDropReason.NO_ROUTE
            )

            return
        }

        scheduleHop(
            state = packetState,
            nextHopId = nextHopId,
            startTime = completionTime
        )
    }


    // =====================================================
    // DYNAMIC NEXT-HOP RESOLUTION
    // =====================================================

    private fun resolveDynamicNextHop(
        provider: TimedRouteProvider,
        currentNodeId: String,
        destinationId: String
    ): String? {

        /*
         * IMPORTANT:
         *
         * This asks Dijkstra for a NEW route using the
         * CURRENT graph.
         *
         * This is what allows an active packet to reroute.
         */
        val path =
            provider.findPath(
                currentNodeId =
                    currentNodeId,
                destinationId =
                    destinationId
            )

        if (
            !isValidDynamicPath(
                path = path,
                currentNodeId = currentNodeId,
                destinationId = destinationId
            )
        ) {
            return null
        }

        /*
         * path[0] = current node
         * path[1] = fresh next hop
         */
        return path!![1]
    }


    private fun isValidDynamicPath(
        path: List<String>?,
        currentNodeId: String,
        destinationId: String
    ): Boolean {

        if (path == null) {
            return false
        }

        if (path.size < 2) {
            return false
        }

        if (path.first() != currentNodeId) {
            return false
        }

        if (path.last() != destinationId) {
            return false
        }

        return true
    }


    // =====================================================
    // FIXED NEXT-HOP RESOLUTION
    // =====================================================

    private fun resolveFixedNextHop(
        messageId: String,
        currentNodeId: String
    ): String? {

        val path =
            routes[messageId]
                ?: return null

        val currentIndex =
            path.indexOf(currentNodeId)

        if (
            currentIndex == -1 ||
            currentIndex >= path.lastIndex
        ) {
            return null
        }

        return path[
            currentIndex + 1
        ]
    }


    // =====================================================
    // LINK TRANSMISSION
    // =====================================================

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

        eventDrivenLinkTransmitter.transmit(
            fromNodeId =
                state.currentNodeId,
            toNodeId =
                nextHopId,
            messageId =
                state.packet.messageId,
            startTime =
                startTime
        ) {
                transmission,
                completionTime ->

            transmissionTelemetry.record(
                transmission
            )

            /*
             * Physical hop exhausted its retry budget.
             */
            if (!transmission.success) {

                recordDrop(
                    packetState = state,
                    reason =
                        PacketDropReason.RETRY_EXHAUSTED
                )

                return@transmit
            }

            /*
             * Transmission succeeded.
             *
             * Check TTL before moving the packet.
             */
            if (
                state.remainingTtl <= 0
            ) {

                recordDrop(
                    packetState = state,
                    reason =
                        PacketDropReason.TTL_EXPIRED
                )

                return@transmit
            }

            val forwardedState =
                state.forwardTo(
                    nextHopId
                )

            val accepted =
                nextNode.receive(
                    forwardedState
                )

            if (!accepted) {

                recordDrop(
                    packetState =
                        forwardedState,
                    reason =
                        PacketDropReason.QUEUE_FULL
                )

                return@transmit
            }

            println(
                "t=$completionTime: " +
                        "${state.packet.messageId} " +
                        "${state.currentNodeId} -> $nextHopId " +
                        "after ${transmission.attempts} attempt(s)"
            )
        }
    }


    // =====================================================
    // PACKET STATE
    // =====================================================

    private fun createInitialState(
        packet: Packet
    ): PacketState {

        return PacketState(
            packet = packet,
            currentNodeId =
                packet.sourceId,
            remainingTtl =
                packet.ttl,
            hopCount = 0
        )
    }


    // =====================================================
    // TERMINATION
    // =====================================================

    private fun recordDrop(
        packetState: PacketState,
        reason: PacketDropReason
    ) {

        val messageId =
            packetState.packet.messageId

        val alreadyFinished =
            results.any {
                it.messageId ==
                        messageId
            }

        if (alreadyFinished) {
            return
        }

        results.add(
            TimedDeliveryResult(
                messageId =
                    messageId,
                createdAt =
                    packetState.packet.createdAt,
                deliveredAt =
                    null,
                droppedAt =
                    simulationEngine.currentTime,
                delivered =
                    false,
                dropped =
                    true,
                dropReason =
                    reason
            )
        )

        clearRoutingState(
            messageId
        )
    }


    private fun clearRoutingState(
        messageId: String
    ) {

        routes.remove(
            messageId
        )

        dynamicRouteProviders.remove(
            messageId
        )
    }


    // =====================================================
    // RESULTS / TELEMETRY
    // =====================================================

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