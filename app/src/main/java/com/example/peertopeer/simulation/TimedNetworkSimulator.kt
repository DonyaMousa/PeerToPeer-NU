package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.network.PacketState
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteDecision
import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.record.PacketRecord

class TimedNetworkSimulator(
    private val simulationEngine: SimulationEngine,
    private val eventDrivenLinkTransmitter: EventDrivenTimedLinkTransmitter,
    private val transmissionTelemetry:
    TimedTransmissionTelemetry = TimedTransmissionTelemetry(),
    private val runId: String? = null,
    private val instrumentation: ExperimentInstrumentation? = null
) {

    /*
     * Compatibility constructor.
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
        transmissionTelemetry = transmissionTelemetry,
        runId = null,
        instrumentation = null
    )

    private val nodes =
        mutableMapOf<String, TimedNetworkNode>()

    /*
     * Fixed routing.
     */
    private val routes =
        mutableMapOf<String, List<String>>()

    /*
     * B0 / MM dynamic routing.
     */
    private val dynamicRouteProviders =
        mutableMapOf<String, TimedRouteProvider>()

    /*
     * 2RH routing.
     *
     * Kept completely separate so B0/MM semantics remain
     * unchanged.
     */
    private val twoRegimeRouteProviders =
        mutableMapOf<String, TwoRegimeRouteProvider>()

    /*
     * Terminal results.
     */
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

        require(
            nodeId.isNotBlank()
        ) {
            "nodeId cannot be blank."
        }

        require(
            !nodes.containsKey(
                nodeId
            )
        ) {
            "Node $nodeId already exists."
        }

        val timedNode =
            TimedNetworkNode(

                nodeId =
                    nodeId,

                queueCapacity =
                    queueCapacity,

                serviceTime =
                    serviceTime,

                simulationEngine =
                    simulationEngine,

                onProcessed = {
                        processedNodeId,
                        packetState,
                        completionTime ->

                    handleProcessedPacket(

                        nodeId =
                            processedNodeId,

                        packetState =
                            packetState,

                        completionTime =
                            completionTime
                    )
                },

                runId =
                    runId,

                instrumentation =
                    instrumentation
            )

        nodes[
            nodeId
        ] =
            timedNode
    }


    // =====================================================
    // FIXED ROUTING
    // =====================================================

    fun setRoute(
        messageId: String,
        path: List<String>
    ) {

        require(
            messageId.isNotBlank()
        ) {
            "messageId cannot be blank."
        }

        require(
            path.size >= 2
        ) {
            "Route must contain at least source and destination."
        }

        routes[
            messageId
        ] =
            path.toList()

        /*
         * One packet uses one routing mode only.
         */
        dynamicRouteProviders.remove(
            messageId
        )

        twoRegimeRouteProviders
            .remove(
                messageId
            )
            ?.clearPacketState(
                messageId
            )
    }


    fun send(
        packet: Packet,
        path: List<String>
    ) {

        require(
            path.size >= 2
        ) {
            "Route must contain at least source and destination."
        }

        require(
            path.first() ==
                    packet.sourceId
        ) {
            "Route must start at packet source."
        }

        require(
            path.last() ==
                    packet.destinationId
        ) {
            "Route must end at packet destination."
        }

        setRoute(
            messageId =
                packet.messageId,

            path =
                path
        )

        val initialState =
            createInitialState(
                packet
            )

        scheduleHop(

            state =
                initialState,

            nextHopId =
                path[1],

            startTime =
                simulationEngine.currentTime
        )
    }


    // =====================================================
    // B0 / MM DYNAMIC ROUTING
    // =====================================================

    fun send(
        packet: Packet,
        routeProvider: TimedRouteProvider
    ) {

        dynamicRouteProviders[
            packet.messageId
        ] =
            routeProvider

        routes.remove(
            packet.messageId
        )

        twoRegimeRouteProviders
            .remove(
                packet.messageId
            )
            ?.clearPacketState(
                packet.messageId
            )

        val initialState =
            createInitialState(
                packet
            )

        val initialPath =
            routeProvider.findPath(

                currentNodeId =
                    packet.sourceId,

                destinationId =
                    packet.destinationId,

                messageId =
                    packet.messageId
            )

        if (
            !isValidDynamicPath(

                path =
                    initialPath,

                currentNodeId =
                    packet.sourceId,

                destinationId =
                    packet.destinationId
            )
        ) {

            recordDrop(

                packetState =
                    initialState,

                reason =
                    PacketDropReason.NO_ROUTE
            )

            return
        }

        scheduleHop(

            state =
                initialState,

            nextHopId =
                initialPath!![1],

            startTime =
                simulationEngine.currentTime
        )
    }


    // =====================================================
    // TWO-REGIME HYBRID ROUTING
    // =====================================================

    fun send(
        packet: Packet,
        routeProvider: TwoRegimeRouteProvider
    ) {

        twoRegimeRouteProviders[
            packet.messageId
        ] =
            routeProvider

        routes.remove(
            packet.messageId
        )

        dynamicRouteProviders.remove(
            packet.messageId
        )

        val initialState =
            createInitialState(
                packet
            )

        /*
         * Initial 2RH decision:
         *
         * HIGH -> Forward
         * LOW  -> Carry
         */
        val decision =
            routeProvider.decide(

                currentNodeId =
                    packet.sourceId,

                destinationId =
                    packet.destinationId,

                messageId =
                    packet.messageId
            )

        executeTwoRegimeDecision(

            packetState =
                initialState,

            currentNodeId =
                packet.sourceId,

            decisionTime =
                simulationEngine.currentTime,

            provider =
                routeProvider,

            decision =
                decision
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

        // -------------------------------------------------
        // DESTINATION
        // -------------------------------------------------

        if (
            nodeId ==
            packet.destinationId
        ) {

            val alreadyFinished =
                results.any {
                    it.messageId ==
                            packet.messageId
                }

            if (
                alreadyFinished
            ) {
                return
            }

            val result =
                TimedDeliveryResult(

                    messageId =
                        packet.messageId,

                    createdAt =
                        packet.createdAt,

                    deliveredAt =
                        completionTime,

                    droppedAt =
                        null,

                    delivered =
                        true,

                    dropped =
                        false,

                    dropReason =
                        null
                )

            results.add(
                result
            )

            recordPacketOutcome(

                packetState =
                    packetState,

                result =
                    result
            )

            clearRoutingState(
                packet.messageId
            )

            return
        }

        // -------------------------------------------------
        // TTL
        // -------------------------------------------------

        if (
            packetState.remainingTtl <= 0
        ) {

            recordDrop(

                packetState =
                    packetState,

                reason =
                    PacketDropReason.TTL_EXPIRED
            )

            return
        }

        // =================================================
        // 2RH
        // =================================================

        val twoRegimeProvider =
            twoRegimeRouteProviders[
                packet.messageId
            ]

        if (
            twoRegimeProvider != null
        ) {

            val decision =
                twoRegimeProvider.decide(

                    currentNodeId =
                        nodeId,

                    destinationId =
                        packet.destinationId,

                    messageId =
                        packet.messageId
                )

            executeTwoRegimeDecision(

                packetState =
                    packetState,

                currentNodeId =
                    nodeId,

                decisionTime =
                    completionTime,

                provider =
                    twoRegimeProvider,

                decision =
                    decision
            )

            return
        }

        // =================================================
        // EXISTING B0 / MM / FIXED ROUTING
        // =================================================

        val dynamicProvider =
            dynamicRouteProviders[
                packet.messageId
            ]

        val nextHopId =
            if (
                dynamicProvider != null
            ) {

                resolveDynamicNextHop(

                    provider =
                        dynamicProvider,

                    currentNodeId =
                        nodeId,

                    destinationId =
                        packet.destinationId,

                    messageId =
                        packet.messageId
                )

            } else {

                resolveFixedNextHop(

                    messageId =
                        packet.messageId,

                    currentNodeId =
                        nodeId
                )
            }

        if (
            nextHopId == null
        ) {

            recordDrop(

                packetState =
                    packetState,

                reason =
                    PacketDropReason.NO_ROUTE
            )

            return
        }

        scheduleHop(

            state =
                packetState,

            nextHopId =
                nextHopId,

            startTime =
                completionTime
        )
    }


    // =====================================================
    // 2RH DECISION EXECUTION
    // =====================================================

    private fun executeTwoRegimeDecision(
        packetState: PacketState,
        currentNodeId: String,
        decisionTime: Long,
        provider: TwoRegimeRouteProvider,
        decision: TwoRegimeRouteDecision
    ) {

        val packet =
            packetState.packet

        /*
         * Future Carry events may still be scheduled after
         * another event has already terminated the packet.
         */
        val alreadyFinished =
            results.any {
                it.messageId ==
                        packet.messageId
            }

        if (
            alreadyFinished
        ) {
            return
        }

        if (
            packetState.remainingTtl <= 0
        ) {

            recordDrop(

                packetState =
                    packetState,

                reason =
                    PacketDropReason.TTL_EXPIRED
            )

            return
        }

        /*
         * Exhaustive sealed-class handling.
         *
         * Forward
         * Carry
         * Probe
         * Drop
         */
        when (
            decision
        ) {

            // =============================================
            // HIGH
            // =============================================

            is TwoRegimeRouteDecision.Forward -> {

                val path =
                    decision.path

                if (
                    !isValidDynamicPath(

                        path =
                            path,

                        currentNodeId =
                            currentNodeId,

                        destinationId =
                            packet.destinationId
                    )
                ) {

                    recordDrop(

                        packetState =
                            packetState,

                        reason =
                            PacketDropReason.NO_ROUTE
                    )

                    return
                }

                scheduleHop(

                    state =
                        packetState,

                    nextHopId =
                        path[1],

                    startTime =
                        decisionTime
                )
            }


            // =============================================
            // LOW — CARRY
            // =============================================

            is TwoRegimeRouteDecision.Carry -> {

                val reevaluationTime =
                    decisionTime +
                            decision.reevaluationDelay

                /*
                 * No physical forwarding takes place during
                 * Carry, so hop TTL is not decremented.
                 *
                 * The LOW fallback budget bounds this wait.
                 */
                simulationEngine.schedule(
                    reevaluationTime
                ) {

                    val reevaluatedDecision =
                        provider.decideAfterCarry(

                            currentNodeId =
                                currentNodeId,

                            destinationId =
                                packet.destinationId,

                            messageId =
                                packet.messageId
                        )

                    executeTwoRegimeDecision(

                        packetState =
                            packetState,

                        currentNodeId =
                            currentNodeId,

                        decisionTime =
                            reevaluationTime,

                        provider =
                            provider,

                        decision =
                            reevaluatedDecision
                    )
                }
            }


            // =============================================
            // LOW — SINGLE BOUNDED PROBE
            // =============================================

            is TwoRegimeRouteDecision.Probe -> {

                val path =
                    decision.path

                /*
                 * A probe still requires an existing valid
                 * deterministic route.
                 */
                if (
                    !isValidDynamicPath(

                        path =
                            path,

                        currentNodeId =
                            currentNodeId,

                        destinationId =
                            packet.destinationId
                    )
                ) {

                    /*
                     * Route disappeared between decision and
                     * execution.
                     *
                     * Return to bounded LOW fallback.
                     */
                    val fallbackDecision =
                        provider.afterProbeFailure(

                            messageId =
                                packet.messageId,

                            confidence =
                                decision.confidence
                        )

                    executeTwoRegimeDecision(

                        packetState =
                            packetState,

                        currentNodeId =
                            currentNodeId,

                        decisionTime =
                            decisionTime,

                        provider =
                            provider,

                        decision =
                            fallbackDecision
                    )

                    return
                }

                scheduleProbe(

                    state =
                        packetState,

                    nextHopId =
                        path[1],

                    startTime =
                        decisionTime,

                    provider =
                        provider,

                    probeConfidence =
                        decision.confidence
                )
            }


            // =============================================
            // LOW — BUDGET EXHAUSTED
            // =============================================

            is TwoRegimeRouteDecision.Drop -> {

                /*
                 * Common frozen schema does not yet contain
                 * FALLBACK_EXHAUSTED.
                 *
                 * Therefore 2RH currently maps terminal LOW
                 * exhaustion to NO_ROUTE.
                 */
                recordDrop(

                    packetState =
                        packetState,

                    reason =
                        PacketDropReason.NO_ROUTE
                )
            }
        }
    }


    // =====================================================
    // LOW PROBE
    // =====================================================

    private fun scheduleProbe(
        state: PacketState,
        nextHopId: String,
        startTime: Long,
        provider: TwoRegimeRouteProvider,
        probeConfidence: Double
    ) {

        val packet =
            state.packet

        val nextNode =
            nodes[
                nextHopId
            ]

        /*
         * A probe cannot execute if the next simulated node
         * is unavailable.
         *
         * This is not terminal yet; it returns to LOW Carry.
         */
        if (
            nextNode == null
        ) {

            val fallbackDecision =
                provider.afterProbeFailure(

                    messageId =
                        packet.messageId,

                    confidence =
                        probeConfidence
                )

            executeTwoRegimeDecision(

                packetState =
                    state,

                currentNodeId =
                    state.currentNodeId,

                decisionTime =
                    startTime,

                provider =
                    provider,

                decision =
                    fallbackDecision
            )

            return
        }

        eventDrivenLinkTransmitter.transmit(

            fromNodeId =
                state.currentNodeId,

            toNodeId =
                nextHopId,

            messageId =
                packet.messageId,

            startTime =
                startTime

        ) {
                transmission,
                completionTime ->

            /*
             * Keep common physical transmission telemetry.
             *
             * MMInstrumentation also receives the physical
             * attempt evidence through the transmitter and
             * updates MultiMetricStateStore.
             */
            transmissionTelemetry.record(
                transmission
            )

            // ---------------------------------------------
            // PROBE PHYSICAL FAILURE
            // ---------------------------------------------

            if (
                !transmission.success
            ) {

                /*
                 * IMPORTANT:
                 *
                 * Normal HIGH forwarding terminates on retry
                 * exhaustion.
                 *
                 * LOW probe failure does NOT immediately
                 * terminate the packet.
                 *
                 * It generated fresh negative evidence and
                 * returns to bounded Carry.
                 */
                val fallbackDecision =
                    provider.afterProbeFailure(

                        messageId =
                            packet.messageId,

                        confidence =
                            probeConfidence
                    )

                executeTwoRegimeDecision(

                    packetState =
                        state,

                    currentNodeId =
                        state.currentNodeId,

                    decisionTime =
                        completionTime,

                    provider =
                        provider,

                    decision =
                        fallbackDecision
                )

                return@transmit
            }

            // ---------------------------------------------
            // TTL
            // ---------------------------------------------

            if (
                state.remainingTtl <= 0
            ) {

                recordDrop(

                    packetState =
                        state,

                    reason =
                        PacketDropReason.TTL_EXPIRED
                )

                return@transmit
            }

            /*
             * Successful probe actually moves the payload
             * one hop.
             *
             * This is still single-copy forwarding.
             */
            val forwardedState =
                state.forwardTo(
                    nextHopId
                )

            val accepted =
                nextNode.receive(
                    forwardedState
                )

            // ---------------------------------------------
            // NEXT-HOP QUEUE REJECTED PROBE
            // ---------------------------------------------

            if (
                !accepted
            ) {

                /*
                 * The forwarding opportunity existed, but
                 * the receiver was congested.
                 *
                 * Treat that as failed LOW recovery rather
                 * than immediately terminating the packet.
                 *
                 * Queue instrumentation supplies fresh
                 * congestion evidence.
                 */
                val fallbackDecision =
                    provider.afterProbeFailure(

                        messageId =
                            packet.messageId,

                        confidence =
                            probeConfidence
                    )

                /*
                 * The packet did not successfully enter the
                 * next node's queue, so continue carrying
                 * the ORIGINAL state at the current node.
                 */
                executeTwoRegimeDecision(

                    packetState =
                        state,

                    currentNodeId =
                        state.currentNodeId,

                    decisionTime =
                        completionTime,

                    provider =
                        provider,

                    decision =
                        fallbackDecision
                )

                return@transmit
            }

            /*
             * Probe successfully transmitted AND entered the
             * receiver queue.
             */
            provider.recordProbeSuccess(
                messageId =
                    packet.messageId
            )

            /*
             * Successful probe:
             *
             * packet is now inside the next node.
             *
             * TimedNetworkNode processing will eventually
             * call handleProcessedPacket(), where 2RH will
             * make a fresh regime decision from the new
             * location and updated observations.
             */
        }
    }


    // =====================================================
    // B0 / MM DYNAMIC NEXT-HOP RESOLUTION
    // =====================================================

    private fun resolveDynamicNextHop(
        provider: TimedRouteProvider,
        currentNodeId: String,
        destinationId: String,
        messageId: String
    ): String? {

        val path =
            provider.findPath(

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                messageId =
                    messageId
            )

        if (
            !isValidDynamicPath(

                path =
                    path,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId
            )
        ) {
            return null
        }

        return path!![1]
    }


    private fun isValidDynamicPath(
        path: List<String>?,
        currentNodeId: String,
        destinationId: String
    ): Boolean {

        if (
            path == null
        ) {
            return false
        }

        if (
            path.size < 2
        ) {
            return false
        }

        if (
            path.first() !=
            currentNodeId
        ) {
            return false
        }

        if (
            path.last() !=
            destinationId
        ) {
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
            routes[
                messageId
            ]
                ?: return null

        val currentIndex =
            path.indexOf(
                currentNodeId
            )

        if (
            currentIndex == -1 ||
            currentIndex >=
            path.lastIndex
        ) {
            return null
        }

        return path[
            currentIndex + 1
        ]
    }


    // =====================================================
    // NORMAL LINK TRANSMISSION
    // =====================================================

    /*
     * Used by:
     *
     * fixed routing
     * B0
     * MM
     * 2RH HIGH
     *
     * Existing behavior is deliberately preserved.
     */
    private fun scheduleHop(
        state: PacketState,
        nextHopId: String,
        startTime: Long
    ) {

        val nextNode =
            nodes[
                nextHopId
            ]

        if (
            nextNode == null
        ) {

            recordDrop(

                packetState =
                    state,

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
             * Normal forwarding retains the existing
             * terminal retry-exhaustion behavior.
             */
            if (
                !transmission.success
            ) {

                recordDrop(

                    packetState =
                        state,

                    reason =
                        PacketDropReason.RETRY_EXHAUSTED
                )

                return@transmit
            }

            if (
                state.remainingTtl <= 0
            ) {

                recordDrop(

                    packetState =
                        state,

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

            if (
                !accepted
            ) {

                recordDrop(

                    packetState =
                        forwardedState,

                    reason =
                        PacketDropReason.QUEUE_FULL
                )

                return@transmit
            }
        }
    }


    // =====================================================
    // PACKET STATE
    // =====================================================

    private fun createInitialState(
        packet: Packet
    ): PacketState {

        return PacketState(

            packet =
                packet,

            currentNodeId =
                packet.sourceId,

            remainingTtl =
                packet.ttl,

            hopCount =
                0
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
            packetState.packet
                .messageId

        val alreadyFinished =
            results.any {
                it.messageId ==
                        messageId
            }

        if (
            alreadyFinished
        ) {
            return
        }

        val result =
            TimedDeliveryResult(

                messageId =
                    messageId,

                createdAt =
                    packetState.packet
                        .createdAt,

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

        results.add(
            result
        )

        recordPacketOutcome(

            packetState =
                packetState,

            result =
                result
        )

        clearRoutingState(
            messageId
        )
    }


    // =====================================================
    // RESEARCH PACKET RECORDING
    // =====================================================

    private fun recordPacketOutcome(
        packetState: PacketState,
        result: TimedDeliveryResult
    ) {

        val activeInstrumentation =
            instrumentation
                ?: return

        val activeRunId =
            runId
                ?: return

        val packet =
            packetState.packet

        val failureTerminationTime =
            if (
                result.dropped
            ) {

                result.timeUntilTermination()

            } else {

                null
            }

        activeInstrumentation
            .onPacketFinished(

                PacketRecord(

                    runId =
                        activeRunId,

                    messageId =
                        packet.messageId,

                    sourceId =
                        packet.sourceId,

                    destinationId =
                        packet.destinationId,

                    createdAt =
                        packet.createdAt,

                    deliveredAt =
                        result.deliveredAt,

                    droppedAt =
                        result.droppedAt,

                    delivered =
                        result.delivered,

                    dropped =
                        result.dropped,

                    dropReason =
                        result.dropReason,

                    hopCount =
                        packetState.hopCount,

                    endToEndLatency =
                        result.endToEndLatency(),

                    terminationTime =
                        failureTerminationTime
                )
            )
    }


    // =====================================================
    // ROUTING STATE CLEANUP
    // =====================================================

    private fun clearRoutingState(
        messageId: String
    ) {

        routes.remove(
            messageId
        )

        dynamicRouteProviders.remove(
            messageId
        )

        val twoRegimeProvider =
            twoRegimeRouteProviders
                .remove(
                    messageId
                )

        twoRegimeProvider
            ?.clearPacketState(
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

        return nodes[
            nodeId
        ]
    }


    fun getTransmissionTelemetry():
            TimedTransmissionTelemetry {

        return transmissionTelemetry
    }
}