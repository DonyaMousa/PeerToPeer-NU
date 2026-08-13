package com.example.peertopeer.experiment
import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.B0ForwardingSimulator
import com.example.peertopeer.network.NetworkTelemetry
import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.RetryLinkTransmitter
import com.example.peertopeer.network.SimulatedNode
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Test

class B0PacketReliabilityExperimentTest {

    @Test
    fun `b0 reliability under controlled unreliable shortest path`() {

        /*
         * ---------------------------------------------------------
         * 1. CREATE NODES
         * ---------------------------------------------------------
         */

        val a =
            Node(
                "A",
                "Node A"
            )

        val b =
            Node(
                "B",
                "Node B"
            )

        val c =
            Node(
                "C",
                "Node C"
            )

        val d =
            Node(
                "D",
                "Node D"
            )

        val e =
            Node(
                "E",
                "Node E"
            )

        /*
         * ---------------------------------------------------------
         * 2. CREATE GRAPH
         * ---------------------------------------------------------
         */

        val graph =
            Graph()

        listOf(
            a,
            b,
            c,
            d,
            e
        ).forEach { node ->

            graph.addNode(node)
        }

        /*
         * B0 preferred route:
         *
         * A -> B -> D
         *
         * Cost = 2 hops
         */

        graph.addEdge(
            Edge(
                from = "A",
                to = "B",
                weight = 1
            )
        )

        graph.addEdge(
            Edge(
                from = "B",
                to = "D",
                weight = 1
            )
        )

        /*
         * Alternative route:
         *
         * A -> C -> E -> D
         *
         * Cost = 3 hops
         *
         * Because B0 uses hop count only,
         * this route is not selected.
         */

        graph.addEdge(
            Edge(
                from = "A",
                to = "C",
                weight = 1
            )
        )

        graph.addEdge(
            Edge(
                from = "C",
                to = "E",
                weight = 1
            )
        )

        graph.addEdge(
            Edge(
                from = "E",
                to = "D",
                weight = 1
            )
        )

        /*
         * ---------------------------------------------------------
         * 3. CREATE B0 ROUTING TABLE
         * ---------------------------------------------------------
         */

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine =
                    DijkstraEngine()
            )

        /*
         * ---------------------------------------------------------
         * 4. CREATE SIMULATED PHONES
         * ---------------------------------------------------------
         */

        val simulatedNodes =
            graph
                .getNodes()
                .associate { node ->

                    node.nodeId to
                            SimulatedNode(
                                node = node,
                                queueCapacity = 20
                            )
                }

        /*
         * ---------------------------------------------------------
         * 5. CONTROLLED LINK RELIABILITY
         * ---------------------------------------------------------
         *
         * We use packetNumber % 10.
         *
         * 0..6
         *   70% of packets
         *
         *   A -> B succeeds immediately.
         *
         *
         * 7..8
         *   20% of packets
         *
         *   A -> B:
         *
         *   attempt 1 = fail
         *   attempt 2 = success
         *
         *
         * 9
         *   10% of packets
         *
         *   A -> B:
         *
         *   attempt 1 = fail
         *   attempt 2 = fail
         *   attempt 3 = fail
         *
         *   packet is dropped.
         *
         *
         * B -> D always succeeds.
         */

        val transmitter =
            RetryLinkTransmitter(
                maxAttempts = 3
            ) { from, to, packetState, attempt ->

                if (
                    from == "A" &&
                    to == "B"
                ) {

                    val packetNumber =
                        packetState
                            .packet
                            .messageId
                            .substringAfterLast("-")
                            .toInt()

                    when (
                        packetNumber % 10
                    ) {

                        /*
                         * 70%
                         *
                         * Immediate success.
                         */
                        0, 1, 2, 3, 4, 5, 6 -> {
                            true
                        }

                        /*
                         * 20%
                         *
                         * First attempt fails.
                         * Second succeeds.
                         */
                        7, 8 -> {
                            attempt >= 2
                        }

                        /*
                         * 10%
                         *
                         * All three attempts fail.
                         */
                        else -> {
                            false
                        }
                    }

                } else {

                    /*
                     * All other links are stable.
                     */
                    true
                }
            }

        /*
         * ---------------------------------------------------------
         * 6. CREATE FORWARDING SIMULATOR
         * ---------------------------------------------------------
         */

        val simulator =
            B0ForwardingSimulator(
                graph = graph,
                routingTable = routingTable,
                nodes = simulatedNodes,
                linkTransmitter = transmitter
            )

        /*
         * ---------------------------------------------------------
         * 7. CREATE NETWORK TELEMETRY
         * ---------------------------------------------------------
         */

        val telemetry =
            NetworkTelemetry()

        /*
         * ---------------------------------------------------------
         * 8. SEND 100 PACKETS
         * ---------------------------------------------------------
         */

        for (
        packetNumber in 0 until 100
        ) {

            val packet =
                Packet(
                    messageId =
                        "MSG-$packetNumber",
                    sourceId = "A",
                    destinationId = "D",
                    createdAt =
                        packetNumber.toLong(),
                    ttl = 10,
                    payload =
                        "Message $packetNumber"
                )

            val result =
                simulator.send(packet)

            telemetry.record(result)
        }

        /*
         * ---------------------------------------------------------
         * 9. PACKET-LEVEL EXPECTED RESULTS
         * ---------------------------------------------------------
         */

        assertEquals(
            100,
            telemetry.generatedPackets
        )

        /*
         * 90 packets survive.
         */
        assertEquals(
            90,
            telemetry.deliveredPackets
        )

        /*
         * 10 packets fail on A -> B.
         */
        assertEquals(
            10,
            telemetry.droppedPackets
        )

