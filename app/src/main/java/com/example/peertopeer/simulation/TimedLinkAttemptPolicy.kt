package com.example.peertopeer.simulation

fun interface TimedLinkAttemptPolicy {

    fun shouldSucceed(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        attemptNumber: Int,
        attemptTime: Long
    ): Boolean
}
