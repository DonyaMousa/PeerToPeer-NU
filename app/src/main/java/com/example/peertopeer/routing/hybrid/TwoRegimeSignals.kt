package com.example.peertopeer.routing.hybrid

data class TwoRegimeSignals(

    /*
     * D — recent delivery / transmission success.
     *
     * 1.0 = very reliable
     * 0.0 = very unreliable
     */
    val deliverySuccess: Double,

    /*
     * F — freshness of the routing/link information.
     *
     * 1.0 = very fresh
     * 0.0 = stale
     */
    val freshness: Double,

    /*
     * R — contact/link stability.
     *
     * 1.0 = stable
     * 0.0 = highly unstable
     */
    val stability: Double,

    /*
     * T — timeliness / queue suitability.
     *
     * 1.0 = little delay / queue pressure
     * 0.0 = severe delay / congestion
     */
    val timeliness: Double,

    /*
     * S — signal/link-quality suitability.
     *
     * In simulation this may initially be derived from
     * available link evidence.
     *
     * On real BLE devices this can later use RSSI or
     * another physical-link indicator.
     */
    val signalReliability: Double,

    /*
     * B — resource / battery suitability.
     *
     * Simulation may initially keep this neutral/high
     * until real resource measurements are available.
     */
    val resourceSuitability: Double

) {

    init {

        validateSignal(
            "deliverySuccess",
            deliverySuccess
        )

        validateSignal(
            "freshness",
            freshness
        )

        validateSignal(
            "stability",
            stability
        )

        validateSignal(
            "timeliness",
            timeliness
        )

        validateSignal(
            "signalReliability",
            signalReliability
        )

        validateSignal(
            "resourceSuitability",
            resourceSuitability
        )
    }

    private fun validateSignal(
        name: String,
        value: Double
    ) {

        require(
            value in 0.0..1.0
        ) {
            "$name must be between 0.0 and 1.0."
        }
    }
}