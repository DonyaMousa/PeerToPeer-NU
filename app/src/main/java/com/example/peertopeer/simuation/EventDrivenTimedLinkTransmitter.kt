package com.example.peertopeer.simulation

interface EventDrivenTimedLinkTransmitter {

    fun transmit(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        startTime: Long,
        onComplete: (
            result: TimedLinkResult,
            completionTime: Long
        ) -> Unit
    )
}
