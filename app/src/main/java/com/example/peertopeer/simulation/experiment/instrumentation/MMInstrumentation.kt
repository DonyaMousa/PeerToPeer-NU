package com.example.peertopeer.simulation.experiment.instrumentation

import com.example.peertopeer.routing.mm.MultiMetricObservationTracker
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord

class MMInstrumentation(

    private val delegate:
    ExperimentInstrumentation,

    private val observationTracker:
    MultiMetricObservationTracker,

    private val queueCapacityByNode:
    Map<String, Int>,

    /*
     * Simulation delay per physical attempt.
     *
     * Same value used by EventDrivenRetryLinkTransmitter.
     */
    private val retryDelay: Long

) : ExperimentInstrumentation {

    init {

        require(retryDelay > 0) {
            "retryDelay must be greater than 0."
        }
    }

    // =====================================================
    // TRANSMISSION
    // =====================================================

    override fun onTransmission(
        record: TransmissionRecord
    ) {

        /*
         * Preserve canonical experiment evidence.
         */
        delegate.onTransmission(
            record
        )

        /*
         * Delay proxy for the current logical-hop attempt.
         *
         * Example:
         *
         * retryDelay = 1
         *
         * attempt 1 -> 1
         * attempt 2 -> 2
         * attempt 3 -> 3
         *
         * This remains simulation time, not real ms.
         */
        val observedDelay =
            record.attemptNumber
                .toDouble() *
                    retryDelay.toDouble()

        /*
         * Update MM reliability + delay evidence.
         */
        observationTracker.observeTransmission(
            fromNodeId =
                record.fromNodeId,

            toNodeId =
                record.toNodeId,

            success =
                record.success,

            observedDelay =
                observedDelay
        )
    }

    // =====================================================
    // ROUTING
    // =====================================================

    override fun onRoutingEvent(
        record: RoutingEventRecord
    ) {

        delegate.onRoutingEvent(
            record
        )
    }

    // =====================================================
    // TOPOLOGY
    // =====================================================

    override fun onTopologyEvent(
        record: TopologyEventRecord
    ) {

        delegate.onTopologyEvent(
            record
        )

        observationTracker.observeTopologyChange(
            fromNodeId =
                record.fromNodeId,

            toNodeId =
                record.toNodeId
        )
    }

    // =====================================================
    // PACKET OUTCOME
    // =====================================================

    override fun onPacketFinished(
        record: PacketRecord
    ) {

        delegate.onPacketFinished(
            record
        )
    }

    // =====================================================
    // QUEUE
    // =====================================================

    override fun onQueueEvent(
        record: QueueEventRecord
    ) {

        delegate.onQueueEvent(
            record
        )

        val queueCapacity =
            queueCapacityByNode[
                record.nodeId
            ]
                ?: return

        observationTracker.observeQueue(
            nodeId =
                record.nodeId,

            queueOccupancy =
                record.queueSizeAfterEvent,

            queueCapacity =
                queueCapacity
        )
    }
}