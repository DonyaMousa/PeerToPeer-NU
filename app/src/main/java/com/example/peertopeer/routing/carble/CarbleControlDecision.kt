package com.example.peertopeer.routing.carble

data class CarbleControlDecision(

    val regime:
    CarbleRegime,

    /*
     * Non-null only while regime == MEDIUM.
     */
    val mediumStage:
    CarbleMediumStage?,

    /*
     * Confidence of:
     *
     * currentNode -> immediate nextHop
     */
    val currentHopConfidence:
    Double,

    /*
     * Minimum confidence across the remaining route.
     */
    val routeConfidence:
    Double,

    val reason:
    CarbleDecisionReason

) {

    init {

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

        if (
            regime ==
            CarbleRegime.MEDIUM
        ) {

            requireNotNull(
                mediumStage
            ) {
                "MEDIUM decisions must have a mediumStage."
            }

        } else {

            require(
                mediumStage == null
            ) {
                "HIGH and LOW decisions must not have a mediumStage."
            }
        }
    }
}