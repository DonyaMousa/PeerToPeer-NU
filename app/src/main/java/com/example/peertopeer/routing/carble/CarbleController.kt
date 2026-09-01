package com.example.peertopeer.routing.carble

class CarbleController(

    /*
     * HIGH:
     *
     * Q >= 0.75
     */
    val highThreshold:
    Double = 0.75,

    /*
     * LOW:
     *
     * Q < 0.45
     */
    val lowThreshold:
    Double = 0.45,

    /*
     * M1:
     *
     * 0.65 <= Q < 0.75
     */
    val m1LowerThreshold:
    Double = 0.65,

    /*
     * M2:
     *
     * 0.55 <= Q < 0.65
     */
    val m2LowerThreshold:
    Double = 0.55

) {

    init {

        require(
            lowThreshold in 0.0..1.0
        )

        require(
            m2LowerThreshold in 0.0..1.0
        )

        require(
            m1LowerThreshold in 0.0..1.0
        )

        require(
            highThreshold in 0.0..1.0
        )

        require(
            lowThreshold <
                    m2LowerThreshold
        ) {
            "lowThreshold must be below m2LowerThreshold."
        }

        require(
            m2LowerThreshold <
                    m1LowerThreshold
        ) {
            "m2LowerThreshold must be below m1LowerThreshold."
        }

        require(
            m1LowerThreshold <
                    highThreshold
        ) {
            "m1LowerThreshold must be below highThreshold."
        }
    }


    // =====================================================
    // CONFIDENCE
    // =====================================================

    fun calculateConfidence(
        signals: CarbleSignals
    ): Double {

        /*
         * CARBLE-v1 confidence model.
         *
         * This remains identical to the model used by the
         * 2RH ablation so that MEDIUM is the meaningful
         * architectural difference.
         *
         * Q =
         *
         * 0.30 D
         * + 0.20 F
         * + 0.15 R
         * + 0.15 T
         * + 0.10 S
         * + 0.10 B
         */
        val confidence =
            0.30 *
                    signals.deliverySuccess +

                    0.20 *
                    signals.freshness +

                    0.15 *
                    signals.stability +

                    0.15 *
                    signals.timeliness +

                    0.10 *
                    signals.signalReliability +

                    0.10 *
                    signals.resourceSuitability

        return confidence.coerceIn(
            0.0,
            1.0
        )
    }


    // =====================================================
    // REGIME SELECTION
    // =====================================================

    fun decide(
        currentHopConfidence: Double,
        routeConfidence: Double
    ): CarbleControlDecision {

        require(
            currentHopConfidence in 0.0..1.0
        ) {
            "currentHopConfidence must be between 0.0 and 1.0."
        }

        require(
            routeConfidence in 0.0..1.0
        ) {
            "routeConfidence must be between 0.0 and 1.0."
        }


        // =================================================
        // LOW
        // =================================================

        /*
         * Only the forwarding opportunity the packet is
         * physically facing NOW may directly force LOW.
         *
         * A weak downstream hop must not make a packet
         * carry prematurely.
         */
        if (
            currentHopConfidence <
            lowThreshold
        ) {

            return CarbleControlDecision(

                regime =
                    CarbleRegime.LOW,

                mediumStage =
                    null,

                currentHopConfidence =
                    currentHopConfidence,

                routeConfidence =
                    routeConfidence,

                reason =
                    CarbleDecisionReason.LOCAL_LOW
            )
        }


        // =================================================
        // M3
        // =================================================

        if (
            currentHopConfidence <
            m2LowerThreshold
        ) {

            return CarbleControlDecision(

                regime =
                    CarbleRegime.MEDIUM,

                mediumStage =
                    CarbleMediumStage.M3,

                currentHopConfidence =
                    currentHopConfidence,

                routeConfidence =
                    routeConfidence,

                reason =
                    CarbleDecisionReason.LOCAL_MEDIUM
            )
        }


        // =================================================
        // M2
        // =================================================

        if (
            currentHopConfidence <
            m1LowerThreshold
        ) {

            return CarbleControlDecision(

                regime =
                    CarbleRegime.MEDIUM,

                mediumStage =
                    CarbleMediumStage.M2,

                currentHopConfidence =
                    currentHopConfidence,

                routeConfidence =
                    routeConfidence,

                reason =
                    CarbleDecisionReason.LOCAL_MEDIUM
            )
        }


        // =================================================
        // M1 — LOCAL
        // =================================================

        if (
            currentHopConfidence <
            highThreshold
        ) {

            return CarbleControlDecision(

                regime =
                    CarbleRegime.MEDIUM,

                mediumStage =
                    CarbleMediumStage.M1,

                currentHopConfidence =
                    currentHopConfidence,

                routeConfidence =
                    routeConfidence,

                reason =
                    CarbleDecisionReason.LOCAL_MEDIUM
            )
        }


        // =================================================
        // M1 — DOWNSTREAM WARNING
        // =================================================

        /*
         * Current hop itself is HIGH, therefore the packet
         * is safe to continue across this hop.
         *
         * But a later route hop is already below the HIGH
         * confidence boundary.
         *
         * CARBLE recognizes this as the pre-failure region
         * without repeating the old 2RH route-bottleneck
         * mistake.
         */
        if (
            routeConfidence <
            highThreshold
        ) {

            return CarbleControlDecision(

                regime =
                    CarbleRegime.MEDIUM,

                mediumStage =
                    CarbleMediumStage.M1,

                currentHopConfidence =
                    currentHopConfidence,

                routeConfidence =
                    routeConfidence,

                reason =
                    CarbleDecisionReason
                        .DOWNSTREAM_WARNING
            )
        }


        // =================================================
        // HIGH
        // =================================================

        return CarbleControlDecision(

            regime =
                CarbleRegime.HIGH,

            mediumStage =
                null,

            currentHopConfidence =
                currentHopConfidence,

            routeConfidence =
                routeConfidence,

            reason =
                CarbleDecisionReason.HEALTHY_ROUTE
        )
    }
}