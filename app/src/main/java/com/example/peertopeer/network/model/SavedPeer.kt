package com.example.peertopeer.network.model

data class SavedPeer(
    val userId: String,
    val displayName: String,
    val addedAtEpochMs: Long,
    val lastSeenEpochMs: Long? = null
)
