package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.simulation.EventDrivenRetryLinkTransmitter
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TimedLinkAttemptPolicy
import com.example.peertopeer.simulation.TimedNetworkSimulator
import org.junit.Assert.assertEquals
import org.junit.Test

class B0TtlExpiredDropTest {

    @Test
    fun packet_drops_with_ttl_expired_when_ttl_is_exhausted() {

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

        simulator.addNode(
            nodeId = "N1",
            queueCapacity = 10,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "N2",
            queueCapacity = 10,
            serviceTime = 1
        )

        val packet =
            Packet(
                messageId = "TTL-TEST-MSG-0",
                sourceId = "N0",
                destinationId = "N2",
                createdAt = 0L,

                /*
                 * Only one forwarding hop is allowed.
                 *
                 * The packet reaches N1, then has no TTL
                 * left for N1 -> N2.
                 */
                ttl = 1,

                payload = "TEST"
            )

        simulator.send(
            packet = packet,
            path =
                listOf(
                    "N0",
                    "N1",
                    "N2"
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
            PacketDropReason.TTL_EXPIRED,
            result.dropReason
        )

        println(
            "TTL drop reason: ${result.dropReason}"
        )
    }
}