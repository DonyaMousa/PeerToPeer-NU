package com.example.peertopeer.simulation.experiment.config

data class ExperimentConfig(

    /*
     * Groups a complete experiment campaign.
     *
     * Example:
     *
     * B0-DAY06-V1
     *
     * Later:
     *
     * CORE-COMPARISON-V1
     */
    val experimentSetId: String,

    /*
     * Unique ID for this independent run.
     *
     * Example:
     *
     * B0-HEALTHY-R001
     */
    val runId: String,

    /*
     * Protocol being evaluated.
     *
     * Examples later:
     *
     * B0
     * MM
     * BINARY
     * DYNABLE_RX
     */
    val protocol: String,

    /*
     * Explicit protocol version.
     *
     * Important once B0 becomes frozen.
     */
    val protocolVersion: String,

    /*
     * Independent replication number.
     *
     * Example:
     *
     * 1, 2, 3, ...
     */
    val runIndex: Int,

    /*
     * Random seed controlling stochastic
     * experiment behavior.
     *
     * Later the SAME seed/run pairing should
     * be reused across competing protocols.
     */
    val seed: Long,

    /*
     * Traffic configuration.
     */
    val traffic: TrafficConfig,

    /*
     * Link/retry configuration.
     */
    val link: LinkConfig,

    /*
     * Topology / environment configuration.
     */
    val scenario: ScenarioConfig,

    /*
     * Git commit can be filled when exporting
     * the final run.
     *
     * Do not fake it if unavailable.
     */
    val gitCommit: String? = null,

    /*
     * Extra reproducibility notes.
     */
    val notes: String = ""
) {

    init {

        require(experimentSetId.isNotBlank()) {
            "experimentSetId cannot be blank."
        }

        require(runId.isNotBlank()) {
            "runId cannot be blank."
        }

        require(protocol.isNotBlank()) {
            "protocol cannot be blank."
        }

        require(protocolVersion.isNotBlank()) {
            "protocolVersion cannot be blank."
        }

        require(runIndex > 0) {
            "runIndex must be greater than zero."
        }
    }
}
