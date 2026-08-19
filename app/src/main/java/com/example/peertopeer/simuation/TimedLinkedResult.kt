package com.example.peertopeer.simulation

data class TimedLinkResult(
    val success: Boolean,
    val attempts: Int,
    val totalDelay: Long
) {

    init {
        require(attempts > 0) {
            "attempts must be greater than zero."
        }

        require(totalDelay >= 0L) {
            "totalDelay cannot be negative."
        }
    }

    val retransmissions: Int
        get() = attempts - 1
}
