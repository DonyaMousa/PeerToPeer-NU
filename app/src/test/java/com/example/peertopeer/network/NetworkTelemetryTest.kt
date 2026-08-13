package com.example.peertopeer.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTelemetryTest {

    private fun createResult(
        messageId: String,
        delivered: Boolean,
        attemptedHops: Int,
        successfulHops: Int,
        attempts: Int
    ): ForwardingResult {

        val packet =
            Packet(
                messageId = messageId,
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Test"
            )

        var state =
            PacketState(
                packet = packet,
                currentNodeId =
                    if (delivered) {
                        "D"
                    } else {
                        "A"
                    },
                remainingTtl = 3,
                hopCount = successfulHops
            )

        state =
            if (delivered) {
                state.markDelivered()
            } else {
                state.markDropped()
            }

        return ForwardingResult(
            finalState = state,
            visitedNodes =
                if (delivered) {
                    listOf(
                        "A",
                        "B",
                        "D"
                    )
                } else {
                    listOf("A")
                },
            attemptedHops =
                attemptedHops,
            successfulHops =
                successfulHops,
            transmissionAttempts =
                attempts
        )
    }

    @Test
    fun `telemetry aggregates packet results correctly`() {

        val telemetry =
            NetworkTelemetry()

        /*
         * PACKET 1
         *
         * A -> B -> D
         *
         * Both hops succeed immediately.
         *
         * attempted hops = 2
         * successful hops = 2
         * physical attempts = 2
         * retransmissions = 0
         */
        telemetry.record(
            createResult(
                messageId = "MSG-001",
                delivered = true,
                attemptedHops = 2,
                successfulHops = 2,
                attempts = 2
            )
        )

        /*
         * PACKET 2
         *
         * A -> B
         *
         * attempt 1 fails
         * attempt 2 succeeds
         *
         * B -> D succeeds immediately.
         *
         * attempted hops = 2
         * successful hops = 2
         * physical attempts = 3
         * retransmissions = 1
         */
        telemetry.record(
            createResult(
                messageId = "MSG-002",
                delivered = true,
                attemptedHops = 2,
                successfulHops = 2,
                attempts = 3
            )
        )

        /*
         * PACKET 3
         *
         * A -> B
         *
         * attempt 1 fails
         * attempt 2 fails
         * attempt 3 fails
         *
         * Packet is dropped.
         *
         * attempted hops = 1
         * successful hops = 0
         * physical attempts = 3
         * retransmissions = 2
         */
        telemetry.record(
            createResult(
                messageId = "MSG-003",
                delivered = false,
                attemptedHops = 1,
                successfulHops = 0,
                attempts = 3
            )
        )

        /*
         * PACKET COUNTS
         */

        assertEquals(
            3,
            telemetry.generatedPackets
        )

        assertEquals(
            2,
            telemetry.deliveredPackets
        )

        assertEquals(
            1,
            telemetry.droppedPackets
        )

        /*
         * ATTEMPTED HOPS
         *
         * Packet 1 = 2
         * Packet 2 = 2
         * Packet 3 = 1
         *
         * Total = 5
         */
        assertEquals(
            5,
            telemetry.attemptedHops
        )

        /*
         * SUCCESSFUL HOPS
         *
         * Packet 1 = 2
         * Packet 2 = 2
         * Packet 3 = 0
         *
         * Total = 4
         */
        assertEquals(
            4,
            telemetry.successfulHops
        )

        /*
         * PHYSICAL TRANSMISSION ATTEMPTS
         *
         * Packet 1 = 2
         * Packet 2 = 3
         * Packet 3 = 3
         *
         * Total = 8
         */
        assertEquals(
            8,
            telemetry.transmissionAttempts
        )

        /*
         * PDR
         *
         * 2 delivered / 3 generated
         */
        assertEquals(
            2.0 / 3.0,
            telemetry.packetDeliveryRatio(),
            0.0001
        )

        /*
         * DROP RATE
         *
         * 1 dropped / 3 generated
         */
        assertEquals(
            1.0 / 3.0,
            telemetry.dropRate(),
            0.0001
        )

        /*
         * ATTEMPTS PER DELIVERED PACKET
         *
         * 8 attempts / 2 delivered
         *
         * = 4.0
         */
        assertEquals(
            4.0,
            telemetry.attemptsPerDeliveredPacket(),
            0.0001
        )

        /*
         * SUCCESSFUL PHYSICAL ATTEMPTS
         *
         * Every successful hop corresponds
         * to one successful physical attempt.
         *
         * = 4
         */
        assertEquals(
            4,
            telemetry.successfulTransmissionAttempts()
        )

        /*
         * FAILED PHYSICAL ATTEMPTS
         *
         * total attempts - successful attempts
         *
         * 8 - 4 = 4
         */
        assertEquals(
            4,
            telemetry.failedTransmissionAttempts()
        )

        /*
         * INITIAL ATTEMPTS
         *
         * One first attempt per attempted hop.
         *
         * = 5
         */
        assertEquals(
            5,
            telemetry.initialTransmissionAttempts()
        )

        /*
         * TRUE RETRANSMISSIONS
         *
         * transmission attempts - attempted hops
         *
         * 8 - 5 = 3
         */
        assertEquals(
            3,
            telemetry.retransmissions()
        )

        println()
        println("===== NETWORK TELEMETRY TEST =====")

        println(
            "Generated packets: ${telemetry.generatedPackets}"
        )

        println(
            "Delivered packets: ${telemetry.deliveredPackets}"
        )

        println(
            "Dropped packets: ${telemetry.droppedPackets}"
        )

        println(
            "Attempted hops: ${telemetry.attemptedHops}"
        )

        println(
            "Successful hops: ${telemetry.successfulHops}"
        )

        println(
            "Transmission attempts: ${telemetry.transmissionAttempts}"
        )

        println(
            "Successful transmission attempts: " +
                    telemetry.successfulTransmissionAttempts()
        )

        println(
            "Failed transmission attempts: " +
                    telemetry.failedTransmissionAttempts()
        )

        println(
            "Initial transmission attempts: " +
                    telemetry.initialTransmissionAttempts()
        )

        println(
            "Retransmissions: ${telemetry.retransmissions()}"
        )

        println(
            "PDR: ${telemetry.packetDeliveryRatio() * 100.0}%"
        )

        println(
            "Drop rate: ${telemetry.dropRate() * 100.0}%"
        )

        println(
            "Attempts per delivered packet: " +
                    telemetry.attemptsPerDeliveredPacket()
        )

        println("==================================")
        println()
    }
}