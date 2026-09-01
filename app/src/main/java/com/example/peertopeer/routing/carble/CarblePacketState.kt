package com.example.peertopeer.routing.carble

data class CarblePacketState(

    val messageId: String,

    val regime: CarbleRegime? = null,

    val mediumStage: CarbleMediumStage? = null,

    /*
     * Previous physical forwarding node.
     *
     * Used to avoid immediately selecting the node we
     * just came from as a backup candidate.
     */
    val previousNodeId: String? = null,

    /*
     * Current primary forwarding choice.
     */
    val primaryNextHopId: String? = null,

    /*
     * Prepared MEDIUM backup.
     */
    val backupNextHopId: String? = null,

    /*
     * Whether the backup opportunity has actually been
     * activated.
     */
    val backupUsed: Boolean = false,

    /*
     * CARBLE-v1 permits at most one backup branch.
     */
    val copyBudgetRemaining: Int = 1,

    /*
     * LOW carry/reevaluation counter.
     */
    val lowReevaluations: Int = 0,

    /*
     * In M3, whichever branch wins first owns forwarding.
     */
    val forwardingWinnerNodeId: String? = null

) {

    init {

        require(
            messageId.isNotBlank()
        ) {
            "messageId must not be blank."
        }

        require(
            copyBudgetRemaining >= 0
        ) {
            "copyBudgetRemaining must not be negative."
        }

        require(
            lowReevaluations >= 0
        ) {
            "lowReevaluations must not be negative."
        }

        if (
            regime !=
            CarbleRegime.MEDIUM
        ) {

            require(
                mediumStage == null
            ) {
                "mediumStage must be null outside MEDIUM."
            }
        }
    }
}