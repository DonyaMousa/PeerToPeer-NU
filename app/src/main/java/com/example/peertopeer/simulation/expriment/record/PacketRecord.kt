package com.example.peertopeer.simulation.experiment.record

import com.example.peertopeer.network.PacketDropReason

data class PacketRecord(
    val runId: String,
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val createdAt: Long,
    val deliveredAt: Long?,
    val droppedAt: Long?,
    val delivered: Boolean,
    val dropped: Boolean,
    val dropReason: PacketDropReason?,
    val hopCount: Int?,
    val endToEndLatency: Long?,
    val terminationTime: Long?
)
