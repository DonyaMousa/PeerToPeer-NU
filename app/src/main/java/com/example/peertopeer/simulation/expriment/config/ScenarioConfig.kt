package com.example.peertopeer.simulation.experiment.config

data class ScenarioConfig(

    /*
     * Stable scenario identifier.
     *
     * Examples:
     *
     * HEALTHY-ALT-01
     * GRADUAL-DEGRADATION-01
     * PARTITION-RECOVERY-01
     */
    val scenarioId: String,

    /*
     * Human-readable description.
     */
    val scenarioName: String,

    /*
     * Topology family.
     *
     * Examples:
     *
     * line
     * alternate-path
     * grid
     * sparse-random
     * dense-random
     */
    val topologyType: String,

    /*
     * Number of simulated nodes.
     */
    val nodeCount: Int,

    /*
     * Queue configuration used by forwarding nodes.
     */
    val queueCapacity: Int,

    /*
     * Processing time required by one node
     * to service one queued packet.
     */
    val serviceTime: Long,

    /*
     * Optional description of the controlled
     * network condition.
     *
     * Examples:
     *
     * healthy
     * congestion
     * retry-degradation
     * gradual-degradation
     * hard-link-failure
     * partition-recovery
     */
    val conditionName: String,

    /*
     * Free research note.
     *
     * Never use this field as a substitute for
     * machine-readable configuration.
     */
    val notes: String = ""
) {

    init {

        require(scenarioId.isNotBlank()) {
            "scenarioId cannot be blank."
        }

        require(scenarioName.isNotBlank()) {
            "scenarioName cannot be blank."
        }

        require(topologyType.isNotBlank()) {
            "topologyType cannot be blank."
        }

        require(nodeCount >= 2) {
            "nodeCount must be at least 2."
        }

        require(queueCapacity > 0) {
            "queueCapacity must be greater than zero."
        }

        require(serviceTime > 0) {
            "serviceTime must be greater than zero."
        }

        require(conditionName.isNotBlank()) {
            "conditionName cannot be blank."
        }
    }
}
