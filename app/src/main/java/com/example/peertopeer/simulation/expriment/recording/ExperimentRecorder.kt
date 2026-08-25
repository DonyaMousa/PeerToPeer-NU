package com.example.peertopeer.simulation.experiment.recording

import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.ResourceSampleRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord

class ExperimentRecorder(
    val runId: String
) {

    init {
        require(runId.isNotBlank()) {
            "runId cannot be blank."
        }
    }

    private val packetRecords =
        mutableListOf<PacketRecord>()

    private val transmissionRecords =
        mutableListOf<TransmissionRecord>()

    private val routingEventRecords =
        mutableListOf<RoutingEventRecord>()

    private val topologyEventRecords =
        mutableListOf<TopologyEventRecord>()

    private val queueEventRecords =
        mutableListOf<QueueEventRecord>()

    private val resourceSampleRecords =
        mutableListOf<ResourceSampleRecord>()


    // =====================================================
    // PACKETS
    // =====================================================

    fun recordPacket(
        record: PacketRecord
    ) {

        require(record.runId == runId) {
            "PacketRecord runId does not match recorder runId."
        }

        packetRecords.add(record)
    }


    // =====================================================
    // TRANSMISSIONS
    // =====================================================

    fun recordTransmission(
        record: TransmissionRecord
    ) {

        require(record.runId == runId) {
            "TransmissionRecord runId does not match recorder runId."
        }

        transmissionRecords.add(record)
    }


    // =====================================================
    // ROUTING
    // =====================================================

    fun recordRoutingEvent(
        record: RoutingEventRecord
    ) {

        require(record.runId == runId) {
            "RoutingEventRecord runId does not match recorder runId."
        }

        routingEventRecords.add(record)
    }


    // =====================================================
    // TOPOLOGY
    // =====================================================

    fun recordTopologyEvent(
        record: TopologyEventRecord
    ) {

        require(record.runId == runId) {
            "TopologyEventRecord runId does not match recorder runId."
        }

        topologyEventRecords.add(record)
    }


    // =====================================================
    // QUEUE
    // =====================================================

    fun recordQueueEvent(
        record: QueueEventRecord
    ) {

        require(record.runId == runId) {
            "QueueEventRecord runId does not match recorder runId."
        }

        queueEventRecords.add(record)
    }


    // =====================================================
    // RESOURCE SAMPLES
    // =====================================================

    fun recordResourceSample(
        record: ResourceSampleRecord
    ) {

        require(record.runId == runId) {
            "ResourceSampleRecord runId does not match recorder runId."
        }

        resourceSampleRecords.add(record)
    }


    // =====================================================
    // READ-ONLY SNAPSHOTS
    // =====================================================

    fun getPacketRecords():
            List<PacketRecord> {

        return packetRecords.toList()
    }


    fun getTransmissionRecords():
            List<TransmissionRecord> {

        return transmissionRecords.toList()
    }


    fun getRoutingEventRecords():
            List<RoutingEventRecord> {

        return routingEventRecords.toList()
    }


    fun getTopologyEventRecords():
            List<TopologyEventRecord> {

        return topologyEventRecords.toList()
    }


    fun getQueueEventRecords():
            List<QueueEventRecord> {

        return queueEventRecords.toList()
    }


    fun getResourceSampleRecords():
            List<ResourceSampleRecord> {

        return resourceSampleRecords.toList()
    }


    // =====================================================
    // RESET
    // =====================================================

    fun clear() {

        packetRecords.clear()
        transmissionRecords.clear()
        routingEventRecords.clear()
        topologyEventRecords.clear()
        queueEventRecords.clear()
        resourceSampleRecords.clear()
    }
}
