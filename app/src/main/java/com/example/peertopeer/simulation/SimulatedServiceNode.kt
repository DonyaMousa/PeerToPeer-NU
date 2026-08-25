package com.example.peertopeer.simulation

import com.example.peertopeer.network.PacketQueue
import com.example.peertopeer.network.PacketState
import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventType

class SimulatedServiceNode(
    val nodeId: String,
    queueCapacity: Int,
    val serviceTime: Long,
    private val simulationEngine: SimulationEngine,
    private val runId: String? = null,
    private val instrumentation: ExperimentInstrumentation? = null,
    private val onPacketProcessed: (PacketState, Long) -> Unit
) {

    init {
        require(nodeId.isNotBlank()) {
            "nodeId cannot be blank."
        }

        require(serviceTime > 0L) {
            "serviceTime must be greater than zero."
        }
    }

    private val queue =
        PacketQueue(
            capacity = queueCapacity
        )

    /*
     * Store the simulated time at which each
     * packet entered this node's queue.
     */
    private val enqueueTimes =
        mutableMapOf<String, Long>()

    private var busy = false

    var droppedPackets: Int = 0
        private set

    var processedPackets: Int = 0
        private set

    var maxQueueSize: Int = 0
        private set

    var totalQueueWaitingTime: Long = 0L
        private set

    var maxQueueWaitingTime: Long = 0L
        private set

    fun receive(
        packetState: PacketState
    ): Boolean {

        require(
            packetState.currentNodeId == nodeId
        ) {
            "Packet current node does not match this service node."
        }

        val currentTime =
            simulationEngine.currentTime

        val accepted =
            queue.enqueue(packetState)

        // =================================================
        // QUEUE FULL
        // =================================================

        if (!accepted) {

            droppedPackets++

            recordQueueEvent(
                packetState = packetState,
                eventTime = currentTime,
                eventType = QueueEventType.DROPPED_FULL,
                queueSizeAfterEvent = queue.size(),
                waitTime = null
            )

            return false
        }

        // =================================================
        // ENQUEUED
        // =================================================

        enqueueTimes[
            packetState.packet.messageId
        ] = currentTime

        if (queue.size() > maxQueueSize) {
            maxQueueSize = queue.size()
        }

        recordQueueEvent(
            packetState = packetState,
            eventTime = currentTime,
            eventType = QueueEventType.ENQUEUED,
            queueSizeAfterEvent = queue.size(),
            waitTime = null
        )

        /*
         * If the node is idle, this packet begins
         * service immediately.
         */
        if (!busy) {
            startNextPacket()
        }

        return true
    }

    fun queuedPackets(): Int {
        return queue.size()
    }

    fun isBusy(): Boolean {
        return busy
    }

    fun averageQueueWaitingTime(): Double {

        if (processedPackets == 0) {
            return 0.0
        }

        return totalQueueWaitingTime.toDouble() /
                processedPackets.toDouble()
    }

    private fun startNextPacket() {

        val packet =
            queue.dequeue()

        if (packet == null) {

            busy = false

            return
        }

        busy = true

        val currentTime =
            simulationEngine.currentTime

        val enqueueTime =
            enqueueTimes.remove(
                packet.packet.messageId
            ) ?: currentTime

        val waitingTime =
            currentTime -
                    enqueueTime

        totalQueueWaitingTime +=
            waitingTime

        if (
            waitingTime >
            maxQueueWaitingTime
        ) {
            maxQueueWaitingTime =
                waitingTime
        }

        // =================================================
        // DEQUEUED / SERVICE START
        // =================================================

        recordQueueEvent(
            packetState = packet,
            eventTime = currentTime,
            eventType = QueueEventType.DEQUEUED,
            queueSizeAfterEvent = queue.size(),
            waitTime = waitingTime
        )

        val completionTime =
            currentTime +
                    serviceTime

        simulationEngine.schedule(
            atTime = completionTime
        ) {

            processedPackets++

            onPacketProcessed(
                packet,
                completionTime
            )

            busy = false

            startNextPacket()
        }
    }

    private fun recordQueueEvent(
        packetState: PacketState,
        eventTime: Long,
        eventType: QueueEventType,
        queueSizeAfterEvent: Int,
        waitTime: Long?
    ) {

        val actualRunId =
            runId
                ?: return

        val actualInstrumentation =
            instrumentation
                ?: return

        actualInstrumentation.onQueueEvent(
            QueueEventRecord(
                runId = actualRunId,
                messageId =
                    packetState.packet.messageId,
                nodeId =
                    nodeId,
                eventTime =
                    eventTime,
                eventType =
                    eventType,
                queueSizeAfterEvent =
                    queueSizeAfterEvent,
                waitTime =
                    waitTime
            )
        )
    }
}