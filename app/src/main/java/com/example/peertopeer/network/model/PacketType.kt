package com.example.peertopeer.network.model

enum class PacketType {
    HELLO,
    RUN_CONTROL,
    TOPOLOGY,
    CHAT,
    /** Versioned metadata that lets every member reconstruct the same group. */
    GROUP_CONTROL,
    /** A one-hop research probe that never falls back to a relay route. */
    DIRECT_PROBE,
    EXPERIMENT,
    DELIVERY_ACK,
    /** Confirms that the immediate next node decoded and durably stored a packet. */
    HOP_ACK
}
