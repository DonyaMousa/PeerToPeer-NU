package com.example.peertopeer.simulation.experiment.instrumentation

import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord

interface ExperimentInstrumentation {

    fun onTransmission(
        record: TransmissionRecord
    )

    fun onRoutingEvent(
        record: RoutingEventRecord
    )

    fun onTopologyEvent(
        record: TopologyEventRecord
    )

    fun onPacketFinished(
        record: PacketRecord
    )

    fun onQueueEvent(
        record: QueueEventRecord
    ) {
        // Optional instrumentation hook.
    }
}