package com.example.peertopeer.routing.carble

data class CarbleSignals(

    /*
     * D
     */
    val deliverySuccess: Double,

    /*
     * F
     */
    val freshness: Double,

    /*
     * R
     */
    val stability: Double,

    /*
     * T
     */
    val timeliness: Double,

    /*
     * S
     */
    val signalReliability: Double,

    /*
     * B
     */
    val resourceSuitability: Double

) {

    init {

        require(
            deliverySuccess in 0.0..1.0
        ) {
            "deliverySuccess must be between 0.0 and 1.0."
        }

        require(
            freshness in 0.0..1.0
        ) {
            "freshness must be between 0.0 and 1.0."
        }

        require(
            stability in 0.0..1.0
        ) {
            "stability must be between 0.0 and 1.0."
        }

        require(
            timeliness in 0.0..1.0
        ) {
            "timeliness must be between 0.0 and 1.0."
        }

        require(
            signalReliability in 0.0..1.0
        ) {
            "signalReliability must be between 0.0 and 1.0."
        }

        require(
            resourceSuitability in 0.0..1.0
        ) {
            "resourceSuitability must be between 0.0 and 1.0."
        }
    }
}