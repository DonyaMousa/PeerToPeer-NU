package com.example.peertopeer.routing.carble

import com.example.peertopeer.routing.mm.MultiMetricLinkState

class CarbleSignalAdapter {

    fun fromLinkState(
        state: MultiMetricLinkState
    ): CarbleSignals {

        /*
         * D — delivery success
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
         * Simulation v1 does not yet have explicit
         * observation age.
         *
         * Keep the SAME proxy used by 2RH.
         */
        val freshness =
            1.0


        /*
         * R — stability
         */
        val instabilityPenalty =
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

        val stability =
            (
                    1.0 -
                            instabilityPenalty
                    )
                .coerceIn(
                    0.0,
                    1.0
                )


        /*
         * T — timeliness
         *
         * Same delay + queue suitability mapping used
         * by the 2RH baseline.
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
         * Real BLE RSSI does not yet exist in simulation,
         * so preserve the exact 2RH proxy.
         */
        val signalReliability =
            deliverySuccess


        /*
         * B — resource suitability
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


        return CarbleSignals(

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