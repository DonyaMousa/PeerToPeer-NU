package com.example.peertopeer.simulation

import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleDecisionReason
import com.example.peertopeer.routing.carble.CarbleMediumStage
import com.example.peertopeer.routing.carble.CarblePacketState
import com.example.peertopeer.routing.carble.CarblePacketStateStore
import com.example.peertopeer.routing.carble.CarbleRegime
import com.example.peertopeer.routing.carble.CarbleRegimeEventRecord
import com.example.peertopeer.routing.carble.CarbleRouteDecision
import com.example.peertopeer.routing.carble.CarbleRouteEvaluator
import com.example.peertopeer.routing.carble.CarbleTelemetry
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy

class CarbleRouteProvider(

    private val mmRouteProvider:
    MMRouteProvider,

    private val routeEvaluator:
    CarbleRouteEvaluator,

    private val candidateFactory:
    CarbleBackupCandidateFactory,

    private val backupSelector:
    CarbleBackupSelector =
        CarbleBackupSelector(),

    /*
     * IMPORTANT:
     *
     * CARBLE LOW deliberately reuses the SAME bounded
     * fallback policy as frozen 2RH.
     */
    private val fallbackPolicy:
    TwoRegimeFallbackPolicy =
        TwoRegimeFallbackPolicy(),

    /*
     * M3 delayed backup:
     *
     * specification =
     * 2 * retryDelay
     */
    private val retryDelay:
    Long = 1L,

    /*
     * Research evidence metadata.
     *
     * Defaults keep older unit tests source-compatible.
     */
    private val runId:
    String? = null,

    private val timeProvider:
        () -> Long = { 0L },

    private val packetStateStore:
    CarblePacketStateStore =
        CarblePacketStateStore()

) {

    init {

        require(
            retryDelay > 0L
        ) {
            "retryDelay must be greater than 0."
        }
    }


    // =====================================================
    // TELEMETRY + REGIME EVENT EVIDENCE
    // =====================================================

    val adaptationTelemetry =
        CarbleTelemetry()

    private val regimeEvents =
        mutableListOf<CarbleRegimeEventRecord>()


    // =====================================================
    // NORMAL DECISION
    // =====================================================

    fun decide(
        currentNodeId: String,
        destinationId: String,
        messageId: String
    ): CarbleRouteDecision {

        validateIds(
            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            messageId =
                messageId
        )


        val packetState =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        val path =
            mmRouteProvider.findPath(

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                messageId =
                    messageId
            )


        // =================================================
        // NO ROUTE
        // =================================================

        if (
            path == null
        ) {

            adaptationTelemetry
                .recordLowDecision()


            recordTransition(

                previous =
                    packetState.regime,

                next =
                    CarbleRegime.LOW
            )


            packetStateStore.update(

                packetState.copy(

                    regime =
                        CarbleRegime.LOW,

                    mediumStage =
                        null,

                    primaryNextHopId =
                        null,

                    backupNextHopId =
                        null,

                    forwardingWinnerNodeId =
                        null
                )
            )


            val decision =
                lowFallback(

                    messageId =
                        messageId,

                    confidence =
                        null
                )


            recordEvaluationEvent(

                messageId =
                    messageId,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                previousRegime =
                    packetState.regime,

                evaluation =
                    null,

                decision =
                    decision,

                reasonOverride =
                    "NO_ROUTE"
            )


            return decision
        }


        val evaluation =
            routeEvaluator.evaluate(
                path
            )


        val decision =
            buildDecision(

                path =
                    path,

                evaluation =
                    evaluation,

                packetState =
                    packetState,

                messageId =
                    messageId,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId
            )


        recordEvaluationEvent(

            messageId =
                messageId,

            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            previousRegime =
                packetState.regime,

            evaluation =
                evaluation,

            decision =
                decision
        )


        return decision
    }


    // =====================================================
    // AFTER LOW CARRY
    // =====================================================

    fun decideAfterCarry(
        currentNodeId: String,
        destinationId: String,
        messageId: String
    ): CarbleRouteDecision {

        validateIds(
            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            messageId =
                messageId
        )


        val packetState =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        val path =
            mmRouteProvider.findPath(

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                messageId =
                    messageId
            )


        // =================================================
        // STILL NO ROUTE
        // =================================================

        if (
            path == null
        ) {

            adaptationTelemetry
                .recordLowDecision()


            packetStateStore.update(

                packetState.copy(

                    regime =
                        CarbleRegime.LOW,

                    mediumStage =
                        null,

                    primaryNextHopId =
                        null,

                    backupNextHopId =
                        null,

                    forwardingWinnerNodeId =
                        null
                )
            )


            val decision =
                lowFallback(

                    messageId =
                        messageId,

                    confidence =
                        null
                )


            recordEvaluationEvent(

                messageId =
                    messageId,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                previousRegime =
                    packetState.regime,

                evaluation =
                    null,

                decision =
                    decision,

                reasonOverride =
                    "NO_ROUTE"
            )


            return decision
        }


        val evaluation =
            routeEvaluator.evaluate(
                path
            )


        // =================================================
        // STILL LOW → PROBE
        // =================================================

        if (
            evaluation.regime ==
            CarbleRegime.LOW
        ) {

            adaptationTelemetry
                .recordLowDecision()

            adaptationTelemetry
                .recordProbeDecision()


            packetStateStore.update(

                packetState.copy(

                    regime =
                        CarbleRegime.LOW,

                    mediumStage =
                        null,

                    primaryNextHopId =
                        path[1],

                    backupNextHopId =
                        null,

                    forwardingWinnerNodeId =
                        null
                )
            )


            val decision =
                CarbleRouteDecision.Probe(

                    path =
                        path,

                    confidence =
                        evaluation
                            .currentHopConfidence
                )


            recordEvaluationEvent(

                messageId =
                    messageId,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                previousRegime =
                    packetState.regime,

                evaluation =
                    evaluation,

                decision =
                    decision
            )


            return decision
        }


        // =================================================
        // LOW RECOVERED → MEDIUM / HIGH
        // =================================================

        /*
         * LOW has recovered into MEDIUM or HIGH.
         *
         * Build the normal CARBLE decision and record the
         * recovery evaluation as an event as well.
         */
        val decision =
            buildDecision(

                path =
                    path,

                evaluation =
                    evaluation,

                packetState =
                    packetState,

                messageId =
                    messageId,

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId
            )


        recordEvaluationEvent(

            messageId =
                messageId,

            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            previousRegime =
                packetState.regime,

            evaluation =
                evaluation,

            decision =
                decision
        )


        return decision
    }


    // =====================================================
    // CENTRAL DECISION BUILDER
    // =====================================================

    private fun buildDecision(
        path: List<String>,
        evaluation:
        CarbleRouteEvaluator.RouteEvaluation,
        packetState:
        CarblePacketState,
        messageId: String,
        currentNodeId: String,
        destinationId: String
    ): CarbleRouteDecision {

        return when (
            evaluation.regime
        ) {

            // =================================================
            // HIGH
            // =================================================

            CarbleRegime.HIGH -> {

                adaptationTelemetry
                    .recordHighDecision()


                recordTransition(

                    previous =
                        packetState.regime,

                    next =
                        CarbleRegime.HIGH
                )


                /*
                 * Genuine HIGH recovery resets the bounded
                 * LOW fallback budget.
                 */
                val updatedState =
                    packetState.copy(

                        regime =
                            CarbleRegime.HIGH,

                        mediumStage =
                            null,

                        primaryNextHopId =
                            path[1],

                        backupNextHopId =
                            null,

                        lowReevaluations =
                            0,

                        forwardingWinnerNodeId =
                            null
                    )


                packetStateStore.update(
                    updatedState
                )


                CarbleRouteDecision.Forward(

                    path =
                        path,

                    currentHopConfidence =
                        evaluation
                            .currentHopConfidence,

                    routeConfidence =
                        evaluation
                            .routeConfidence,

                    regime =
                        CarbleRegime.HIGH,

                    mediumStage =
                        null,

                    reason =
                        evaluation.reason
                )
            }


            // =================================================
            // MEDIUM
            // =================================================

            CarbleRegime.MEDIUM -> {

                val stage =
                    requireNotNull(
                        evaluation.mediumStage
                    )


                adaptationTelemetry
                    .recordMediumDecision(
                        stage
                    )


                if (
                    evaluation.reason ==
                    CarbleDecisionReason
                        .DOWNSTREAM_WARNING
                ) {

                    adaptationTelemetry
                        .recordDownstreamWarning()
                }


                recordTransition(

                    previous =
                        packetState.regime,

                    next =
                        CarbleRegime.MEDIUM
                )


                /*
                 * IMPORTANT:
                 *
                 * LOW -> MEDIUM does NOT reset the bounded
                 * LOW fallback budget.
                 *
                 * Only genuine HIGH recovery resets it.
                 * This prevents an indefinitely cycling
                 * M2/M3 -> LOW -> MEDIUM -> LOW pattern.
                 */
                val baseState =
                    packetState.copy(

                        regime =
                            CarbleRegime.MEDIUM,

                        mediumStage =
                            stage,

                        primaryNextHopId =
                            path[1],

                        backupNextHopId =
                            null,

                        forwardingWinnerNodeId =
                            null
                    )


                when (
                    stage
                ) {

                    // =====================================
                    // M1
                    // =====================================

                    CarbleMediumStage.M1 -> {

                        packetStateStore.update(
                            baseState
                        )


                        CarbleRouteDecision.Forward(

                            path =
                                path,

                            currentHopConfidence =
                                evaluation
                                    .currentHopConfidence,

                            routeConfidence =
                                evaluation
                                    .routeConfidence,

                            regime =
                                CarbleRegime.MEDIUM,

                            mediumStage =
                                CarbleMediumStage.M1,

                            reason =
                                evaluation.reason
                        )
                    }


                    // =====================================
                    // M2
                    // =====================================

                    CarbleMediumStage.M2 -> {

                        val backupPath =
                            findBackupPath(

                                currentNodeId =
                                    currentNodeId,

                                destinationId =
                                    destinationId,

                                primaryNextHopId =
                                    path[1],

                                packetState =
                                    packetState
                            )


                        packetStateStore.update(

                            baseState.copy(

                                backupNextHopId =
                                    backupPath
                                        ?.getOrNull(
                                            1
                                        )
                            )
                        )


                        CarbleRouteDecision
                            .ForwardWithFailover(

                                primaryPath =
                                    path,

                                backupPath =
                                    backupPath,

                                currentHopConfidence =
                                    evaluation
                                        .currentHopConfidence,

                                routeConfidence =
                                    evaluation
                                        .routeConfidence,

                                reason =
                                    evaluation.reason
                            )
                    }


                    // =====================================
                    // M3
                    // =====================================

                    CarbleMediumStage.M3 -> {

                        val backupPath =
                            findBackupPath(

                                currentNodeId =
                                    currentNodeId,

                                destinationId =
                                    destinationId,

                                primaryNextHopId =
                                    path[1],

                                packetState =
                                    packetState
                            )


                        packetStateStore.update(

                            baseState.copy(

                                backupNextHopId =
                                    backupPath
                                        ?.getOrNull(
                                            1
                                        )
                            )
                        )


                        CarbleRouteDecision
                            .ForwardWithDelayedBackup(

                                primaryPath =
                                    path,

                                backupPath =
                                    backupPath,

                                backupDelay =
                                    retryDelay * 2L,

                                currentHopConfidence =
                                    evaluation
                                        .currentHopConfidence,

                                routeConfidence =
                                    evaluation
                                        .routeConfidence,

                                reason =
                                    evaluation.reason
                            )
                    }
                }
            }


            // =================================================
            // LOW
            // =================================================

            CarbleRegime.LOW -> {

                adaptationTelemetry
                    .recordLowDecision()


                recordTransition(

                    previous =
                        packetState.regime,

                    next =
                        CarbleRegime.LOW
                )


                packetStateStore.update(

                    packetState.copy(

                        regime =
                            CarbleRegime.LOW,

                        mediumStage =
                            null,

                        primaryNextHopId =
                            path[1],

                        backupNextHopId =
                            null,

                        forwardingWinnerNodeId =
                            null
                    )
                )


                lowFallback(

                    messageId =
                        messageId,

                    confidence =
                        evaluation
                            .currentHopConfidence
                )
            }
        }
    }


    // =====================================================
    // BACKUP DISCOVERY
    // =====================================================

    private fun findBackupPath(
        currentNodeId: String,
        destinationId: String,
        primaryNextHopId: String,
        packetState: CarblePacketState
    ): List<String>? {

        val candidates =
            candidateFactory
                .createCandidates(

                    currentNodeId =
                        currentNodeId,

                    destinationId =
                        destinationId
                )


        val selected =
            backupSelector
                .selectBackup(

                    primaryNextHopId =
                        primaryNextHopId,

                    previousNodeId =
                        packetState
                            .previousNodeId,

                    candidates =
                        candidates
                )


        if (
            selected == null
        ) {

            return null
        }


        /*
         * Candidate exists, but this packet has already
         * consumed its one permitted MEDIUM backup.
         */
        if (
            packetState.copyBudgetRemaining <= 0
        ) {

            adaptationTelemetry
                .recordCopyBudgetExhaustion()

            return null
        }


        adaptationTelemetry
            .recordBackupPrepared()


        return selected
            .candidate
            .path
    }


    // =====================================================
    // M2 / M3 — BACKUP ACTIVATION
    // =====================================================

    /*
     * Called by the simulator ONLY when a prepared backup
     * is actually launched.
     *
     * Preparing a backup does not consume copy budget.
     */
    fun recordBackupActivation(
        messageId: String
    ) {

        requireMessageId(
            messageId
        )


        val state =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        require(
            state.copyBudgetRemaining > 0
        ) {
            "CARBLE backup copy budget already exhausted."
        }


        adaptationTelemetry
            .recordBackupActivation()


        packetStateStore.update(

            state.copy(

                backupUsed =
                    true,

                copyBudgetRemaining =
                    state
                        .copyBudgetRemaining -
                            1
            )
        )
    }


    fun recordBackupFailure(
        messageId: String
    ) {

        requireMessageId(
            messageId
        )

        adaptationTelemetry
            .recordBackupFailure()
    }


    // =====================================================
    // M3 WINNER / DUPLICATE SUPPRESSION
    // =====================================================

    /*
     * Returns true only for the first successful branch.
     *
     * Primary and delayed backup may both complete
     * physically, but only one is allowed to continue.
     */
    fun tryClaimForwardingWinner(
        messageId: String,
        nextHopId: String,
        isBackup: Boolean
    ): Boolean {

        requireMessageId(
            messageId
        )

        require(
            nextHopId.isNotBlank()
        )


        val state =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        val existingWinner =
            state.forwardingWinnerNodeId


        if (
            existingWinner != null
        ) {

            adaptationTelemetry
                .recordDuplicateSuppression()

            return false
        }


        packetStateStore.update(

            state.copy(

                forwardingWinnerNodeId =
                    nextHopId
            )
        )


        if (
            isBackup
        ) {

            adaptationTelemetry
                .recordBackupSuccess()
        }


        return true
    }


    // =====================================================
    // LATE BRANCH SUPPRESSION
    // =====================================================

    fun recordDuplicateSuppression() {

        adaptationTelemetry
            .recordDuplicateSuppression()
    }


    // =====================================================
    // SUCCESSFUL PHYSICAL PROGRESS
    // =====================================================

    /*
     * Called after the chosen branch successfully enters
     * the next node.
     *
     * previousNodeId therefore reflects REAL progress,
     * not merely an attempted transmission.
     */
    fun recordForwardProgress(
        messageId: String,
        fromNodeId: String,
        toNodeId: String
    ) {

        requireMessageId(
            messageId
        )

        require(
            fromNodeId.isNotBlank()
        )

        require(
            toNodeId.isNotBlank()
        )

        require(
            fromNodeId !=
                    toNodeId
        )


        val state =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        packetStateStore.update(

            state.copy(

                previousNodeId =
                    fromNodeId,

                primaryNextHopId =
                    null,

                backupNextHopId =
                    null,

                forwardingWinnerNodeId =
                    null
            )
        )
    }


    // =====================================================
    // MEDIUM FAILURE → LOW
    // =====================================================

    /*
     * Called when:
     *
     * M2:
     * primary fails AND backup is absent/fails
     *
     * M3:
     * neither primary nor backup succeeds
     *
     * This is an ACTION outcome, not a new route
     * evaluation, so lowDecisions is NOT incremented here.
     */
    fun afterMediumFailure(
        messageId: String,
        confidence: Double
    ): CarbleRouteDecision {

        requireMessageId(
            messageId
        )

        require(
            confidence in 0.0..1.0
        )


        val state =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        if (
            state.regime ==
            CarbleRegime.MEDIUM
        ) {

            adaptationTelemetry
                .recordMediumToLowEscalation()
        }


        packetStateStore.update(

            state.copy(

                regime =
                    CarbleRegime.LOW,

                mediumStage =
                    null,

                primaryNextHopId =
                    null,

                backupNextHopId =
                    null,

                forwardingWinnerNodeId =
                    null
            )
        )


        return lowFallback(

            messageId =
                messageId,

            confidence =
                confidence
        )
    }


    // =====================================================
    // LOW PROBE FAILURE
    // =====================================================

    fun afterProbeFailure(
        messageId: String,
        confidence: Double
    ): CarbleRouteDecision {

        requireMessageId(
            messageId
        )

        require(
            confidence in 0.0..1.0
        )


        /*
         * Same semantic rule as frozen 2RH:
         *
         * probe failure is an ACTION result,
         * not a new confidence/regime evaluation.
         */
        adaptationTelemetry
            .recordProbeFailure()


        return lowFallback(

            messageId =
                messageId,

            confidence =
                confidence
        )
    }


    // =====================================================
    // LOW PROBE SUCCESS
    // =====================================================

    fun recordProbeSuccess(
        messageId: String,
        fromNodeId: String,
        toNodeId: String
    ) {

        requireMessageId(
            messageId
        )


        adaptationTelemetry
            .recordProbeSuccess()


        /*
         * Probe success is physical progress.
         *
         * It is NOT automatically LOW -> MEDIUM/HIGH.
         * The next real evaluation determines that.
         */
        recordForwardProgress(

            messageId =
                messageId,

            fromNodeId =
                fromNodeId,

            toNodeId =
                toNodeId
        )
    }


    // =====================================================
    // LOW FALLBACK
    // =====================================================

    private fun lowFallback(
        messageId: String,
        confidence: Double?
    ): CarbleRouteDecision {

        val state =
            packetStateStore
                .getOrCreate(
                    messageId
                )


        val fallbackDecision =
            fallbackPolicy.decide(

                completedReevaluations =
                    state.lowReevaluations
            )


        return when (
            fallbackDecision.action
        ) {

            // =============================================
            // CARRY
            // =============================================

            TwoRegimeFallbackPolicy.Action
                .CARRY_AND_REEVALUATE -> {

                packetStateStore.update(

                    state.copy(

                        regime =
                            CarbleRegime.LOW,

                        mediumStage =
                            null,

                        lowReevaluations =
                            fallbackDecision
                                .nextReevaluationNumber
                    )
                )


                adaptationTelemetry
                    .recordCarryDecision()


                CarbleRouteDecision.Carry(

                    confidence =
                        confidence
                            ?: 0.0,

                    reevaluationNumber =
                        fallbackDecision
                            .nextReevaluationNumber,

                    reevaluationDelay =
                        fallbackPolicy
                            .reevaluationDelay
                )
            }


            // =============================================
            // DROP
            // =============================================

            TwoRegimeFallbackPolicy.Action
                .DROP -> {

                adaptationTelemetry
                    .recordFallbackDrop()


                packetStateStore.remove(
                    messageId
                )


                CarbleRouteDecision.Drop(

                    confidence =
                        confidence,

                    reason =
                        fallbackDecision.reason
                )
            }
        }
    }


    // =====================================================
    // REGIME TRANSITIONS
    // =====================================================

    private fun recordTransition(
        previous: CarbleRegime?,
        next: CarbleRegime
    ) {

        if (
            previous == null ||
            previous ==
            next
        ) {

            return
        }


        when {

            previous ==
                    CarbleRegime.MEDIUM &&
                    next ==
                    CarbleRegime.HIGH -> {

                adaptationTelemetry
                    .recordMediumToHighRecovery()
            }


            previous ==
                    CarbleRegime.MEDIUM &&
                    next ==
                    CarbleRegime.LOW -> {

                adaptationTelemetry
                    .recordMediumToLowEscalation()
            }


            previous ==
                    CarbleRegime.LOW &&
                    next ==
                    CarbleRegime.MEDIUM -> {

                adaptationTelemetry
                    .recordLowToMediumRecovery()
            }


            previous ==
                    CarbleRegime.LOW &&
                    next ==
                    CarbleRegime.HIGH -> {

                adaptationTelemetry
                    .recordLowToHighRecovery()
            }
        }
    }


    // =====================================================
    // REGIME EVENT EVIDENCE
    // =====================================================

    /*
     * This stream records controller evaluations and the
     * simulator-facing action produced by each evaluation.
     *
     * Physical outcomes such as backup/probe success and
     * failure remain available from adaptation telemetry
     * and TransmissionRecord evidence.
     */
    private fun recordEvaluationEvent(
        messageId: String,
        currentNodeId: String,
        destinationId: String,
        previousRegime: CarbleRegime?,
        evaluation:
        CarbleRouteEvaluator.RouteEvaluation?,
        decision:
        CarbleRouteDecision,
        reasonOverride: String? = null
    ) {

        val regime =
            when (
                decision
            ) {

                is CarbleRouteDecision.Forward ->
                    decision.regime

                is CarbleRouteDecision
                .ForwardWithFailover ->
                    CarbleRegime.MEDIUM

                is CarbleRouteDecision
                .ForwardWithDelayedBackup ->
                    CarbleRegime.MEDIUM

                is CarbleRouteDecision.Carry ->
                    CarbleRegime.LOW

                is CarbleRouteDecision.Probe ->
                    CarbleRegime.LOW

                is CarbleRouteDecision.Drop ->
                    CarbleRegime.LOW
            }


        val mediumStage =
            when (
                decision
            ) {

                is CarbleRouteDecision.Forward ->
                    decision.mediumStage

                is CarbleRouteDecision
                .ForwardWithFailover ->
                    CarbleMediumStage.M2

                is CarbleRouteDecision
                .ForwardWithDelayedBackup ->
                    CarbleMediumStage.M3

                else ->
                    null
            }


        val action =
            when (
                decision
            ) {

                is CarbleRouteDecision.Forward ->
                    "FORWARD"

                is CarbleRouteDecision
                .ForwardWithFailover ->
                    "FORWARD_WITH_FAILOVER"

                is CarbleRouteDecision
                .ForwardWithDelayedBackup ->
                    "FORWARD_WITH_DELAYED_BACKUP"

                is CarbleRouteDecision.Carry ->
                    "CARRY"

                is CarbleRouteDecision.Probe ->
                    "PROBE"

                is CarbleRouteDecision.Drop ->
                    "DROP"
            }


        val primaryNextHopId =
            when (
                decision
            ) {

                is CarbleRouteDecision.Forward ->
                    decision.path
                        .getOrNull(
                            1
                        )

                is CarbleRouteDecision
                .ForwardWithFailover ->
                    decision.primaryPath
                        .getOrNull(
                            1
                        )

                is CarbleRouteDecision
                .ForwardWithDelayedBackup ->
                    decision.primaryPath
                        .getOrNull(
                            1
                        )

                is CarbleRouteDecision.Probe ->
                    decision.path
                        .getOrNull(
                            1
                        )

                else ->
                    null
            }


        val backupNextHopId =
            when (
                decision
            ) {

                is CarbleRouteDecision
                .ForwardWithFailover ->
                    decision.backupPath
                        ?.getOrNull(
                            1
                        )

                is CarbleRouteDecision
                .ForwardWithDelayedBackup ->
                    decision.backupPath
                        ?.getOrNull(
                            1
                        )

                else ->
                    null
            }


        val reason =
            reasonOverride
                ?: evaluation
                    ?.reason
                    ?.name
                ?: when (
                    decision
                ) {

                    is CarbleRouteDecision.Drop ->
                        decision.reason

                    else ->
                        "LOW_FALLBACK"
                }


        regimeEvents.add(

            CarbleRegimeEventRecord(

                runId =
                    runId,

                messageId =
                    messageId,

                eventTime =
                    timeProvider(),

                currentNodeId =
                    currentNodeId,

                destinationId =
                    destinationId,

                currentHopConfidence =
                    evaluation
                        ?.currentHopConfidence,

                routeConfidence =
                    evaluation
                        ?.routeConfidence,

                previousRegime =
                    previousRegime,

                regime =
                    regime,

                mediumStage =
                    mediumStage,

                reason =
                    reason,

                bottleneckFromNodeId =
                    evaluation
                        ?.bottleneckFromNodeId,

                bottleneckToNodeId =
                    evaluation
                        ?.bottleneckToNodeId,

                primaryNextHopId =
                    primaryNextHopId,

                backupNextHopId =
                    backupNextHopId,

                action =
                    action
            )
        )
    }


    fun getRegimeEvents():
            List<CarbleRegimeEventRecord> {

        return regimeEvents
            .toList()
    }


    // =====================================================
    // CLEANUP
    // =====================================================

    fun clearPacketState(
        messageId: String
    ) {

        packetStateStore.remove(
            messageId
        )
    }


    fun getPacketState(
        messageId: String
    ): CarblePacketState? {

        return packetStateStore.get(
            messageId
        )
    }


    // =====================================================
    // VALIDATION
    // =====================================================

    private fun validateIds(
        currentNodeId: String,
        destinationId: String,
        messageId: String
    ) {

        require(
            currentNodeId.isNotBlank()
        )

        require(
            destinationId.isNotBlank()
        )

        requireMessageId(
            messageId
        )
    }


    private fun requireMessageId(
        messageId: String
    ) {

        require(
            messageId.isNotBlank()
        ) {
            "messageId must not be blank."
        }
    }


    // =====================================================
    // COMMON MM TELEMETRY
    // =====================================================

    val telemetry
        get() =
            mmRouteProvider.telemetry
}
