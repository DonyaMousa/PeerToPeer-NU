package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import org.junit.Assert.assertEquals
import org.junit.Test

class B0LinkUnavailableDropTest {

    @Test
    fun packet_drops_with_link_unavailable_when_next_node_does_not_exist() {

        val simulationEngine =
            SimulationEngine()

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = simulationEngine,
                maxAttempts = 1,
                delayPerAttempt = 1,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            _,
                            _,
                            _,
                            _,
                            _ ->

                        true
                    }
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter
            )

        /*
         * Intentionally do NOT add N1.
         *
         * The route says N0 -> N1, but N1 has no
         * TimedNetworkNode instance.
         */
        val packet =
            Packet(
                messageId = "LINK-TEST-MSG-0",
                sourceId = "N0",
                destinationId = "N1",
                createdAt = 0L,
                ttl = 10,
                payload = "TEST"
            )

        simulator.send(
            packet = packet,
            path =
                listOf(
                    "N0",
                    "N1"
                )
        )

        simulationEngine.run()

        val results =
            simulator.getResults()

        assertEquals(
            1,
            results.size
        )

        val result =
            results.single()

        assertEquals(
            false,
            result.delivered
        )

        assertEquals(
            true,
            result.dropped
        )

        assertEquals(
            PacketDropReason.LINK_UNAVAILABLE,
            result.dropReason
        )

        println(
            "Link unavailable drop reason: ${result.dropReason}"
        )
    }
}
