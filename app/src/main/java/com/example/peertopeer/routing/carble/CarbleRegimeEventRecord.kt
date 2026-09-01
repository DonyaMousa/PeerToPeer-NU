package com.example.peertopeer.routing.carble

data class CarbleRegimeEventRecord(

    val runId: String?,

    val messageId: String,

    val eventTime: Long,

    val currentNodeId: String,

    val destinationId: String,

    /*
     * Confidence of the immediate forwarding hop.
     *
     * Null when no route/evaluation exists.
     */
    val currentHopConfidence: Double?,

    /*
     * Minimum confidence across the remaining route.
     *
     * Null when no route/evaluation exists.
     */
    val routeConfidence: Double?,

    val previousRegime: CarbleRegime?,

    val regime: CarbleRegime,

    val mediumStage: CarbleMediumStage?,

    /*
     * Stored as text so the event stream can represent
     * both controller reasons and action-outcome reasons
     * such as NO_ROUTE, PROBE_FAILURE, or
     * MEDIUM_FAILURE_ESCALATION.
     */
    val reason: String,

    val bottleneckFromNodeId: String?,

    val bottleneckToNodeId: String?,

    val primaryNextHopId: String?,

    val backupNextHopId: String?,

    /*
     * Simulator-facing action produced by this event.
     *
     * Examples:
     * FORWARD
     * FORWARD_WITH_FAILOVER
     * FORWARD_WITH_DELAYED_BACKUP
     * CARRY
     * PROBE
     * DROP
     */
    val action: String
) {

    init {

        require(
            messageId.isNotBlank()
        ) {
            "messageId must not be blank."
        }

        require(
            eventTime >= 0L
        ) {
            "eventTime must not be negative."
        }

        require(
            currentNodeId.isNotBlank()
        ) {
            "currentNodeId must not be blank."
        }

        require(
            destinationId.isNotBlank()
        ) {
            "destinationId must not be blank."
        }

        require(
            currentHopConfidence == null ||
                    currentHopConfidence in 0.0..1.0
        ) {
            "currentHopConfidence must be null or between 0.0 and 1.0."
        }

        require(
            routeConfidence == null ||
                    routeConfidence in 0.0..1.0
        ) {
            "routeConfidence must be null or between 0.0 and 1.0."
        }

        if (
            regime ==
                    CarbleRegime.MEDIUM
        ) {

            requireNotNull(
                mediumStage
            ) {
                "MEDIUM regime events must contain mediumStage."
            }

        } else {

            require(
                mediumStage == null
            ) {
                "HIGH and LOW regime events must not contain mediumStage."
            }
        }

        require(
            reason.isNotBlank()
        ) {
            "reason must not be blank."
        }

        require(
            action.isNotBlank()
        ) {
            "action must not be blank."
        }
    }
}