        /*
         * PDR:
         *
         * 90 / 100
         *
         * = 0.90
         */
        assertEquals(
            0.90,
            telemetry.packetDeliveryRatio(),
            0.0001
        )

        /*
         * Drop rate:
         *
         * 10 / 100
         *
         * = 0.10
         */
        assertEquals(
            0.10,
            telemetry.dropRate(),
            0.0001
        )

        /*
         * ---------------------------------------------------------
         * 10. ATTEMPTED HOPS
         * ---------------------------------------------------------
         *
         * 90 delivered packets:
         *
         * 90 × 2 attempted hops
         * = 180
         *
         * 10 failed packets:
         *
         * each attempted A -> B once
         *
         * = 10
         *
         * TOTAL:
         *
         * 180 + 10
         * = 190 attempted hops
         */

        assertEquals(
            190,
            telemetry.attemptedHops
        )

        /*
         * ---------------------------------------------------------
         * 11. SUCCESSFUL HOPS
         * ---------------------------------------------------------
         *
         * Only the 90 delivered packets
         * complete both hops.
         *
         * 90 × 2
         *
         * = 180 successful hops
         */

        assertEquals(
            180,
            telemetry.successfulHops
        )

        /*
         * ---------------------------------------------------------
         * 12. TOTAL PHYSICAL TRANSMISSION ATTEMPTS
         * ---------------------------------------------------------
         *
         * 70 immediate-success packets:
         *
         * A -> B = 1
         * B -> D = 1
         *
         * 70 × 2
         * = 140
         *
         *
         * 20 packets requiring retry:
         *
         * A -> B = 2 attempts
         * B -> D = 1 attempt
         *
         * 20 × 3
         * = 60
         *
         *
         * 10 failed packets:
         *
         * A -> B = 3 attempts
         *
         * 10 × 3
         * = 30
         *
         *
         * TOTAL:
         *
         * 140 + 60 + 30
         * = 230
         */

        assertEquals(
            230,
            telemetry.transmissionAttempts
        )

        /*
         * ---------------------------------------------------------
         * 13. SUCCESSFUL PHYSICAL ATTEMPTS
         * ---------------------------------------------------------
         *
         * Every successful hop has exactly
         * one successful physical attempt.
         *
         * = 180
         */

        assertEquals(
            180,
            telemetry.successfulTransmissionAttempts()
        )

        /*
         * ---------------------------------------------------------
         * 14. FAILED PHYSICAL ATTEMPTS
         * ---------------------------------------------------------
         *
         * transmission attempts
         * -
         * successful physical attempts
         *
         * 230 - 180
         *
         * = 50
         */

        assertEquals(
            50,
            telemetry.failedTransmissionAttempts()
        )

        /*
         * ---------------------------------------------------------
         * 15. INITIAL TRANSMISSION ATTEMPTS
         * ---------------------------------------------------------
         *
         * Every attempted hop has one
         * initial physical attempt.
         *
         * attempted hops = 190
         */

        assertEquals(
            190,
            telemetry.initialTransmissionAttempts()
        )

        /*
         * ---------------------------------------------------------
         * 16. TRUE RETRANSMISSIONS
         * ---------------------------------------------------------
         *
         * Physical attempts after the first
         * attempt of each attempted hop.
         *
         * total attempts - attempted hops
         *
         * 230 - 190
         *
         * = 40 retransmissions
         */

        assertEquals(
            40,
            telemetry.retransmissions()
        )

        /*
         * ---------------------------------------------------------
         * 17. ATTEMPTS PER DELIVERED PACKET
         * ---------------------------------------------------------
         *
         * 230 total attempts
         * /
         * 90 delivered packets
         *
         * = approximately 2.5556
         */

        assertEquals(
            230.0 / 90.0,
            telemetry.attemptsPerDeliveredPacket(),
            0.0001
        )

        /*
         * ---------------------------------------------------------
         * 18. ROUTING CACHE
         * ---------------------------------------------------------
         *
         * The topology never changes.
         *
         * Therefore:
         *
         * first request = cache miss
         * next 99 = cache hits
         *
         * Dijkstra should run only once.
         */

        assertEquals(
            100,
            routingTable.telemetry.routeRequests
        )

        assertEquals(
            99,
            routingTable.telemetry.cacheHits
        )

        assertEquals(
            1,
            routingTable.telemetry.cacheMisses
        )

        assertEquals(
            1,
            routingTable.telemetry.routeCalculations
        )

        /*
         * ---------------------------------------------------------
         * 19. PRINT EXPERIMENT RESULT
         * ---------------------------------------------------------
         */

        println()
        println(
            "===== B0 PACKET RELIABILITY EXPERIMENT ====="
        )

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
            "PDR: ${telemetry.packetDeliveryRatio() * 100.0}%"
        )

        println(
            "Drop rate: ${telemetry.dropRate() * 100.0}%"
        )

        println()

        println(
            "Attempted hops: ${telemetry.attemptedHops}"
        )

        println(
            "Successful hops: ${telemetry.successfulHops}"
        )

        println()

        println(
            "Transmission attempts: " +
                    telemetry.transmissionAttempts
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
            "Retransmissions: " +
                    telemetry.retransmissions()
        )

        println(
            "Attempts per delivered packet: " +
                    telemetry.attemptsPerDeliveredPacket()
        )

        println()

        println("ROUTING CACHE")

        println(
            "Route requests: " +
                    routingTable.telemetry.routeRequests
        )

        println(
            "Cache hits: " +
                    routingTable.telemetry.cacheHits
        )

        println(
            "Cache misses: " +
                    routingTable.telemetry.cacheMisses
        )

        println(
            "Dijkstra calculations: " +
                    routingTable.telemetry.routeCalculations
        )

        println(
            "============================================"
        )

        println()
    }
}