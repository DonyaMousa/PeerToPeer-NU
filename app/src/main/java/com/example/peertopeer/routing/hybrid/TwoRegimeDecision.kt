package com.example.peertopeer.routing.hybrid

data class TwoRegimeDecision(

    val state: TwoRegimeState,

    val confidence: Double,

    val reason: String
) {

    init {

        require(
            confidence in 0.0..1.0
        ) {
            "confidence must be between 0.0 and 1.0."
        }

        require(
            reason.isNotBlank()
        ) {
            "reason must not be blank."
        }
    }
}