package com.example.peertopeer.simulation.experiment.instrumentation

import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord


class RecorderInstrumentation(
    private val recorder: ExperimentRecorder
) : ExperimentInstrumentation {

    override fun onTransmission(
        record: TransmissionRecord
    ) {
        recorder.recordTransmission(record)
    }

    override fun onRoutingEvent(
        record: RoutingEventRecord
    ) {
        recorder.recordRoutingEvent(record)
    }

    override fun onTopologyEvent(
        record: TopologyEventRecord
    ) {
        recorder.recordTopologyEvent(record)
    }

    override fun onPacketFinished(
        record: PacketRecord
    ) {
        recorder.recordPacket(record)
    }
    override fun onQueueEvent(
        record: QueueEventRecord
    ) {
        recorder.recordQueueEvent(record)
    }
}
