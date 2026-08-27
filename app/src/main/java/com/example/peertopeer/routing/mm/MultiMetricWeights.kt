package com.example.peertopeer.routing.mm

import kotlin.math.abs

data class MultiMetricWeights(

    /*
     * Reliability / expected transmission effort.
     */
    val reliability: Double = 0.30,

    /*
     * Delay.
     */
    val delay: Double = 0.20,

    /*
     * Queue pressure.
     */
    val queue: Double = 0.15,

    /*
     * Link instability.
     */
    val instability: Double = 0.15,

    /*
     * Resource / energy penalty.
     */
    val energy: Double = 0.10,

    /*
     * Per-hop penalty.
     */
    val hop: Double = 0.10

) {

    init {

        val values =
            listOf(
                reliability,
                delay,
                queue,
                instability,
                energy,
                hop
            )

        require(
            values.all {
                it >= 0.0
            }
        ) {
            "MM weights cannot be negative."
        }

        val total =
            values.sum()

        require(
            abs(
                total - 1.0
            ) < 1e-9
        ) {
            "MM weights must sum to 1.0. Current sum = $total"
        }
    }
}
