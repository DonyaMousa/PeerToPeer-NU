package com.example.peertopeer.network

interface LinkTransmitter {

    fun transmit(
        fromNodeId: String,
        toNodeId: String,
        packetState: PacketState
    ): LinkTransmissionResult
}