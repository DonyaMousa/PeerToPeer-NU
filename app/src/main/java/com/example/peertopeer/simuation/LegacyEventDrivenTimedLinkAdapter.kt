package com.example.peertopeer.simulation

class LegacyEventDrivenTimedLinkAdapter(
    private val simulationEngine: SimulationEngine,
    private val legacyTransmitter: TimedLinkTransmitter
) : EventDrivenTimedLinkTransmitter {

    override fun transmit(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        startTime: Long,
        onComplete: (
            result: TimedLinkResult,
            completionTime: Long
        ) -> Unit
    ) {
        val result = legacyTransmitter.transmit(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            messageId = messageId
        )

        val completionTime =
            startTime + result.totalDelay

        simulationEngine.schedule(completionTime) {
            onComplete(
                result,
                completionTime
            )
        }
    }
}
