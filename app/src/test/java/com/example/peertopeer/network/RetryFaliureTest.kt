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

class B0RetryFailureTest {

    @Test
    fun `b0 packet drops after retry budget is exhausted`() {

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
                routingEngine = DijkstraEngine()
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

        /*
         * Every attempt on A -> B fails.
         *
         * Retry budget = 3.
         */
        val transmitter =
            RetryLinkTransmitter(
                maxAttempts = 3
            ) { from, to, _, _ ->

                when {

                    from == "A" &&
                            to == "B" ->
                        false

                    else ->
                        true
                }
            }

        val simulator =
            B0ForwardingSimulator(
                graph = graph,
                routingTable = routingTable,
                nodes = simulatedNodes,
                linkTransmitter = transmitter
            )

        val packet =
            Packet(
                messageId = "MSG-FAIL-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Hello D"
            )

        val result =
            simulator.send(packet)

        /*
         * Packet never reached destination.
         */
        assertFalse(
            result.finalState.delivered
        )

        assertTrue(
            result.finalState.dropped
        )

        /*
         * It never left A successfully.
         */
        assertEquals(
            listOf("A"),
            result.visitedNodes
        )

        assertEquals(
            0,
            result.successfulHops
        )

        /*
         * Three failed physical attempts.
         */
        assertEquals(
            3,
            result.transmissionAttempts
        )

        /*
         * Current derived value:
         *
         * attempts - successful hops
         *
         * = 3 - 0
         * = 3
         */
        assertEquals(
            3,
            result.retransmissions
        )

        assertEquals(
            0,
            result.finalState.hopCount
        )

        assertEquals(
            5,
            result.finalState.remainingTtl
        )

        println()
        println("===== B0 RETRY FAILURE =====")
        println("Visited: ${result.visitedNodes}")
        println("Successful hops: ${result.successfulHops}")
        println("Transmission attempts: ${result.transmissionAttempts}")
        println("Retransmission attempts: ${result.retransmissions}")
        println("Delivered: ${result.finalState.delivered}")
        println("Dropped: ${result.finalState.dropped}")
        println("============================")
        println()
    }
}
