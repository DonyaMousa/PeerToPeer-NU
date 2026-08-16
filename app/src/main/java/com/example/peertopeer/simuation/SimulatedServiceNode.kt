package com.example.peertopeer.simulation

import com.example.peertopeer.network.PacketQueue
import com.example.peertopeer.network.PacketState

class SimulatedServiceNode(
    val nodeId: String,
    queueCapacity: Int,
    val serviceTime: Long,
    private val simulationEngine: SimulationEngine,
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
     * Store when each queued packet arrived.
     *
     * Key = messageId
     * Value = simulated arrival time
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

        val accepted =
            queue.enqueue(packetState)

        if (!accepted) {

            droppedPackets++

            return false
        }

        enqueueTimes[
            packetState.packet.messageId
        ] = simulationEngine.currentTime

        /*
         * Track the largest waiting queue
         * observed during the experiment.
         */
        if (queue.size() > maxQueueSize) {
            maxQueueSize = queue.size()
        }

        /*
         * If idle, begin processing immediately.
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

        val enqueueTime =
            enqueueTimes.remove(
                packet.packet.messageId
            ) ?: simulationEngine.currentTime

        val waitingTime =
            simulationEngine.currentTime -
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

        val completionTime =
            simulationEngine.currentTime +
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
}