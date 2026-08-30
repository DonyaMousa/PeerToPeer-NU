package com.example.peertopeer.routing.hybrid

import com.example.peertopeer.routing.mm.MultiMetricLinkState

class TwoRegimeSignalAdapter {

    fun fromLinkState(
        state: MultiMetricLinkState
    ): TwoRegimeSignals {

        /*
         * D — delivery success
         *
         * Already represented directly as successRate.
         */
        val deliverySuccess =
            state.successRate
                .coerceIn(
                    0.0,
                    1.0
                )

        /*
         * F — freshness
         *
         * We do not yet have explicit observation age in
         * MultiMetricLinkState.
         *
         * For 2RH v1, freshness stays neutral/high.
         *
         * Later CARBLE can replace this with real age-based
         * freshness once timestamps are available.
         */
        val freshness =
            1.0

        /*
         * R — stability
         *
         * More recent link changes = less stable.
         */
        val stability =
            (
                    1.0 -
                            (
                                    state.recentLinkChanges
                                        .toDouble() /
                                            state.instabilityReference
                                                .toDouble()
                                    )
                                .coerceIn(
                                    0.0,
                                    1.0
                                )
                    )
                .coerceIn(
                    0.0,
                    1.0
                )

        /*
         * T — timeliness
         *
         * We combine:
         *
         * - observed delay suitability
         * - queue suitability
         *
         * equally for now.
         */
        val delayPenalty =
            (
                    state.observedDelay /
                            state.delayReference
                    )
                .coerceIn(
                    0.0,
                    1.0
                )

        val delaySuitability =
            1.0 -
                    delayPenalty

        val queuePenalty =
            if (
                state.queueCapacity <= 0
            ) {

                1.0

            } else {

                (
                        state.queueOccupancy
                            .toDouble() /
                                state.queueCapacity
                                    .toDouble()
                        )
                    .coerceIn(
                        0.0,
                        1.0
                    )
            }

        val queueSuitability =
            1.0 -
                    queuePenalty

        val timeliness =
            (
                    delaySuitability +
                            queueSuitability
                    ) /
                    2.0

        /*
         * S — signal reliability
         *
         * We do not yet have BLE RSSI in simulation.
         *
         * For now, use reliability evidence as the best
         * available link-quality proxy.
         */
        val signalReliability =
            deliverySuccess

        /*
         * B — resource suitability
         *
         * MultiMetricLinkState already stores a normalized
         * resource/energy penalty:
         *
         * 0 = good
         * 1 = bad
         *
         * Convert penalty into suitability.
         */
        val resourceSuitability =
            (
                    1.0 -
                            state.energyPenaltyNormalized
                    )
                .coerceIn(
                    0.0,
                    1.0
                )

        return TwoRegimeSignals(

            deliverySuccess =
                deliverySuccess,

            freshness =
                freshness,

            stability =
                stability,

            timeliness =
                timeliness,

            signalReliability =
                signalReliability,

            resourceSuitability =
                resourceSuitability
        )
    }
}