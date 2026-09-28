package com.example.peertopeer.network.model

data class PeerGroup(
    val groupId: String,
    val name: String,
    val memberNodeIds: List<String>,
    val ownerNodeId: String = "",
    val version: Long = 1L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
