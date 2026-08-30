package com.example.peertopeer.routing.hybrid

class TwoRegimeController(

    /*
     * IMPORTANT:
     *
     * 2RH intentionally uses CARBLE's future HIGH
     * threshold.
     *
     * Everything below HIGH is collapsed directly into
     * LOW because 2RH has no MEDIUM regime.
     */
    val highThreshold: Double =
        0.75

) {

    init {

        require(
            highThreshold in 0.0..1.0
        ) {
            "highThreshold must be between 0.0 and 1.0."
        }
    }

    // =====================================================
    // CONFIDENCE
    // =====================================================

    fun calculateConfidence(
        signals: TwoRegimeSignals
    ): Double {

        /*
         * Q =
         *
         * 0.30 D
         * + 0.20 F
         * + 0.15 R
         * + 0.15 T
         * + 0.10 S
         * + 0.10 B
         *
         * Weights sum exactly to 1.0.
         */
        return (
                0.30 *
                        signals.deliverySuccess
                ) +
                (
                        0.20 *
                                signals.freshness
                        ) +
                (
                        0.15 *
                                signals.stability
                        ) +
                (
                        0.15 *
                                signals.timeliness
                        ) +
                (
                        0.10 *
                                signals.signalReliability
                        ) +
                (
                        0.10 *
                                signals.resourceSuitability
                        )
    }

    // =====================================================
    // DECISION
    // =====================================================

    fun decide(
        signals: TwoRegimeSignals
    ): TwoRegimeDecision {

        val confidence =
            calculateConfidence(
                signals
            )

        return if (
            confidence >=
            highThreshold
        ) {

            TwoRegimeDecision(

                state =
                    TwoRegimeState.HIGH,

                confidence =
                    confidence,

                reason =
                    "Confidence is at or above the HIGH threshold."
            )

        } else {

            /*
             * This is the defining ablation behavior.
             *
             * CARBLE will later distinguish:
             *
             * MEDIUM: 0.45 <= Q < 0.75
             * LOW:    Q < 0.45
             *
             * 2RH deliberately removes that distinction.
             */
            TwoRegimeDecision(

                state =
                    TwoRegimeState.LOW,

                confidence =
                    confidence,

                reason =
                    "Confidence is below the HIGH threshold; " +
                            "2RH has no MEDIUM regime."
            )
        }
    }
}