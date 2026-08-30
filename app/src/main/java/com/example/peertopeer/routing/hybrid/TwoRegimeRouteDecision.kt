package com.example.peertopeer.routing.hybrid

sealed class TwoRegimeRouteDecision {

    // =====================================================
    // HIGH — NORMAL FORWARDING
    // =====================================================

    data class Forward(
        val path: List<String>,
        val confidence: Double
    ) : TwoRegimeRouteDecision() {

        init {

            require(
                path.isNotEmpty()
            ) {
                "Forward path must not be empty."
            }

            require(
                confidence in 0.0..1.0
            ) {
                "confidence must be between 0.0 and 1.0."
            }
        }
    }


    // =====================================================
    // LOW — TEMPORARY CARRY
    // =====================================================

    data class Carry(
        val confidence: Double,
        val reevaluationNumber: Int,
        val reevaluationDelay: Long
    ) : TwoRegimeRouteDecision() {

        init {

            require(
                confidence in 0.0..1.0
            ) {
                "confidence must be between 0.0 and 1.0."
            }

            require(
                reevaluationNumber > 0
            ) {
                "reevaluationNumber must be greater than 0."
            }

            require(
                reevaluationDelay > 0L
            ) {
                "reevaluationDelay must be greater than 0."
            }
        }
    }


    // =====================================================
    // LOW — BOUNDED RECOVERY PROBE
    // =====================================================

    /*
     * After a Carry period, if confidence is still LOW but
     * a deterministic route exists, 2RH gets one bounded
     * forwarding opportunity.
     *
     * The probe is NOT flooding and does not create
     * multiple packet copies.
     *
     * Its purpose is to:
     *
     * 1. test the current forwarding opportunity,
     * 2. generate fresh transmission evidence,
     * 3. potentially move the packet one hop.
     */
    data class Probe(
        val path: List<String>,
        val confidence: Double
    ) : TwoRegimeRouteDecision() {

        init {

            require(
                path.size >= 2
            ) {
                "Probe path must contain at least two nodes."
            }

            require(
                confidence in 0.0..1.0
            ) {
                "confidence must be between 0.0 and 1.0."
            }
        }
    }


    // =====================================================
    // TERMINAL FALLBACK FAILURE
    // =====================================================

    data class Drop(
        val confidence: Double?,
        val reason: String
    ) : TwoRegimeRouteDecision() {

        init {

            require(
                confidence == null ||
                        confidence in 0.0..1.0
            ) {
                "confidence must be null or between 0.0 and 1.0."
            }

            require(
                reason.isNotBlank()
            ) {
                "reason must not be blank."
            }
        }
    }
}