package com.example.peertopeer.simulation

import java.util.PriorityQueue

class SimulationEngine {

    private val eventQueue =
        PriorityQueue<SimulationEvent>(
            compareBy<SimulationEvent> {
                it.scheduledTime
            }.thenBy {
                it.sequenceNumber
            }
        )

    private var nextSequenceNumber = 0L

    var currentTime: Long = 0L
        private set

    fun schedule(
        atTime: Long,
        action: () -> Unit
    ) {

        require(atTime >= currentTime) {
            "Cannot schedule an event in the past."
        }

        val event =
            SimulationEvent(
                scheduledTime = atTime,
                sequenceNumber =
                    nextSequenceNumber++,
                action = action
            )

        eventQueue.add(event)
    }

    fun run() {

        while (eventQueue.isNotEmpty()) {

            val event =
                eventQueue.poll()
                    ?: continue

            currentTime =
                event.scheduledTime

            event.action()
        }
    }

    fun runUntil(
        endTime: Long
    ) {

        require(endTime >= currentTime) {
            "endTime cannot be earlier than current simulation time."
        }

        while (eventQueue.isNotEmpty()) {

            val nextEvent =
                eventQueue.peek()
                    ?: break

            if (
                nextEvent.scheduledTime >
                endTime
            ) {
                break
            }

            val event =
                eventQueue.poll()
                    ?: continue

            currentTime =
                event.scheduledTime

            event.action()
        }

        /*
         * Advance the simulated clock to
         * the requested experiment boundary.
         */
        currentTime = endTime
    }

    fun pendingEvents(): Int {
        return eventQueue.size
    }

    fun isIdle(): Boolean {
        return eventQueue.isEmpty()
    }

    fun reset() {

        eventQueue.clear()

        currentTime = 0L
        nextSequenceNumber = 0L
    }
}
