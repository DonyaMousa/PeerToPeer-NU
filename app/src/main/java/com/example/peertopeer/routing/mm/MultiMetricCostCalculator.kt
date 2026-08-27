package com.example.peertopeer.routing.mm

import kotlin.math.min

class MultiMetricCostCalculator(

    private val weights:
    MultiMetricWeights =
        MultiMetricWeights()

) {

    data class CostBreakdown(

        val reliabilityNormalized: Double,
        val delayNormalized: Double,
        val queueNormalized: Double,
        val instabilityNormalized: Double,
        val energyNormalized: Double,
        val hopNormalized: Double,

        val totalCost: Double
    )

    fun calculate(
        state: MultiMetricLinkState
    ): CostBreakdown {

        /*
         * =================================================
         * RELIABILITY
         * =================================================
         *
         * Initial MM implementation uses:
         *
         * penalty = 1 - recent success rate
         *
         * Examples:
         *
         * successRate = 1.00 -> penalty 0.00
         * successRate = 0.80 -> penalty 0.20
         * successRate = 0.40 -> penalty 0.60
         *
         * We deliberately call this a reliability penalty,
         * not literal ETX, because ETX = 1 / p would be a
         * different mathematical metric.
         */
        val reliabilityNormalized =
            1.0 -
                    state.successRate

        /*
         * =================================================
         * DELAY
         * =================================================
         */
        val delayNormalized =
            min(
                state.observedDelay /
                        state.delayReference,
                1.0
            )

        /*
         * =================================================
         * QUEUE PRESSURE
         * =================================================
         *
         * The B0 experiments showed queue waiting/occupancy
         * can become abnormal before PDR falls, which is why
         * queue state belongs in MM.
         */
        val queueNormalized =
            state.queueOccupancy
                .toDouble() /
                    state.queueCapacity
                        .toDouble()

        /*
         * =================================================
         * INSTABILITY
         * =================================================
         */
        val instabilityNormalized =
            min(
                state.recentLinkChanges
                    .toDouble() /
                        state.instabilityReference
                            .toDouble(),
                1.0
            )

        /*
         * =================================================
         * RESOURCE / ENERGY
         * =================================================
         *
         * Simulation does not invent battery measurements.
         *
         * Until a real resource model is supplied,
         * this will normally be 0.0.
         */
        val energyNormalized =
            state.energyPenaltyNormalized

        /*
         * Every edge receives one hop penalty.
         */
        val hopNormalized =
            1.0

        val totalCost =
            weights.reliability *
                    reliabilityNormalized +
                    weights.delay *
                    delayNormalized +
                    weights.queue *
                    queueNormalized +
                    weights.instability *
                    instabilityNormalized +
                    weights.energy *
                    energyNormalized +
                    weights.hop *
                    hopNormalized

        return CostBreakdown(
            reliabilityNormalized =
                reliabilityNormalized,

            delayNormalized =
                delayNormalized,

            queueNormalized =
                queueNormalized,

            instabilityNormalized =
                instabilityNormalized,

            energyNormalized =
                energyNormalized,

            hopNormalized =
                hopNormalized,

            totalCost =
                totalCost
        )
    }
}
