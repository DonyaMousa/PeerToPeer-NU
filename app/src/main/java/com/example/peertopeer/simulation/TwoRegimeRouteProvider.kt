package com.example.peertopeer.simulation

import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteDecision
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeState
import com.example.peertopeer.routing.hybrid.TwoRegimeTelemetry

class TwoRegimeRouteProvider(

    private val mmRouteProvider:
    MMRouteProvider,

    private val routeEvaluator:
    TwoRegimeRouteEvaluator,

    private val fallbackPolicy:
    TwoRegimeFallbackPolicy =
        TwoRegimeFallbackPolicy()

) {

    private val completedReevaluations =
        mutableMapOf<String, Int>()

    /*
     * Packets currently undergoing LOW fallback.
     */
    private val packetsInLow =
        mutableSetOf<String>()


    // =====================================================
    // 2RH ADAPTATION TELEMETRY
    // =====================================================

    val adaptationTelemetry =
        TwoRegimeTelemetry()


    // =====================================================
    // NORMAL DECISION
    // =====================================================

    fun decide(
        currentNodeId: String,
        destinationId: String,
        messageId: String
    ): TwoRegimeRouteDecision {

        validateIds(
            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            messageId =
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


        // -------------------------------------------------
        // NO ROUTE
        // -------------------------------------------------

        if (
            path == null
        ) {

            adaptationTelemetry
                .recordLowDecision()

            packetsInLow.add(
                messageId
            )

            return lowFallback(

                messageId =
                    messageId,

                confidence =
                    null
            )
        }


        val evaluation =
            routeEvaluator.evaluate(
                path
            )


        /*
         * CRITICAL 2RH RULE:
         *
         * We classify the forwarding opportunity using the
         * CURRENT HOP.
         *
         * A weak downstream hop must not stop us from
         * traversing healthy upstream hops.
         */
        return when (
            evaluation.currentHopState
        ) {

            // =============================================
            // CURRENT HOP HIGH
            // =============================================

            TwoRegimeState.HIGH -> {

                adaptationTelemetry
                    .recordHighDecision()

                /*
                 * If this packet was previously in LOW,
                 * crossing back to HIGH is a true recovery.
                 */
                if (
                    packetsInLow.remove(
                        messageId
                    )
                ) {

                    adaptationTelemetry
                        .recordLowToHighRecovery()
                }

                /*
                 * A successful HIGH recovery resets the
                 * bounded LOW budget.
                 */
                completedReevaluations.remove(
                    messageId
                )

                TwoRegimeRouteDecision.Forward(

                    path =
                        path,

                    confidence =
                        evaluation.currentHopConfidence
                )
            }


            // =============================================
            // CURRENT HOP LOW
            // =============================================

            TwoRegimeState.LOW -> {

                adaptationTelemetry
                    .recordLowDecision()

                packetsInLow.add(
                    messageId
                )

                lowFallback(

                    messageId =
                        messageId,

                    confidence =
                        evaluation.currentHopConfidence
                )
            }
        }
    }


    // =====================================================
    // AFTER CARRY
    // =====================================================

    fun decideAfterCarry(
        currentNodeId: String,
        destinationId: String,
        messageId: String
    ): TwoRegimeRouteDecision {

        validateIds(
            currentNodeId =
                currentNodeId,

            destinationId =
                destinationId,

            messageId =
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


        // -------------------------------------------------
        // STILL NO ROUTE
        // -------------------------------------------------

        if (
            path == null
        ) {

            adaptationTelemetry
                .recordLowDecision()

            packetsInLow.add(
                messageId
            )

            return lowFallback(

                messageId =
                    messageId,

                confidence =
                    null
            )
        }


        val evaluation =
            routeEvaluator.evaluate(
                path
            )


        return when (
            evaluation.currentHopState
        ) {

            // =============================================
            // CURRENT HOP RECOVERED
            // =============================================

            TwoRegimeState.HIGH -> {

                adaptationTelemetry
                    .recordHighDecision()

                if (
                    packetsInLow.remove(
                        messageId
                    )
                ) {

                    adaptationTelemetry
                        .recordLowToHighRecovery()
                }

                completedReevaluations.remove(
                    messageId
                )

                TwoRegimeRouteDecision.Forward(

                    path =
                        path,

                    confidence =
                        evaluation.currentHopConfidence
                )
            }


            // =============================================
            // CURRENT HOP STILL LOW
            // =============================================

            TwoRegimeState.LOW -> {

                adaptationTelemetry
                    .recordLowDecision()

                adaptationTelemetry
                    .recordProbeDecision()

                packetsInLow.add(
                    messageId
                )

                /*
                 * Because CURRENT hop is LOW, path[1] is
                 * precisely the hop we need to probe.
                 */
                TwoRegimeRouteDecision.Probe(

                    path =
                        path,

                    confidence =
                        evaluation.currentHopConfidence
                )
            }
        }
    }


    // =====================================================
    // PROBE FAILURE
    // =====================================================

    fun afterProbeFailure(
        messageId: String,
        confidence: Double
    ): TwoRegimeRouteDecision {

        require(
            messageId.isNotBlank()
        ) {
            "messageId must not be blank."
        }

        require(
            confidence in 0.0..1.0
        ) {
            "confidence must be between 0.0 and 1.0."
        }

        /*
         * Probe failure is an ACTION result,
         * not another confidence evaluation.
         */
        adaptationTelemetry
            .recordProbeFailure()

        packetsInLow.add(
            messageId
        )

        return lowFallback(

            messageId =
                messageId,

            confidence =
                confidence
        )
    }


    // =====================================================
    // PROBE SUCCESS
    // =====================================================

    fun recordProbeSuccess(
        messageId: String
    ) {

        require(
            messageId.isNotBlank()
        ) {
            "messageId must not be blank."
        }

        adaptationTelemetry
            .recordProbeSuccess()

        /*
         * Do NOT record LOW -> HIGH here.
         *
         * Physical probe success is not the same thing as
         * confidence recovery.
         *
         * Recovery is recorded only when a subsequent
         * evaluation genuinely returns HIGH.
         */
    }


    // =====================================================
    // LOW FALLBACK
    // =====================================================

    private fun lowFallback(
        messageId: String,
        confidence: Double?
    ): TwoRegimeRouteDecision {

        val completed =
            completedReevaluations[
                messageId
            ]
                ?: 0

        val fallbackDecision =
            fallbackPolicy.decide(

                completedReevaluations =
                    completed
            )


        return when (
            fallbackDecision.action
        ) {

            // =============================================
            // CARRY
            // =============================================

            TwoRegimeFallbackPolicy.Action
                .CARRY_AND_REEVALUATE -> {

                completedReevaluations[
                    messageId
                ] =
                    fallbackDecision
                        .nextReevaluationNumber

                adaptationTelemetry
                    .recordCarryDecision()

                TwoRegimeRouteDecision.Carry(

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
            // FALLBACK EXHAUSTED
            // =============================================

            TwoRegimeFallbackPolicy.Action
                .DROP -> {

                completedReevaluations.remove(
                    messageId
                )

                packetsInLow.remove(
                    messageId
                )

                adaptationTelemetry
                    .recordFallbackDrop()

                TwoRegimeRouteDecision.Drop(

                    confidence =
                        confidence,

                    reason =
                        fallbackDecision.reason
                )
            }
        }
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
        ) {
            "currentNodeId must not be blank."
        }

        require(
            destinationId.isNotBlank()
        ) {
            "destinationId must not be blank."
        }

        require(
            messageId.isNotBlank()
        ) {
            "messageId must not be blank."
        }
    }


    // =====================================================
    // PACKET STATE
    // =====================================================

    fun clearPacketState(
        messageId: String
    ) {

        completedReevaluations.remove(
            messageId
        )

        packetsInLow.remove(
            messageId
        )
    }


    fun getCompletedReevaluations(
        messageId: String
    ): Int {

        return completedReevaluations[
            messageId
        ]
            ?: 0
    }


    // =====================================================
    // COMMON MM TELEMETRY
    // =====================================================

    val telemetry
        get() =
            mmRouteProvider.telemetry
}