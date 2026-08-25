package com.example.peertopeer.simulation

import com.example.peertopeer.network.Packet
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentInstrumentationTest {

    @Test
    fun instrumentation_records_packet_and_physical_attempts_correctly() {

        val simulationEngine =
            SimulationEngine()

        val runId =
            "B0-INSTRUMENTATION-TEST-001"

        val recorder =
            ExperimentRecorder(
                runId = runId
            )

        val instrumentation =
            RecorderInstrumentation(
                recorder = recorder
            )

        /*
         * Desired behavior:
         *
         * A -> B
         *   attempt 1 = fail
         *   attempt 2 = success
         *
         * B -> D
         *   attempt 1 = success
         *
         * Therefore:
         *
         * logical hops = 2
         * physical attempts = 3
         * retransmissions = 1
         */
        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = simulationEngine,
                maxAttempts = 2,
                delayPerAttempt = 1,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            fromNodeId,
                            toNodeId,
                            _,
                            attemptNumber,
                            _ ->

                        when {

                            fromNodeId == "A" &&
                                    toNodeId == "B" &&
                                    attemptNumber == 1 -> {

                                false
                            }

                            else -> {
                                true
                            }
                        }
                    },
                runId = runId,
                instrumentation = instrumentation
            )

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter,
                runId = runId,
                instrumentation = instrumentation
            )

        /*
         * A is source-only for this controlled test.
         *
         * B forwards.
         * D is destination.
         */
        simulator.addNode(
            nodeId = "B",
            queueCapacity = 10,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "D",
            queueCapacity = 10,
            serviceTime = 1
        )

        val packet =
            Packet(
                messageId =
                    "MSG-INSTRUMENTATION-1",
                sourceId =
                    "A",
                destinationId =
                    "D",
                createdAt =
                    0,
                ttl =
                    10,
                payload =
                    "instrumentation test"
            )

        simulator.send(
            packet = packet,
            path = listOf(
                "A",
                "B",
                "D"
            )
        )

        simulationEngine.run()

        // =================================================
        // RAW RECORDS
        // =================================================

        val packetRecords =
            recorder.getPacketRecords()

        val transmissionRecords =
            recorder.getTransmissionRecords()

        println()
        println(
            "===== EXPERIMENT INSTRUMENTATION ====="
        )

        println(
            "Packet records: ${packetRecords.size}"
        )

        println(
            "Transmission records: ${transmissionRecords.size}"
        )

        transmissionRecords.forEach {

            println(
                "hop=${it.logicalHopIndex} " +
                        "${it.fromNodeId}->${it.toNodeId} " +
                        "attempt=${it.attemptNumber} " +
                        "time=${it.attemptTime} " +
                        "success=${it.success}"
            )
        }

        val packetRecord =
            packetRecords.single()

        println()
        println(
            "Packet delivered: ${packetRecord.delivered}"
        )

        println(
            "Packet dropped: ${packetRecord.dropped}"
        )

        println(
            "Hop count: ${packetRecord.hopCount}"
        )

        println(
            "Latency: ${packetRecord.endToEndLatency}"
        )

        println(
            "Termination time: ${packetRecord.terminationTime}"
        )

        println(
            "======================================"
        )


        // =================================================
        // PACKET ACCOUNTING
        // =================================================

        assertEquals(
            1,
            packetRecords.size
        )

        assertTrue(
            packetRecord.delivered
        )

        assertFalse(
            packetRecord.dropped
        )

        assertEquals(
            null,
            packetRecord.dropReason
        )

        assertEquals(
            null,
            packetRecord.terminationTime
        )

        /*
         * Path:
         *
         * A -> B -> D
         *
         * therefore final hop count should be 2.
         */
        assertEquals(
            2,
            packetRecord.hopCount
        )


        // =================================================
        // PHYSICAL ATTEMPTS
        // =================================================

        assertEquals(
            3,
            transmissionRecords.size
        )

        val firstAttempt =
            transmissionRecords[0]

        assertEquals(
            "A",
            firstAttempt.fromNodeId
        )

        assertEquals(
            "B",
            firstAttempt.toNodeId
        )

        assertEquals(
            0,
            firstAttempt.logicalHopIndex
        )

        assertEquals(
            1,
            firstAttempt.attemptNumber
        )

        assertFalse(
            firstAttempt.success
        )


        val secondAttempt =
            transmissionRecords[1]

        assertEquals(
            "A",
            secondAttempt.fromNodeId
        )

        assertEquals(
            "B",
            secondAttempt.toNodeId
        )

        assertEquals(
            0,
            secondAttempt.logicalHopIndex
        )

        assertEquals(
            2,
            secondAttempt.attemptNumber
        )

        assertTrue(
            secondAttempt.success
        )


        val thirdAttempt =
            transmissionRecords[2]

        assertEquals(
            "B",
            thirdAttempt.fromNodeId
        )

        assertEquals(
            "D",
            thirdAttempt.toNodeId
        )

        assertEquals(
            1,
            thirdAttempt.logicalHopIndex
        )

        assertEquals(
            1,
            thirdAttempt.attemptNumber
        )

        assertTrue(
            thirdAttempt.success
        )


        // =================================================
        // LOGICAL / PHYSICAL CONSISTENCY
        // =================================================

        val logicalHopCount =
            transmissionRecords
                .mapNotNull {
                    it.logicalHopIndex
                }
                .distinct()
                .size

        val physicalAttemptCount =
            transmissionRecords.size

        val retransmissionCount =
            physicalAttemptCount -
                    logicalHopCount

        assertEquals(
            2,
            logicalHopCount
        )

        assertEquals(
            3,
            physicalAttemptCount
        )

        assertEquals(
            1,
            retransmissionCount
        )
    }
}
