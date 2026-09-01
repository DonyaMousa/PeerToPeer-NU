package com.example.peertopeer.routing.carble

data class CarbleBackupCandidate(

    /*
     * Immediate alternate neighbor.
     */
    val nextHopId: String,

    /*
     * Complete candidate route beginning at the current
     * node and continuing through nextHopId.
     *
     * Example:
     *
     * current = N1
     *
     * path =
     * N1 -> N3 -> N4
     */
    val path: List<String>,

    /*
     * D
     *
     * Estimated delivery suitability of this forwarding
     * candidate.
     */
    val deliveryProbability: Double,

    /*
     * P
     *
     * Normalized progress toward the destination.
     *
     * 0 = no useful progress
     * 1 = strongest available progress
     */
    val progress: Double,

    /*
     * F
     *
     * Freshness of the candidate evidence.
     */
    val freshness: Double,

    /*
     * A
     *
     * Queue availability.
     *
     * 1 = empty / highly available
     * 0 = fully congested
     */
    val queueAvailability: Double,

    /*
     * R
     *
     * Contact / link stability.
     */
    val contactStability: Double

) {

    init {

        require(
            nextHopId.isNotBlank()
        ) {
            "nextHopId must not be blank."
        }

        require(
            path.size >= 2
        ) {
            "Backup candidate path must contain at least two nodes."
        }

        require(
            path[1] ==
                    nextHopId
        ) {
            "path[1] must equal nextHopId."
        }

        require(
            deliveryProbability in 0.0..1.0
        ) {
            "deliveryProbability must be between 0.0 and 1.0."
        }

        require(
            progress in 0.0..1.0
        ) {
            "progress must be between 0.0 and 1.0."
        }

        require(
            freshness in 0.0..1.0
        ) {
            "freshness must be between 0.0 and 1.0."
        }

        require(
            queueAvailability in 0.0..1.0
        ) {
            "queueAvailability must be between 0.0 and 1.0."
        }

        require(
            contactStability in 0.0..1.0
        ) {
            "contactStability must be between 0.0 and 1.0."
        }
    }
}