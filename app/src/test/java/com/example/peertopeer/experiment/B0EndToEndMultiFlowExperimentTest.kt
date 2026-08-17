package com.example.peertopeer.experiment

import com.example.peertopeer.network.Packet
import com.example.peertopeer.simulation.SimulatedServiceNode
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedForwardingSimulator
import com.example.peertopeer.simulation.TimedNetworkTelemetry
import org.junit.Test

class B0EndToEndMultiFlowExperimentTest {

    @Test
    fun `multiple flows sharing relay reduce end to end performance`() {

        val simulation =
            SimulationEngine()

        lateinit var forwardingSimulator:
                TimedForwardingSimulator

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 5,
                serviceTime = 5L,
                simulationEngine = simulation
            ) { packetState, completionTime ->

                forwardingSimulator
                    .recordRelayProcessed(
                        packetState = packetState,
                        completionTime = completionTime
                    )
            }

        forwardingSimulator =
            TimedForwardingSimulator(
                simulationEngine = simulation,
                relayNode = relayB,
                relayToDestinationDelay = 2L
            )

        val sourceIds =
            listOf(
                "A",
                "C",
                "E",
                "F"
            )

        val packetsPerSource = 5

        sourceIds.forEachIndexed {
                sourceIndex,
                sourceId ->

            for (index in 0 until packetsPerSource) {

                val creationTime =
                    sourceIndex.toLong() +
                            (index * 6L)

                val packet =
                    Packet(
                        /*
                         * Include source ID so message IDs
                         * remain unique across flows.
                         */
                        messageId =
                            "MSG-$sourceId-$index",
                        sourceId = sourceId,
                        destinationId = "D",
                        createdAt = creationTime,
                        ttl = 10,
                        payload =
                            "Message $index from $sourceId"
                    )

                simulation.schedule(
                    atTime = creationTime
                ) {

                    forwardingSimulator
                        .sendThroughRelay(
                            packet = packet,
                            relayNodeId = "B"
                        )
                }
            }
        }

        simulation.run()

        val telemetry =
            TimedNetworkTelemetry()

        forwardingSimulator
            .getResults()
            .forEach {
                telemetry.record(it)
            }

        println()
        println(
            "===== B0 END-TO-END MULTI-FLOW ====="
        )

        println(
            "Sources: $sourceIds"
        )

        println(
            "Generated: ${telemetry.generatedPackets()}"
        )

        println(
            "Delivered: ${telemetry.deliveredPackets()}"
        )

        println(
            "Dropped: ${telemetry.droppedPackets()}"
        )

        println(
            "PDR: ${telemetry.packetDeliveryRatio() * 100.0}%"
        )

        println(
            "Average latency: ${telemetry.averageLatency()}"
        )

        println(
            "Median latency: ${telemetry.medianLatency()}"
        )

        println(
            "Max latency: ${telemetry.maxLatency()}"
        )

        println(
            "Average queue wait: " +
                    relayB.averageQueueWaitingTime()
        )

        println(
            "Max queue wait: " +
                    relayB.maxQueueWaitingTime
        )

        println(
            "Max queue size: " +
                    relayB.maxQueueSize
        )

        println(
            "===================================="
        )

        println()
    }
}
