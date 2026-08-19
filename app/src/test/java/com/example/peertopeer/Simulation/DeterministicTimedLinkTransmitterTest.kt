package com.example.peertopeer.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicTimedLinkTransmitterTest {

    @Test
    fun `link succeeds on second attempt`() {

        val transmitter =
            DeterministicTimedLinkTransmitter(
                maxAttempts = 3,
                delayPerAttempt = 2L
            ) {
                    fromNodeId,
                    toNodeId,
                    _,
                    attemptNumber ->

                if (
                    fromNodeId == "B" &&
                    toNodeId == "C"
                ) {
                    attemptNumber == 2
                } else {
                    true
                }
            }

        val result =
            transmitter.transmit(
                fromNodeId = "B",
                toNodeId = "C",
                messageId = "MSG-A-0"
            )

        assertTrue(
            result.success
        )

        assertEquals(
            2,
            result.attempts
        )

        assertEquals(
            1,
            result.retransmissions
        )

        assertEquals(
            4L,
            result.totalDelay
        )

        println()
        println(
            "===== TIMED LINK RETRY TEST ====="
        )

        println(
            "Success: ${result.success}"
        )

        println(
            "Attempts: ${result.attempts}"
        )

        println(
            "Retransmissions: ${result.retransmissions}"
        )

        println(
            "Total link delay: ${result.totalDelay}"
        )

        println(
            "================================="
        )
        println()
    }
}
