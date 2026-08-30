package com.example.peertopeer.routing.hybrid

class TwoRegimeFallbackPolicy(

    /*
     * Maximum number of LOW-state re-evaluation
     * opportunities allowed for one packet.
     *
     * This prevents indefinite carrying.
     */
    val maxReevaluations: Int =
        3,

    /*
     * Simulation-time delay before trying again.
     *
     * This is NOT real milliseconds.
     */
    val reevaluationDelay: Long =
        5L

) {

    init {

        require(
            maxReevaluations > 0
        ) {
            "maxReevaluations must be greater than 0."
        }

        require(
            reevaluationDelay > 0L
        ) {
            "reevaluationDelay must be greater than 0."
        }
    }

    enum class Action {

        /*
         * Hold the packet temporarily and reassess
         * network confidence after the configured delay.
         */
        CARRY_AND_REEVALUATE,

        /*
         * The bounded fallback budget has been exhausted.
         */
        DROP
    }

    data class Decision(

        val action: Action,

        val nextReevaluationNumber: Int,

        val reason: String
    )

    // =====================================================
    // FALLBACK DECISION
    // =====================================================

    fun decide(
        completedReevaluations: Int
    ): Decision {

        require(
            completedReevaluations >= 0
        ) {
            "completedReevaluations cannot be negative."
        }

        return if (
            completedReevaluations <
            maxReevaluations
        ) {

            Decision(

                action =
                    Action.CARRY_AND_REEVALUATE,

                nextReevaluationNumber =
                    completedReevaluations + 1,

                reason =
                    "LOW confidence: bounded carry-and-reevaluate allowed."
            )

        } else {

            Decision(

                action =
                    Action.DROP,

                nextReevaluationNumber =
                    completedReevaluations,

                reason =
                    "LOW fallback budget exhausted."
            )
        }
    }
}