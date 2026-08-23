package com.example.peertopeer.simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.routing.DijkstraEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class B0PartitionRecoveryTest {

    @Test
    fun active_packet_drops_during_partition_and_new_packet_delivers_after_recovery() {

        // =====================================================
        // 1. NETWORK GRAPH
        // =====================================================

        val graph = Graph()

        listOf(
            "A",
            "B",
            "C",
            "D",
            "E"
        ).forEach { nodeId ->

            graph.addNode(
                Node(
                    nodeId = nodeId,
                    displayName = nodeId
                )
            )
        }

        /*
         * Initial topology:
         *
         * A ---- B ---- D
         *        |
         *        C
         *        |
         *        E ---- D
         *
         *
         * Primary path:
         *
         * A -> B -> D
         *
         *
         * Backup topology:
         *
         * A -> B -> C -> E -> D
         *
         *
         * We will remove:
         *
         * B-D
         * E-D
         *
         * This isolates D completely.
         */

        graph.addEdge(
            from = "A",
            to = "B",
            weight = 1
        )

        graph.addEdge(
            from = "B",
            to = "D",
            weight = 1
        )

        graph.addEdge(
            from = "B",
            to = "C",
            weight = 1
        )

        graph.addEdge(
            from = "C",
            to = "E",
            weight = 1
        )

        graph.addEdge(
            from = "E",
            to = "D",
            weight = 1
        )


        // =====================================================
        // 2. ROUTING PROVIDER
        // =====================================================

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine()
            )

        val healthyRoute =
            routeProvider.findPath(
                currentNodeId = "A",
                destinationId = "D"
            )

        assertEquals(
            listOf("A", "B", "D"),
            healthyRoute
        )

        println(
            "Healthy route before traffic: $healthyRoute"
        )


        // =====================================================
        // 3. SIMULATION ENGINE
        // =====================================================

        val simulationEngine =
            SimulationEngine()


        // =====================================================
        // 4. OBSERVATION STATE
        // =====================================================

        var partitionInjected = false

        val actualHops =
            mutableMapOf<String, MutableList<String>>()


        // =====================================================
        // 5. EVENT-DRIVEN PHYSICAL LINK TRANSMITTER
        // =====================================================

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine = simulationEngine,
                maxAttempts = 1,
                delayPerAttempt = 1,
                attemptPolicy =
                    TimedLinkAttemptPolicy {
                            fromNodeId,
                            toNodeId,
                            messageId,
                            _,
                            attemptTime ->

                        actualHops
                            .getOrPut(messageId) {
                                mutableListOf()
                            }
                            .add(
                                "$fromNodeId->$toNodeId"
                            )

                        /*
                         * Inject the partition while
                         * PACKET 1 is physically traveling
                         * from A to B.
                         *
                         * This is important:
                         *
                         * the packet already started under
                         * a healthy topology.
                         */
                        if (
                            messageId == "MSG-PARTITION-1" &&
                            fromNodeId == "A" &&
                            toNodeId == "B" &&
                            !partitionInjected
                        ) {

                            graph.removeEdge(
                                from = "B",
                                to = "D"
                            )

                            graph.removeEdge(
                                from = "E",
                                to = "D"
                            )

                            partitionInjected = true

                            println(
                                "t=$attemptTime: " +
                                        "PARTITION CREATED: " +
                                        "B-D DOWN, E-D DOWN"
                            )
                        }

                        /*
                         * A transmission succeeds only if
                         * that physical graph link currently
                         * exists.
                         */
                        graph.containsEdge(
                            fromNodeId,
                            toNodeId
                        )
                    }
            )


        // =====================================================
        // 6. NETWORK SIMULATOR
        // =====================================================

        val simulator =
            TimedNetworkSimulator(
                simulationEngine = simulationEngine,
                eventDrivenLinkTransmitter = transmitter
            )


        // =====================================================
        // 7. RECEIVING / FORWARDING NODES
        // =====================================================

        listOf(
            "B",
            "C",
            "E",
            "D"
        ).forEach { nodeId ->

            simulator.addNode(
                nodeId = nodeId,
                queueCapacity = 10,
                serviceTime = 1
            )
        }


        // =====================================================
        // PHASE 1
        //
        // ACTIVE PACKET ENCOUNTERS PARTITION
        // =====================================================

        val firstPacket =
            Packet(
                messageId =
                    "MSG-PARTITION-1",
                sourceId =
                    "A",
                destinationId =
                    "D",
                createdAt =
                    simulationEngine.currentTime,
                ttl =
                    10,
                payload =
                    "packet during partition"
            )


        println()
        println(
            "===== PHASE 1: PARTITION ====="
        )

        simulator.send(
            packet = firstPacket,
            routeProvider = routeProvider
        )

        simulationEngine.run()


        // =====================================================
        // 8. FIRST PACKET RESULT
        // =====================================================

        val firstResult =
            simulator
                .getResults()
                .first {
                    it.messageId ==
                            firstPacket.messageId
                }

        val routeDuringPartition =
            routeProvider.findPath(
                currentNodeId = "B",
                destinationId = "D"
            )


        println()
        println(
            "Packet 1 hops: " +
                    actualHops[firstPacket.messageId]
        )

        println(
            "Route B -> D during partition: " +
                    routeDuringPartition
        )

        println(
            "Packet 1 delivered: " +
                    firstResult.delivered
        )

        println(
            "Packet 1 dropped: " +
                    firstResult.dropped
        )

        println(
            "Packet 1 reason: " +
                    firstResult.dropReason
        )

        println(
            "Packet 1 droppedAt: " +
                    firstResult.droppedAt
        )

        println(
            "Packet 1 termination time: " +
                    firstResult.timeUntilTermination()
        )


        // =====================================================
        // 9. VALIDATE PARTITION BEHAVIOR
        // =====================================================

        assertTrue(
            partitionInjected
        )

        assertFalse(
            graph.containsEdge(
                "B",
                "D"
            )
        )

        assertFalse(
            graph.containsEdge(
                "E",
                "D"
            )
        )

        assertEquals(
            null,
            routeDuringPartition
        )

        assertFalse(
            firstResult.delivered
        )

        assertTrue(
            firstResult.dropped
        )

        assertEquals(
            PacketDropReason.NO_ROUTE,
            firstResult.dropReason
        )

        assertNotNull(
            firstResult.droppedAt
        )


        // =====================================================
        // PHASE 2
        //
        // NETWORK RECOVERS
        // =====================================================

        println()
        println(
            "===== PHASE 2: RECOVERY ====="
        )

        val recoveryTime =
            simulationEngine.currentTime

        graph.addEdge(
            from = "B",
            to = "D",
            weight = 1
        )

        graph.addEdge(
            from = "E",
            to = "D",
            weight = 1
        )

        println(
            "t=$recoveryTime: " +
                    "CONNECTIVITY RESTORED"
        )


        // =====================================================
        // 10. VERIFY ROUTING RECOVERY
        // =====================================================

        val recoveredRoute =
            routeProvider.findPath(
                currentNodeId = "A",
                destinationId = "D"
            )

        println(
            "Recovered route: $recoveredRoute"
        )

        assertEquals(
            listOf("A", "B", "D"),
            recoveredRoute
        )


        // =====================================================
        // 11. SEND A NEW PACKET AFTER RECOVERY
        // =====================================================

        val secondPacket =
            Packet(
                messageId =
                    "MSG-RECOVERY-2",
                sourceId =
                    "A",
                destinationId =
                    "D",
                createdAt =
                    simulationEngine.currentTime,
                ttl =
                    10,
                payload =
                    "packet after recovery"
            )

        simulator.send(
            packet = secondPacket,
            routeProvider = routeProvider
        )

        simulationEngine.run()


        // =====================================================
        // 12. SECOND PACKET RESULT
        // =====================================================

        val secondResult =
            simulator
                .getResults()
                .first {
                    it.messageId ==
                            secondPacket.messageId
                }


        println()
        println(
            "Packet 2 hops: " +
                    actualHops[secondPacket.messageId]
        )

        println(
            "Packet 2 delivered: " +
                    secondResult.delivered
        )

        println(
            "Packet 2 dropped: " +
                    secondResult.dropped
        )

        println(
            "Packet 2 deliveredAt: " +
                    secondResult.deliveredAt
        )

        println(
            "Packet 2 latency: " +
                    secondResult.endToEndLatency()
        )


        // =====================================================
        // 13. FINAL ASSERTIONS
        // =====================================================

        assertEquals(
            listOf(
                "A->B",
                "B->D"
            ),
            actualHops[
                secondPacket.messageId
            ]
        )

        assertTrue(
            secondResult.delivered
        )

        assertFalse(
            secondResult.dropped
        )

        assertEquals(
            null,
            secondResult.dropReason
        )


        // =====================================================
        // 14. SUMMARY
        // =====================================================

        println()
        println(
            "===== B0 PARTITION / RECOVERY ====="
        )

        println(
            "Packet during partition:"
        )

        println(
            "  Delivered = " +
                    firstResult.delivered
        )

        println(
            "  Drop reason = " +
                    firstResult.dropReason
        )

        println(
            "  Terminal time = " +
                    firstResult.timeUntilTermination()
        )

        println()

        println(
            "Packet after recovery:"
        )

        println(
            "  Route = $recoveredRoute"
        )

        println(
            "  Delivered = " +
                    secondResult.delivered
        )

        println(
            "  Latency = " +
                    secondResult.endToEndLatency()
        )

        println(
            "====================================="
        )
    }
}
