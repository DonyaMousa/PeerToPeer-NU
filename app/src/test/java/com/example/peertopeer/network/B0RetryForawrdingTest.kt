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

class B0RetryForwardingTest {

    @Test
    fun `b0 packet survives failed transmission using retry`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val d = Node("D", "Node D")

        val graph = Graph()

        listOf(a, b, d).forEach {
            graph.addNode(it)
        }

        graph.addEdge(
            Edge("A", "B", 1)
        )

        graph.addEdge(
            Edge("B", "D", 1)
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

        val transmitter =
            RetryLinkTransmitter(
                maxAttempts = 3
            ) { from, to, _, attempt ->

                /*
                 * A -> B:
                 *
                 * attempt 1 fails
                 * attempt 2 succeeds
                 *
                 * B -> D:
                 *
                 * succeeds immediately.
                 */
                when {

                    from == "A" &&
                            to == "B" ->
                        attempt >= 2

                    else ->
                        true
                }
            }

        val simulator =
            B0ForwardingSimulator(
                graph = graph,
                routingTable = routingTable,
                nodes = simulatedNodes,
                linkTransmitter =
                    transmitter
            )

        val packet =
            Packet(
                messageId =
                    "MSG-RETRY-B0-001",
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
            listOf("A", "B", "D"),
            result.visitedNodes
        )

        assertEquals(
            2,
            result.successfulHops
        )

        /*
         * A -> B = 2 attempts
         * B -> D = 1 attempt
         *
         * Total = 3
         */
        assertEquals(
            3,
            result.transmissionAttempts
        )

        /*
         * 3 physical attempts
         * - 2 successful hops
         * = 1 retransmission
         */
        assertEquals(
            1,
            result.retransmissions
        )

        println()
        println("===== B0 RETRY FORWARDING =====")
        println(
            "Visited: ${result.visitedNodes}"
        )
        println(
            "Successful hops: ${result.successfulHops}"
        )
        println(
            "Transmission attempts: ${result.transmissionAttempts}"
        )
        println(
            "Retransmissions: ${result.retransmissions}"
        )
        println(
            "Delivered: ${result.finalState.delivered}"
        )
        println(
            "Dropped: ${result.finalState.dropped}"
        )
        println("===============================")
        println()
    }
}
