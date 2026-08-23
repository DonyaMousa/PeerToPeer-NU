package com.example.peertopeer.simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.Packet
import com.example.peertopeer.routing.DijkstraEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDrivenActiveRerouteTest {

    @Test
    fun active_packet_reroutes_after_primary_link_failure() {

        // =================================================
        // 1. GRAPH
        // =================================================

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
         *        D
         *        |
         *        B
         *       / \
         *      A   C
         *           \
         *            E
         *             \
         *              D
         *
         * Primary:
         *
         * A -> B -> D
         *
         * Alternate FROM B:
         *
         * B -> C -> E -> D
         *
         * This topology is intentional.
         *
         * When B-D fails after the packet reaches B,
         * B can reroute forward without going back to A.
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


        // =================================================
        // 2. ROUTING
        // =================================================

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine =
                    DijkstraEngine()
            )

        /*
         * Before traffic starts,
         * B0 must choose the 2-hop route.
         */
        val initialRoute =
            routeProvider.findPath(
                currentNodeId = "A",
                destinationId = "D"
            )

        assertEquals(
            listOf("A", "B", "D"),
            initialRoute
        )

        println(
            "Initial route: $initialRoute"
        )


        // =================================================
        // 3. SIMULATION ENGINE
        // =================================================

        val simulationEngine =
            SimulationEngine()


        // =================================================
        // 4. RECORD ACTUAL PHYSICAL HOPS
        // =================================================

        val actualHops =
            mutableListOf<String>()

        var primaryLinkRemoved =
            false


        // =================================================
        // 5. EVENT-DRIVEN LINK TRANSMITTER
        // =================================================

        val transmitter =
            EventDrivenRetryLinkTransmitter(
                simulationEngine,
                1,
                1,
                TimedLinkAttemptPolicy {
                        fromNodeId,
                        toNodeId,
                        _,
                        _,
                        attemptTime ->

                    actualHops.add(
                        "$fromNodeId->$toNodeId"
                    )

                    /*
                     * Critical event:
                     *
                     * The packet has ALREADY been assigned
                     * its initial A -> B -> D route.
                     *
                     * During the first physical A -> B hop,
                     * B-D disappears.
                     */
                    if (
                        fromNodeId == "A" &&
                        toNodeId == "B" &&
                        !primaryLinkRemoved
                    ) {

                        graph.removeEdge(
                            from = "B",
                            to = "D"
                        )

                        primaryLinkRemoved =
                            true

                        println(
                            "t=$attemptTime: " +
                                    "TOPOLOGY CHANGE: B-D DOWN"
                        )
                    }

                    /*
                     * Transmission succeeds only if the
                     * corresponding graph edge currently
                     * exists.
                     */
                    graph.containsEdge(
                        fromNodeId,
                        toNodeId
                    )
                }
            )


        // =================================================
        // 6. NETWORK SIMULATOR
        // =================================================

        val simulator =
            TimedNetworkSimulator(
                simulationEngine =
                    simulationEngine,
                eventDrivenLinkTransmitter =
                    transmitter
            )


        // =================================================
        // 7. TIMED NETWORK NODES
        // =================================================

        /*
         * A is the source, therefore it does not need
         * queue/service processing in this test.
         *
         * B, C, E and D receive/process the packet.
         */

        simulator.addNode(
            nodeId = "B",
            queueCapacity = 10,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "C",
            queueCapacity = 10,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "E",
            queueCapacity = 10,
            serviceTime = 1
        )

        simulator.addNode(
            nodeId = "D",
            queueCapacity = 10,
            serviceTime = 1
        )


        // =================================================
        // 8. PACKET
        // =================================================

        val packet =
            Packet(
                messageId =
                    "MSG-ACTIVE-REROUTE-1",
                sourceId =
                    "A",
                destinationId =
                    "D",
                createdAt =
                    0,
                ttl =
                    10,
                payload =
                    "active reroute test"
            )


        // =================================================
        // 9. SEND USING DYNAMIC ROUTING
        // =================================================

        simulator.send(
            packet = packet,
            routeProvider = routeProvider
        )


        // =================================================
        // 10. RUN ALL EVENTS
        // =================================================

        simulationEngine.run()


        // =================================================
        // 11. RESULT
        // =================================================

        val result =
            simulator
                .getResults()
                .single()

        println()
        println(
            "===== ACTIVE B0 REROUTE ====="
        )

        println(
            "Initial route: $initialRoute"
        )

        println(
            "B-D removed: $primaryLinkRemoved"
        )

        println(
            "Actual hops: $actualHops"
        )

        println(
            "Delivered: ${result.delivered}"
        )

        println(
            "Dropped: ${result.dropped}"
        )

        println(
            "Delivered at: ${result.deliveredAt}"
        )

        println(
            "Latency: ${result.endToEndLatency()}"
        )

        println(
            "Final B-D exists: " +
                    graph.containsEdge("B", "D")
        )

        println(
            "============================="
        )


        // =================================================
        // 12. ASSERTIONS
        // =================================================

        assertTrue(
            primaryLinkRemoved
        )

        assertFalse(
            graph.containsEdge(
                "B",
                "D"
            )
        )

        /*
         * Most important assertion.
         *
         * The packet DID NOT continue:
         *
         * A -> B -> D
         *
         * Instead it dynamically used:
         *
         * A -> B -> C -> E -> D
         */
        assertEquals(
            listOf(
                "A->B",
                "B->C",
                "C->E",
                "E->D"
            ),
            actualHops
        )

        assertTrue(
            result.delivered
        )

        assertFalse(
            result.dropped
        )

        assertEquals(
            null,
            result.dropReason
        )
    }
}
