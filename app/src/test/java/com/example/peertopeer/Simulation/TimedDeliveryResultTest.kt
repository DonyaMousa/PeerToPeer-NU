package com.example.peertopeer.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedDeliveryResultTest {

    @Test
    fun `delivered packet calculates end to end latency`() {

        val result =
            TimedDeliveryResult(
                messageId = "MSG-001",
                createdAt = 2L,
                deliveredAt = 15L,
                delivered = true,
                dropped = false
            )

        assertEquals(
            13L,
            result.endToEndLatency()
        )
    }

    @Test
    fun `dropped packet has no end to end latency`() {

        val result =
            TimedDeliveryResult(
                messageId = "MSG-002",
                createdAt = 2L,
                deliveredAt = null,
                delivered = false,
                dropped = true
            )

        assertNull(
            result.endToEndLatency()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `delivered packet requires delivery timestamp`() {

        TimedDeliveryResult(
            messageId = "MSG-003",
            createdAt = 2L,
            deliveredAt = null,
            delivered = true,
            dropped = false
        )
    }
}
