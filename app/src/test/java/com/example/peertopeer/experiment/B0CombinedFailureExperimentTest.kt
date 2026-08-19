package com.example.peertopeer.experiment

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.simulation.DeterministicTimedLinkTransmitter
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedNetworkSimulator
import com.example.peertopeer.simulation.TimedNetworkTelemetry
import org.junit.Test

class B0CombinedFailureExperimentTest {

    @Test
    fun `congestion and unreliable link produce different drop reasons`() {

        val simulation =
            SimulationEngine()

        val transmitter =
            DeterministicTimedLinkTransmitter(
                maxAttempts = 3,
                delayPerAttempt = 1L
            ) {
                    fromNodeId,
                    toNodeId,
                    messageId,
                    attemptNumber ->

                if (
                    fromNodeId == "B" &&
                    toNodeId == "C"
                ) {

                    when (messageId) {

                        /*
                         * Always fail.
                         */
                        "MSG-A-2",
                        "MSG-A-6" ->
                            false

                        /*
                         * Fail first attempt,
                         * succeed on second.
                         */
                        "MSG-A-1",
                        "MSG-A-4",
                        "MSG-A-8" ->
                            attemptNumber >= 2

                        else ->
                            true
                    }

                } else {

                    true
                }
            }

        val network =
            TimedNetworkSimulator(
                simulationEngine = simulation,
                linkTransmitter = transmitter
            )

        network.addNode(
            nodeId = "B",
            queueCapacity = 3,
            serviceTime = 5L
        )

        network.addNode(
            nodeId = "C",
            queueCapacity = 5,
            serviceTime = 2L
        )

        network.addNode(
            nodeId = "D",
            queueCapacity = 5,
            serviceTime = 1L
        )

        val packetCount = 10

        for (index in 0 until packetCount) {

            val creationTime =
                index.toLong()

            val packet =
                Packet(
                    messageId = "MSG-A-$index",
                    sourceId = "A",
                    destinationId = "D",
                    createdAt = creationTime,
                    ttl = 10,
                    payload = "Message $index"
                )

            simulation.schedule(
                atTime = creationTime
            ) {

                network.send(
                    packet = packet,
                    path =
                        listOf(
                            "A",
                            "B",
                            "C",
                            "D"
                        )
                )
            }
        }

        simulation.run()

        val telemetry =
            TimedNetworkTelemetry()

        network
            .getResults()
            .forEach {
                telemetry.record(it)
            }
        val transmissionTelemetry =
            network.getTransmissionTelemetry()

        println()
        println(
            "===== B0 COMBINED FAILURE EXPERIMENT ====="
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
            "Queue-full drops: ${
                telemetry.dropsByReason(
                    PacketDropReason.QUEUE_FULL
                )
            }"
        )

        println(
            "Retry-exhausted drops: ${
                telemetry.dropsByReason(
                    PacketDropReason.RETRY_EXHAUSTED
                )
            }"
        )

        println(
            "TTL drops: ${
                telemetry.dropsByReason(
                    PacketDropReason.TTL_EXPIRED
                )
            }"
        )

        println(
            "No-route drops: ${
                telemetry.dropsByReason(
                    PacketDropReason.NO_ROUTE
                )
            }"
        )

        println(
            "Link-unavailable drops: ${
                telemetry.dropsByReason(
                    PacketDropReason.LINK_UNAVAILABLE
                )
            }"
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
            "B max queue: ${
                network.getNode("B")
                    ?.maxQueueSize
            }"
        )

        println(
            "B average queue wait: ${
                network.getNode("B")
                    ?.averageQueueWaitingTime()
            }"
        )

        println(
            "B max queue wait: ${
                network.getNode("B")
                    ?.maxQueueWaitingTime
            }"
        )

        println(
            "Logical hop attempts: ${
                transmissionTelemetry
                    .logicalHopAttempts()
            }"
        )

        println(
            "Transmission attempts: ${
                transmissionTelemetry
                    .transmissionAttempts()
            }"
        )

        println(
            "Successful hop transmissions: ${
                transmissionTelemetry
                    .successfulHopTransmissions()
            }"
        )

        println(
            "Failed transmission attempts: ${
                transmissionTelemetry
                    .failedTransmissionAttempts()
            }"
        )

        println(
            "Retransmissions: ${
                transmissionTelemetry
                    .retransmissions()
            }"
        )

        println(
            "=========================================="
        )

        println()
    }
}
