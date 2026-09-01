package com.example.peertopeer.routing.carble

sealed class CarbleRouteDecision {


    // =====================================================
    // HIGH / M1
    // =====================================================

    data class Forward(

        val path: List<String>,

        val currentHopConfidence: Double,

        val routeConfidence: Double,

        val regime: CarbleRegime,

        val mediumStage: CarbleMediumStage? = null,

        val reason: CarbleDecisionReason

    ) : CarbleRouteDecision() {

        init {

            require(
                path.size >= 2
            ) {
                "Forward path must contain at least two nodes."
            }

            require(
                currentHopConfidence in 0.0..1.0
            )

            require(
                routeConfidence in 0.0..1.0
            )

            require(
                regime ==
                        CarbleRegime.HIGH ||
                        regime ==
                        CarbleRegime.MEDIUM
            )

            if (
                regime ==
                CarbleRegime.MEDIUM
            ) {

                require(
                    mediumStage ==
                            CarbleMediumStage.M1
                ) {
                    "Normal MEDIUM Forward is reserved for M1."
                }

            } else {

                require(
                    mediumStage == null
                )
            }
        }
    }


    // =====================================================
    // M2
    // =====================================================

    /*
     * Send primary first.
     *
     * Backup is activated only if the primary fails.
     */
    data class ForwardWithFailover(

        val primaryPath: List<String>,

        val backupPath: List<String>?,

        val currentHopConfidence: Double,

        val routeConfidence: Double,

        val reason: CarbleDecisionReason

    ) : CarbleRouteDecision() {

        init {

            require(
                primaryPath.size >= 2
            )

            require(
                backupPath == null ||
                        backupPath.size >= 2
            )

            require(
                currentHopConfidence in 0.0..1.0
            )

            require(
                routeConfidence in 0.0..1.0
            )

            if (
                backupPath != null
            ) {

                require(
                    primaryPath[1] !=
                            backupPath[1]
                ) {
                    "M2 backup must use a different next hop."
                }
            }
        }
    }


    // =====================================================
    // M3
    // =====================================================

    /*
     * Primary starts immediately.
     *
     * Backup may start after backupDelay if the forwarding
     * opportunity remains unresolved.
     */
    data class ForwardWithDelayedBackup(

        val primaryPath: List<String>,

        val backupPath: List<String>?,

        val backupDelay: Long,

        val currentHopConfidence: Double,

        val routeConfidence: Double,

        val reason: CarbleDecisionReason

    ) : CarbleRouteDecision() {

        init {

            require(
                primaryPath.size >= 2
            )

            require(
                backupPath == null ||
                        backupPath.size >= 2
            )

            require(
                backupDelay > 0L
            ) {
                "backupDelay must be greater than 0."
            }

            require(
                currentHopConfidence in 0.0..1.0
            )

            require(
                routeConfidence in 0.0..1.0
            )

            if (
                backupPath != null
            ) {

                require(
                    primaryPath[1] !=
                            backupPath[1]
                ) {
                    "M3 backup must use a different next hop."
                }
            }
        }
    }


    // =====================================================
    // LOW — CARRY
    // =====================================================

    data class Carry(

        val confidence: Double,

        val reevaluationNumber: Int,

        val reevaluationDelay: Long

    ) : CarbleRouteDecision() {

        init {

            require(
                confidence in 0.0..1.0
            )

            require(
                reevaluationNumber > 0
            )

            require(
                reevaluationDelay > 0L
            )
        }
    }


    // =====================================================
    // LOW — PROBE
    // =====================================================

    data class Probe(

        val path: List<String>,

        val confidence: Double

    ) : CarbleRouteDecision() {

        init {

            require(
                path.size >= 2
            )

            require(
                confidence in 0.0..1.0
            )
        }
    }


    // =====================================================
    // TERMINAL
    // =====================================================

    data class Drop(

        val confidence: Double?,

        val reason: String

    ) : CarbleRouteDecision() {

        init {

            require(
                confidence == null ||
                        confidence in 0.0..1.0
            )

            require(
                reason.isNotBlank()
            )
        }
    }
}