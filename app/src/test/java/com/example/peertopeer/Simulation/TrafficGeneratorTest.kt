package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficGeneratorTest {

    @Test
    fun `traffic generator creates packets at correct simulated times`() {

        val simulation =
            SimulationEngine()

        val generator =
            TrafficGenerator(
                simulationEngine = simulation
            )

        val generatedPackets =
            mutableListOf<Packet>()

        generator.schedulePackets(
            count = 4,
            startTime = 0L,
            interval = 2L,
            sourceId = "A",
            destinationId = "D",
            ttl = 5
        ) { packet ->

            generatedPackets.add(packet)
        }

        simulation.run()

        assertEquals(
            4,
            generatedPackets.size
        )

        assertEquals(
            "MSG-0",
            generatedPackets[0].messageId
        )

        assertEquals(
            0L,
            generatedPackets[0].createdAt
        )

        assertEquals(
            "MSG-1",
            generatedPackets[1].messageId
        )

        assertEquals(
            2L,
            generatedPackets[1].createdAt
        )

        assertEquals(
            "MSG-2",
            generatedPackets[2].messageId
        )

        assertEquals(
            4L,
            generatedPackets[2].createdAt
        )

        assertEquals(
            "MSG-3",
            generatedPackets[3].messageId
        )

        assertEquals(
            6L,
            generatedPackets[3].createdAt
        )

        assertEquals(
            6L,
            simulation.currentTime
        )
    }
}
