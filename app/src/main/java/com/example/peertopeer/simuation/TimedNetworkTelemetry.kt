package com.example.peertopeer.simulation
import com.example.peertopeer.network.PacketDropReason
class TimedNetworkTelemetry {

    private val results =
        mutableListOf<TimedDeliveryResult>()

    fun record(
        result: TimedDeliveryResult
    ) {
        results.add(result)
    }

    fun generatedPackets(): Int {
        return results.size
    }

    fun deliveredPackets(): Int {
        return results.count {
            it.delivered
        }
    }

    fun droppedPackets(): Int {
        return results.count {
            it.dropped
        }
    }

    fun packetDeliveryRatio(): Double {

        if (results.isEmpty()) {
            return 0.0
        }

        return deliveredPackets().toDouble() /
                generatedPackets().toDouble()
    }

    fun deliveredLatencies(): List<Long> {

        return results
            .mapNotNull {
                it.endToEndLatency()
            }
            .sorted()
    }

    fun averageLatency(): Double {

        val latencies =
            deliveredLatencies()

        if (latencies.isEmpty()) {
            return 0.0
        }

        return latencies.average()
    }

    fun medianLatency(): Double {

        val latencies =
            deliveredLatencies()

        if (latencies.isEmpty()) {
            return 0.0
        }

        val middle =
            latencies.size / 2

        return if (
            latencies.size % 2 == 0
        ) {

            (
                    latencies[middle - 1] +
                            latencies[middle]
                    ) / 2.0

        } else {

            latencies[middle].toDouble()
        }
    }

    fun maxLatency(): Long {

        return deliveredLatencies()
            .maxOrNull()
            ?: 0L
    }

    fun throughput(
        experimentDuration: Long
    ): Double {

        require(
            experimentDuration > 0L
        ) {
            "experimentDuration must be greater than zero."
        }

        return deliveredPackets().toDouble() /
                experimentDuration.toDouble()
    }
    fun dropsByReason(
        reason: PacketDropReason
    ): Int {

        return results.count {
            it.dropped &&
                    it.dropReason == reason
        }
    }
    fun allDropReasons():
            Map<PacketDropReason, Int> {

        return PacketDropReason
            .entries
            .associateWith { reason ->
                dropsByReason(reason)
            }
    }
}
