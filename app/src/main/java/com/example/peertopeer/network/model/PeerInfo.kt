package com.example.peertopeer.network.model

data class PeerInfo(
    val nodeId: String,
    val displayName: String,
    val isDirect: Boolean,
    val transportState: String = "Discovered",
    val rssi: Int? = null,
    val address: String? = null,
    val lastSeenElapsedMs: Long = 0L,
    val hopEstimate: Int? = null
)
