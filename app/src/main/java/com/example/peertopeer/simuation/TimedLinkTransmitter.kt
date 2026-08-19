package com.example.peertopeer.simulation

interface TimedLinkTransmitter {

    fun transmit(
        fromNodeId: String,
        toNodeId: String,
        messageId: String
    ): TimedLinkResult
}
