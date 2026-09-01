package com.example.peertopeer.simulation.experiment.config

data class LinkConfig(

    /*
     * Maximum physical attempts allowed for
     * one logical hop.
     */
    val maxAttempts: Int,

    /*
     * Delay between physical transmission attempts.
     *
     * Simulation time units for now.
     */
    val retryDelay: Long,

    /*
     * Optional label for the link behavior model.
     *
     * Examples:
     *
     * deterministic
     * fixed-success-probability
     * gradual-degradation
     * scheduled-failure
     */
    val modelName: String = "deterministic",

    val successProbability: Double? = null

) {

    init {

        require(maxAttempts > 0) {
            "maxAttempts must be greater than zero."
        }

        require(retryDelay > 0) {
            "retryDelay must be greater than zero."
        }

        require(modelName.isNotBlank()) {
            "modelName cannot be blank."
        }
        successProbability?.let {
            require(it in 0.0..1.0) {
                "successProbability must be between 0.0 and 1.0."
            }
        }
    }
}
