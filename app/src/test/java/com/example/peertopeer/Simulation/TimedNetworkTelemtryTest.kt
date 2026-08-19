package com.example.peertopeer.simulation

import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.peertopeer.network.PacketDropReason

class TimedNetworkTelemetryTest {

    @Test
    fun `telemetry calculates destination metrics`() {

        val telemetry =
            TimedNetworkTelemetry()

        /*
         * Delivered:
         * latency = 7
         */
        telemetry.record(
            TimedDeliveryResult(
                messageId = "MSG-0",
                createdAt = 0L,
                deliveredAt = 7L,
                delivered = true,
                dropped = false
            )
        )

        /*
         * Delivered:
         * latency = 11
         */
        telemetry.record(
            TimedDeliveryResult(
                messageId = "MSG-1",
                createdAt = 1L,
                deliveredAt = 12L,
                delivered = true,
                dropped = false
            )
        )

        /*
         * Delivered:
         * latency = 15
         */
        telemetry.record(
            TimedDeliveryResult(
                messageId = "MSG-2",
                createdAt = 2L,
                deliveredAt = 17L,
                delivered = true,
                dropped = false
            )
        )

        /*
         * Dropped packet.
         */
        telemetry.record(
            TimedDeliveryResult(
                messageId = "MSG-3",
                createdAt = 3L,
                deliveredAt = null,
                delivered = false,
                dropped = true,
                dropReason = PacketDropReason.QUEUE_FULL
            )
        )

        assertEquals(
            4,
            telemetry.generatedPackets()
        )

        assertEquals(
            3,
            telemetry.deliveredPackets()
        )

        assertEquals(
            1,
            telemetry.droppedPackets()
        )

        assertEquals(
            0.75,
            telemetry.packetDeliveryRatio(),
            0.0001
        )
        assertEquals(
            1,
            telemetry.dropsByReason(
                PacketDropReason.QUEUE_FULL
            )
        )

        assertEquals(
            0,
            telemetry.dropsByReason(
                PacketDropReason.RETRY_EXHAUSTED
            )
        )

        /*
         * Latencies:
         *
         * 7, 11, 15
         */

        assertEquals(
            11.0,
            telemetry.averageLatency(),
            0.0001
        )

        assertEquals(
            11.0,
            telemetry.medianLatency(),
            0.0001
        )

        assertEquals(
            15L,
            telemetry.maxLatency()
        )

        /*
         * 3 delivered packets
         * over 20 simulated units.
         *
         * = 0.15 packets / time unit
         */
        assertEquals(
            0.15,
            telemetry.throughput(
                experimentDuration = 20L
            ),
            0.0001
        )

        println()
        println(
            "===== TIMED NETWORK TELEMETRY ====="
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
            "Throughput: ${
                telemetry.throughput(
                    experimentDuration = 20L
                )
            } packets/time-unit"
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
            "TTL-expired drops: ${
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
            "==================================="
        )

        println()
    }
}
