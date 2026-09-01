package com.example.peertopeer.routing.carble

class CarbleBackupSelector {

    companion object {

        const val DELIVERY_WEIGHT =
            0.35

        const val PROGRESS_WEIGHT =
            0.25

        const val FRESHNESS_WEIGHT =
            0.15

        const val QUEUE_AVAILABILITY_WEIGHT =
            0.15

        const val STABILITY_WEIGHT =
            0.10
    }


    // =====================================================
    // SCORE
    // =====================================================

    fun calculateScore(
        candidate: CarbleBackupCandidate
    ): Double {

        /*
         * K_j =
         *
         * 0.35 DeliveryProbability
         * + 0.25 Progress
         * + 0.15 Freshness
         * + 0.15 QueueAvailability
         * + 0.10 ContactStability
         */
        val score =

            DELIVERY_WEIGHT *
                    candidate.deliveryProbability +

                    PROGRESS_WEIGHT *
                    candidate.progress +

                    FRESHNESS_WEIGHT *
                    candidate.freshness +

                    QUEUE_AVAILABILITY_WEIGHT *
                    candidate.queueAvailability +

                    STABILITY_WEIGHT *
                    candidate.contactStability


        return score.coerceIn(
            0.0,
            1.0
        )
    }


    // =====================================================
    // SELECT BACKUP
    // =====================================================

    fun selectBackup(
        primaryNextHopId: String,
        previousNodeId: String?,
        excludedNodeIds: Set<String> = emptySet(),
        candidates: List<CarbleBackupCandidate>
    ): CarbleBackupSelection? {

        require(
            primaryNextHopId.isNotBlank()
        ) {
            "primaryNextHopId must not be blank."
        }

        if (
            previousNodeId != null
        ) {

            require(
                previousNodeId.isNotBlank()
            ) {
                "previousNodeId must not be blank when supplied."
            }
        }


        /*
         * CARBLE permits only ONE backup.
         *
         * Candidate generation will later verify:
         *
         * - real graph neighbor
         * - valid route toward destination
         *
         * This pure selector is responsible for:
         *
         * - not selecting the primary
         * - not sending immediately backward
         * - respecting exclusions
         * - ranking eligible candidates
         */
        val eligibleCandidates =
            candidates.filter { candidate ->

                candidate.nextHopId !=
                        primaryNextHopId &&

                        candidate.nextHopId !=
                        previousNodeId &&

                        candidate.nextHopId !in
                        excludedNodeIds
            }


        if (
            eligibleCandidates.isEmpty()
        ) {

            return null
        }


        /*
         * Highest K wins.
         *
         * nextHopId is used as a deterministic secondary
         * ordering rule so identical inputs always produce
         * identical simulation results.
         */
        val selected =
            eligibleCandidates
                .map { candidate ->

                    CarbleBackupSelection(

                        candidate =
                            candidate,

                        score =
                            calculateScore(
                                candidate
                            )
                    )
                }
                .sortedWith(

                    compareByDescending<
                            CarbleBackupSelection
                            > {
                        it.score
                    }
                        .thenBy {
                            it.candidate.nextHopId
                        }
                )
                .first()


        return selected
    }
}