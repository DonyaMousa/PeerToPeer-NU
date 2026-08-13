package com.example.peertopeer.network

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B0ForwardingSimulatorTest {

    @Test
    fun `b0 simulator forwards packet hop by hop`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")
        val e = Node("E", "Node E")

        val graph = Graph()

        listOf(
            a,
            b,
            c,
            d,
            e
        ).forEach {
            graph.addNode(it)
        }

        /*
         * B0 route:
         *
         * A -> B -> D
         *
         * 2 hops
         */
        graph.addEdge(
            Edge("A", "B", 1)
        )

        graph.addEdge(
            Edge("B", "D", 1)
        )

        /*
         * Longer alternative:
         *
         * A -> C -> E -> D
         *
         * 3 hops
         */
        graph.addEdge(
            Edge("A", "C", 1)
        )

        graph.addEdge(
            Edge("C", "E", 1)
        )

        graph.addEdge(
            Edge("E", "D", 1)
        )

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine =
                    DijkstraEngine()
            )

        val simulatedNodes =
            graph
                .getNodes()
                .associate { node ->

                    node.nodeId to
                            SimulatedNode(
                                node = node,
                                queueCapacity = 10
                            )
                }

        val simulator =
            B0ForwardingSimulator(
                graph = graph,
                routingTable = routingTable,
                nodes = simulatedNodes,
                linkTransmitter =
                    RetryLinkTransmitter(
                        maxAttempts = 1
                    ) { _, _, _, _ ->
                        true
                    }
            )

        val packet =
            Packet(
                messageId = "MSG-SIM-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Hello D"
            )

        val result =
            simulator.send(packet)

        assertTrue(
            result.finalState.delivered
        )

        assertFalse(
            result.finalState.dropped
        )

        assertEquals(
            listOf(
                "A",
                "B",
                "D"
            ),
            result.visitedNodes
        )

        assertEquals(
            2,
            result.transmissionAttempts
        )

        assertEquals(
            2,
            result.finalState.hopCount
        )

        assertEquals(
            3,
            result.finalState.remainingTtl
        )

        /*
         * After successful forwarding,
         * relay queues should be empty.
         */
        assertEquals(
            0,
            simulatedNodes["A"]!!
                .queuedPackets()
        )

        assertEquals(
            0,
            simulatedNodes["B"]!!
                .queuedPackets()
        )

        println()
        println("===== B0 FORWARDING SIMULATOR =====")
        println("Packet: ${packet.messageId}")
        println("Visited: ${result.visitedNodes}")
        println("Transmissions: ${result.transmissionAttempts}")
        println("Hop count: ${result.finalState.hopCount}")
        println("Remaining TTL: ${result.finalState.remainingTtl}")
        println("Delivered: ${result.finalState.delivered}")
        println("Dropped: ${result.finalState.dropped}")
        println("===================================")
        println()
    }
}
