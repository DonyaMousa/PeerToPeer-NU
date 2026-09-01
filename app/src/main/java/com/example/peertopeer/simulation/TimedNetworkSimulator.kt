package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.network.PacketState
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteDecision
import com.example.peertopeer.routing.carble.CarbleRegime
import com.example.peertopeer.routing.carble.CarbleRouteDecision
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
 * CARBLE routing.
 *
 * Separate from B0/MM/2RH so their frozen execution
 * semantics remain untouched.
 */
    private val carbleRouteProviders =
        mutableMapOf<String, CarbleRouteProvider>()

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
        carbleRouteProviders
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
        carbleRouteProviders
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
        carbleRouteProviders
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
// CARBLE ROUTING
// =====================================================

    fun send(
        packet: Packet,
        routeProvider: CarbleRouteProvider
    ) {

        /*
         * Clear any old CARBLE state associated with an
         * accidentally reused message ID.
         */
        carbleRouteProviders
            .remove(
                packet.messageId
            )
            ?.clearPacketState(
                packet.messageId
            )


        carbleRouteProviders[
            packet.messageId
        ] =
            routeProvider


        /*
         * One packet uses exactly one routing mode.
         */
        routes.remove(
            packet.messageId
        )

        dynamicRouteProviders.remove(
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


        val decision =
            routeProvider.decide(

                currentNodeId =
                    packet.sourceId,

                destinationId =
                    packet.destinationId,

                messageId =
                    packet.messageId
            )


        executeCarbleDecision(

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
// CARBLE
// =================================================

        val carbleProvider =
            carbleRouteProviders[
                packet.messageId
            ]

        if (
            carbleProvider != null
        ) {

            val decision =
                carbleProvider.decide(

                    currentNodeId =
                        nodeId,

                    destinationId =
                        packet.destinationId,

                    messageId =
                        packet.messageId
                )


            executeCarbleDecision(

                packetState =
                    packetState,

                currentNodeId =
                    nodeId,

                decisionTime =
                    completionTime,

                provider =
                    carbleProvider,

                decision =
                    decision
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
// CARBLE DECISION EXECUTION
// =====================================================

    private fun executeCarbleDecision(
        packetState: PacketState,
        currentNodeId: String,
        decisionTime: Long,
        provider: CarbleRouteProvider,
        decision: CarbleRouteDecision
    ) {

        val packet =
            packetState.packet


        /*
         * Delayed M3 / Carry events may still exist after a
         * packet has already terminated.
         */
        if (
            isPacketFinished(
                packet.messageId
            )
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


        when (
            decision
        ) {

            // =================================================
            // HIGH / M1
            // =================================================

            is CarbleRouteDecision.Forward -> {

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

                    /*
                     * HIGH retains deterministic terminal
                     * behavior.
                     *
                     * M1 is already in the resilience region,
                     * so failure escalates into bounded LOW.
                     */
                    if (
                        decision.regime ==
                        CarbleRegime.HIGH
                    ) {

                        recordDrop(

                            packetState =
                                packetState,

                            reason =
                                PacketDropReason.NO_ROUTE
                        )

                    } else {

                        val fallbackDecision =
                            provider.afterMediumFailure(

                                messageId =
                                    packet.messageId,

                                confidence =
                                    decision
                                        .currentHopConfidence
                            )


                        executeCarbleDecision(

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
                    }

                    return
                }


                scheduleCarbleForward(

                    state =
                        packetState,

                    nextHopId =
                        path[1],

                    startTime =
                        decisionTime,

                    provider =
                        provider,

                    decision =
                        decision
                )
            }


            // =================================================
            // M2
            // =================================================

            is CarbleRouteDecision
            .ForwardWithFailover -> {

                scheduleCarbleM2(

                    state =
                        packetState,

                    currentNodeId =
                        currentNodeId,

                    startTime =
                        decisionTime,

                    provider =
                        provider,

                    decision =
                        decision
                )
            }


            // =================================================
            // M3
            // =================================================

            is CarbleRouteDecision
            .ForwardWithDelayedBackup -> {

                scheduleCarbleM3(

                    state =
                        packetState,

                    currentNodeId =
                        currentNodeId,

                    startTime =
                        decisionTime,

                    provider =
                        provider,

                    decision =
                        decision
                )
            }


            // =================================================
            // LOW — CARRY
            // =================================================

            is CarbleRouteDecision.Carry -> {

                val reevaluationTime =
                    decisionTime +
                            decision.reevaluationDelay


                /*
                 * Carry consumes simulation time but does not
                 * move the packet and therefore does not
                 * consume hop TTL.
                 */
                simulationEngine.schedule(
                    reevaluationTime
                ) {

                    if (
                        isPacketFinished(
                            packet.messageId
                        )
                    ) {

                        return@schedule
                    }


                    val reevaluatedDecision =
                        provider.decideAfterCarry(

                            currentNodeId =
                                currentNodeId,

                            destinationId =
                                packet.destinationId,

                            messageId =
                                packet.messageId
                        )


                    executeCarbleDecision(

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


            // =================================================
            // LOW — PROBE
            // =================================================

            is CarbleRouteDecision.Probe -> {

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

                    val fallbackDecision =
                        provider.afterProbeFailure(

                            messageId =
                                packet.messageId,

                            confidence =
                                decision.confidence
                        )


                    executeCarbleDecision(

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


                scheduleCarbleProbe(

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


            // =================================================
            // TERMINAL FALLBACK
            // =================================================

            is CarbleRouteDecision.Drop -> {

                /*
                 * Preserve the common frozen packet-drop
                 * schema.
                 *
                 * Bounded CARBLE fallback exhaustion currently
                 * maps to NO_ROUTE, just like frozen 2RH.
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
// CARBLE HIGH / M1 FORWARD
// =====================================================

    private fun scheduleCarbleForward(
        state: PacketState,
        nextHopId: String,
        startTime: Long,
        provider: CarbleRouteProvider,
        decision: CarbleRouteDecision.Forward
    ) {

        val packet =
            state.packet


        val nextNode =
            nodes[
                nextHopId
            ]


        // -------------------------------------------------
        // NEXT NODE UNAVAILABLE
        // -------------------------------------------------

        if (
            nextNode == null
        ) {

            if (
                decision.regime ==
                CarbleRegime.HIGH
            ) {

                recordDrop(

                    packetState =
                        state,

                    reason =
                        PacketDropReason.LINK_UNAVAILABLE
                )

            } else {

                /*
                 * M1 is already MEDIUM.
                 *
                 * No usable local forwarding opportunity
                 * exists, so escalate into bounded LOW.
                 */
                val fallbackDecision =
                    provider.afterMediumFailure(

                        messageId =
                            packet.messageId,

                        confidence =
                            decision
                                .currentHopConfidence
                    )


                executeCarbleDecision(

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
            }

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


            transmissionTelemetry.record(
                transmission
            )


            // ---------------------------------------------
            // PHYSICAL FAILURE
            // ---------------------------------------------

            if (
                !transmission.success
            ) {

                if (
                    decision.regime ==
                    CarbleRegime.HIGH
                ) {

                    /*
                     * HIGH remains frozen-MM deterministic
                     * behavior.
                     */
                    recordDrop(

                        packetState =
                            state,

                        reason =
                            PacketDropReason.RETRY_EXHAUSTED
                    )

                } else {

                    /*
                     * M1 failure produced fresh negative
                     * evidence through MMInstrumentation.
                     *
                     * Re-evaluate CARBLE immediately.
                     *
                     * Q may now become M2, M3 or LOW.
                     */
                    val reevaluatedDecision =
                        provider.decide(

                            currentNodeId =
                                state.currentNodeId,

                            destinationId =
                                packet.destinationId,

                            messageId =
                                packet.messageId
                        )


                    executeCarbleDecision(

                        packetState =
                            state,

                        currentNodeId =
                            state.currentNodeId,

                        decisionTime =
                            completionTime,

                        provider =
                            provider,

                        decision =
                            reevaluatedDecision
                    )
                }

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


            val forwardedState =
                state.forwardTo(
                    nextHopId
                )


            val accepted =
                nextNode.receive(
                    forwardedState
                )


            // ---------------------------------------------
            // QUEUE REJECTION
            // ---------------------------------------------

            if (
                !accepted
            ) {

                if (
                    decision.regime ==
                    CarbleRegime.HIGH
                ) {

                    recordDrop(

                        packetState =
                            forwardedState,

                        reason =
                            PacketDropReason.QUEUE_FULL
                    )

                } else {

                    /*
                     * M1 uses the fresh queue evidence to make
                     * another CARBLE decision from the current
                     * node.
                     *
                     * The packet never entered the receiver
                     * queue, so retain the original state.
                     */
                    val reevaluatedDecision =
                        provider.decide(

                            currentNodeId =
                                state.currentNodeId,

                            destinationId =
                                packet.destinationId,

                            messageId =
                                packet.messageId
                        )


                    executeCarbleDecision(

                        packetState =
                            state,

                        currentNodeId =
                            state.currentNodeId,

                        decisionTime =
                            completionTime,

                        provider =
                            provider,

                        decision =
                            reevaluatedDecision
                    )
                }

                return@transmit
            }


            /*
             * Actual successful progress.
             */
            provider.recordForwardProgress(

                messageId =
                    packet.messageId,

                fromNodeId =
                    state.currentNodeId,

                toNodeId =
                    nextHopId
            )
        }
    }
    // =====================================================
// CARBLE M2 — SEQUENTIAL FAILOVER
// =====================================================

    private fun scheduleCarbleM2(
        state: PacketState,
        currentNodeId: String,
        startTime: Long,
        provider: CarbleRouteProvider,
        decision:
        CarbleRouteDecision.ForwardWithFailover
    ) {

        val packet =
            state.packet


        // =================================================
        // BOTH PRIMARY + BACKUP FAILED
        // =================================================

        fun escalateToLow(
            failureTime: Long
        ) {

            if (
                isPacketFinished(
                    packet.messageId
                )
            ) {
                return
            }


            val fallbackDecision =
                provider.afterMediumFailure(

                    messageId =
                        packet.messageId,

                    confidence =
                        decision
                            .currentHopConfidence
                )


            executeCarbleDecision(

                packetState =
                    state,

                currentNodeId =
                    currentNodeId,

                decisionTime =
                    failureTime,

                provider =
                    provider,

                decision =
                    fallbackDecision
            )
        }


        // =================================================
        // BACKUP
        // =================================================

        fun launchBackup(
            backupStartTime: Long
        ) {

            val backupPath =
                decision.backupPath


            /*
             * No valid alternate was prepared.
             */
            if (
                backupPath == null
            ) {

                escalateToLow(
                    backupStartTime
                )

                return
            }


            if (
                !isValidDynamicPath(

                    path =
                        backupPath,

                    currentNodeId =
                        currentNodeId,

                    destinationId =
                        packet.destinationId
                )
            ) {

                escalateToLow(
                    backupStartTime
                )

                return
            }


            val backupNextHopId =
                backupPath[1]


            val backupNode =
                nodes[
                    backupNextHopId
                ]


            if (
                backupNode == null
            ) {

                escalateToLow(
                    backupStartTime
                )

                return
            }


            /*
             * The backup is NOW physically activated.
             *
             * This consumes CARBLE's one-copy budget.
             */
            provider.recordBackupActivation(
                packet.messageId
            )


            eventDrivenLinkTransmitter.transmit(

                fromNodeId =
                    currentNodeId,

                toNodeId =
                    backupNextHopId,

                messageId =
                    packet.messageId,

                startTime =
                    backupStartTime

            ) {
                    transmission,
                    completionTime ->


                transmissionTelemetry.record(
                    transmission
                )


                // -----------------------------------------
                // BACKUP PHYSICAL FAILURE
                // -----------------------------------------

                if (
                    !transmission.success
                ) {

                    provider.recordBackupFailure(
                        packet.messageId
                    )

                    escalateToLow(
                        completionTime
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
                        backupNextHopId
                    )


                val accepted =
                    backupNode.receive(
                        forwardedState
                    )


                // -----------------------------------------
                // BACKUP QUEUE FAILURE
                // -----------------------------------------

                if (
                    !accepted
                ) {

                    provider.recordBackupFailure(
                        packet.messageId
                    )

                    escalateToLow(
                        completionTime
                    )

                    return@transmit
                }


                /*
                 * Sequential M2 has no concurrent primary at
                 * this point, but use the same winner model.
                 *
                 * isBackup=true also records backup success.
                 */
                provider.tryClaimForwardingWinner(

                    messageId =
                        packet.messageId,

                    nextHopId =
                        backupNextHopId,

                    isBackup =
                        true
                )


                provider.recordForwardProgress(

                    messageId =
                        packet.messageId,

                    fromNodeId =
                        currentNodeId,

                    toNodeId =
                        backupNextHopId
                )
            }
        }


        // =================================================
        // PRIMARY
        // =================================================

        val primaryPath =
            decision.primaryPath


        if (
            !isValidDynamicPath(

                path =
                    primaryPath,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    packet.destinationId
            )
        ) {

            launchBackup(
                startTime
            )

            return
        }


        val primaryNextHopId =
            primaryPath[1]


        val primaryNode =
            nodes[
                primaryNextHopId
            ]


        if (
            primaryNode == null
        ) {

            launchBackup(
                startTime
            )

            return
        }


        eventDrivenLinkTransmitter.transmit(

            fromNodeId =
                currentNodeId,

            toNodeId =
                primaryNextHopId,

            messageId =
                packet.messageId,

            startTime =
                startTime

        ) {
                transmission,
                completionTime ->


            transmissionTelemetry.record(
                transmission
            )


            // ---------------------------------------------
            // PRIMARY FAILED → BACKUP
            // ---------------------------------------------

            if (
                !transmission.success
            ) {

                launchBackup(
                    completionTime
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
                    primaryNextHopId
                )


            val accepted =
                primaryNode.receive(
                    forwardedState
                )


            // ---------------------------------------------
            // PRIMARY QUEUE FAILED → BACKUP
            // ---------------------------------------------

            if (
                !accepted
            ) {

                launchBackup(
                    completionTime
                )

                return@transmit
            }


            provider.tryClaimForwardingWinner(

                messageId =
                    packet.messageId,

                nextHopId =
                    primaryNextHopId,

                isBackup =
                    false
            )


            provider.recordForwardProgress(

                messageId =
                    packet.messageId,

                fromNodeId =
                    currentNodeId,

                toNodeId =
                    primaryNextHopId
            )
        }
    }
    // =====================================================
// CARBLE M3 — DELAYED CONTROLLED BACKUP
// =====================================================

    private fun scheduleCarbleM3(
        state: PacketState,
        currentNodeId: String,
        startTime: Long,
        provider: CarbleRouteProvider,
        decision:
        CarbleRouteDecision.ForwardWithDelayedBackup
    ) {

        val packet =
            state.packet


        /*
         * These variables belong to THIS forwarding
         * opportunity only.
         *
         * SimulationEngine executes events deterministically,
         * so they provide a safe first-winner rule.
         */
        var resolved =
            false

        var fallbackStarted =
            false

        var primaryDone =
            false

        var backupDone =
            decision.backupPath ==
                    null


        // =================================================
        // ESCALATE ONLY WHEN BOTH OPPORTUNITIES FAILED
        // =================================================

        fun maybeEscalateToLow(
            eventTime: Long
        ) {

            if (
                resolved ||
                fallbackStarted
            ) {
                return
            }


            if (
                !primaryDone ||
                !backupDone
            ) {
                return
            }


            fallbackStarted =
                true

            resolved =
                true


            val fallbackDecision =
                provider.afterMediumFailure(

                    messageId =
                        packet.messageId,

                    confidence =
                        decision
                            .currentHopConfidence
                )


            executeCarbleDecision(

                packetState =
                    state,

                currentNodeId =
                    currentNodeId,

                decisionTime =
                    eventTime,

                provider =
                    provider,

                decision =
                    fallbackDecision
            )
        }


        // =================================================
        // PRIMARY
        // =================================================

        val primaryPath =
            decision.primaryPath


        if (
            !isValidDynamicPath(

                path =
                    primaryPath,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    packet.destinationId
            )
        ) {

            primaryDone =
                true

        } else {

            val primaryNextHopId =
                primaryPath[1]


            val primaryNode =
                nodes[
                    primaryNextHopId
                ]


            if (
                primaryNode == null
            ) {

                primaryDone =
                    true

            } else {

                eventDrivenLinkTransmitter.transmit(

                    fromNodeId =
                        currentNodeId,

                    toNodeId =
                        primaryNextHopId,

                    messageId =
                        packet.messageId,

                    startTime =
                        startTime

                ) {
                        transmission,
                        completionTime ->


                    transmissionTelemetry.record(
                        transmission
                    )


                    /*
                     * Backup may already have won while the
                     * primary was still in flight.
                     */
                    if (
                        resolved
                    ) {

                        if (
                            transmission.success
                        ) {

                            provider
                                .recordDuplicateSuppression()
                        }

                        return@transmit
                    }


                    if (
                        !transmission.success
                    ) {

                        primaryDone =
                            true

                        maybeEscalateToLow(
                            completionTime
                        )

                        return@transmit
                    }


                    if (
                        state.remainingTtl <= 0
                    ) {

                        resolved =
                            true


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
                            primaryNextHopId
                        )


                    val accepted =
                        primaryNode.receive(
                            forwardedState
                        )


                    /*
                     * Queue rejection means this branch did
                     * not win.
                     */
                    if (
                        !accepted
                    ) {

                        primaryDone =
                            true

                        maybeEscalateToLow(
                            completionTime
                        )

                        return@transmit
                    }


                    /*
                     * PRIMARY WINS.
                     */
                    resolved =
                        true


                    provider
                        .tryClaimForwardingWinner(

                            messageId =
                                packet.messageId,

                            nextHopId =
                                primaryNextHopId,

                            isBackup =
                                false
                        )


                    provider.recordForwardProgress(

                        messageId =
                            packet.messageId,

                        fromNodeId =
                            currentNodeId,

                        toNodeId =
                            primaryNextHopId
                    )
                }
            }
        }


        // =================================================
        // DELAYED BACKUP
        // =================================================

        val backupActivationTime =
            startTime +
                    decision.backupDelay


        simulationEngine.schedule(
            backupActivationTime
        ) {

            /*
             * Primary already successfully progressed.
             *
             * Backup never activates and consumes no copy
             * budget.
             */
            if (
                resolved ||
                isPacketFinished(
                    packet.messageId
                )
            ) {

                return@schedule
            }


            val backupPath =
                decision.backupPath


            if (
                backupPath == null
            ) {

                backupDone =
                    true

                maybeEscalateToLow(
                    backupActivationTime
                )

                return@schedule
            }


            if (
                !isValidDynamicPath(

                    path =
                        backupPath,

                    currentNodeId =
                        currentNodeId,

                    destinationId =
                        packet.destinationId
                )
            ) {

                backupDone =
                    true

                maybeEscalateToLow(
                    backupActivationTime
                )

                return@schedule
            }


            val backupNextHopId =
                backupPath[1]


            val backupNode =
                nodes[
                    backupNextHopId
                ]


            if (
                backupNode == null
            ) {

                backupDone =
                    true

                maybeEscalateToLow(
                    backupActivationTime
                )

                return@schedule
            }


            /*
             * Backup is genuinely launched now.
             */
            provider.recordBackupActivation(
                packet.messageId
            )


            eventDrivenLinkTransmitter.transmit(

                fromNodeId =
                    currentNodeId,

                toNodeId =
                    backupNextHopId,

                messageId =
                    packet.messageId,

                startTime =
                    backupActivationTime

            ) {
                    transmission,
                    completionTime ->


                transmissionTelemetry.record(
                    transmission
                )


                /*
                 * An activated backup that physically fails is
                 * still counted as a backup failure even if
                 * the primary happened to win meanwhile.
                 */
                if (
                    !transmission.success
                ) {

                    provider.recordBackupFailure(
                        packet.messageId
                    )


                    if (
                        resolved
                    ) {

                        return@transmit
                    }


                    backupDone =
                        true

                    maybeEscalateToLow(
                        completionTime
                    )

                    return@transmit
                }


                /*
                 * Primary may have won while this successful
                 * backup transmission was in flight.
                 *
                 * Suppress it BEFORE receiver queue insertion.
                 */
                if (
                    resolved
                ) {

                    provider
                        .recordDuplicateSuppression()

                    return@transmit
                }


                if (
                    state.remainingTtl <= 0
                ) {

                    resolved =
                        true


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
                        backupNextHopId
                    )


                val accepted =
                    backupNode.receive(
                        forwardedState
                    )


                if (
                    !accepted
                ) {

                    provider.recordBackupFailure(
                        packet.messageId
                    )


                    backupDone =
                        true

                    maybeEscalateToLow(
                        completionTime
                    )

                    return@transmit
                }


                /*
                 * BACKUP WINS.
                 */
                resolved =
                    true


                provider
                    .tryClaimForwardingWinner(

                        messageId =
                            packet.messageId,

                        nextHopId =
                            backupNextHopId,

                        isBackup =
                            true
                    )


                provider.recordForwardProgress(

                    messageId =
                        packet.messageId,

                    fromNodeId =
                        currentNodeId,

                    toNodeId =
                        backupNextHopId
                )
            }
        }


        /*
         * Example:
         *
         * primary could already have been determined
         * unavailable before backup scheduling.
         *
         * If there is no backup either, LOW can begin once
         * the delayed opportunity is resolved.
         */
        if (
            primaryDone &&
            backupDone
        ) {

            maybeEscalateToLow(
                startTime
            )
        }
    }
    // =====================================================
// CARBLE LOW PROBE
// =====================================================

    private fun scheduleCarbleProbe(
        state: PacketState,
        nextHopId: String,
        startTime: Long,
        provider: CarbleRouteProvider,
        probeConfidence: Double
    ) {

        val packet =
            state.packet


        val nextNode =
            nodes[
                nextHopId
            ]


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


            executeCarbleDecision(

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


            transmissionTelemetry.record(
                transmission
            )


            // ---------------------------------------------
            // PROBE FAILURE
            // ---------------------------------------------

            if (
                !transmission.success
            ) {

                val fallbackDecision =
                    provider.afterProbeFailure(

                        messageId =
                            packet.messageId,

                        confidence =
                            probeConfidence
                    )


                executeCarbleDecision(

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


            val forwardedState =
                state.forwardTo(
                    nextHopId
                )


            val accepted =
                nextNode.receive(
                    forwardedState
                )


            // ---------------------------------------------
            // QUEUE REJECTED PROBE
            // ---------------------------------------------

            if (
                !accepted
            ) {

                val fallbackDecision =
                    provider.afterProbeFailure(

                        messageId =
                            packet.messageId,

                        confidence =
                            probeConfidence
                    )


                executeCarbleDecision(

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
             * Successful physical LOW probe.
             *
             * This records progress but does NOT claim a
             * confidence recovery. The next evaluation decides
             * whether the packet is LOW/MEDIUM/HIGH.
             */
            provider.recordProbeSuccess(

                messageId =
                    packet.messageId,

                fromNodeId =
                    state.currentNodeId,

                toNodeId =
                    nextHopId
            )
        }
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
// TERMINAL CHECK
// =====================================================

    private fun isPacketFinished(
        messageId: String
    ): Boolean {

        return results.any {
            it.messageId ==
                    messageId
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

        val carbleProvider =
            carbleRouteProviders
                .remove(
                    messageId
                )

        carbleProvider
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