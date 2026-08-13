package com.example.peertopeer.network

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class B0PacketRoutingTest {

    @Test
    fun `packet follows b0 hop count dijkstra route`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")
        val e = Node("E", "Node E")

        val graph = Graph()

        listOf(a, b, c, d, e).forEach {
            graph.addNode(it)
        }

        /*
         * Shortest hop-count route:
         *
         * A -> B -> D
         *
         * 2 hops
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
         * Longer alternative:
         *
         * A -> C -> E -> D
         *
         * 3 hops
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

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = DijkstraEngine()
            )

        val route =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNotNull(route)

        /*
         * B0 should choose the fewest-hop path.
         */
        assertEquals(
            listOf(a, b, d),
            route!!.path
        )

        assertEquals(
            2,
            route.totalCost
        )

        /*
         * Create packet at source A.
         */
        val packet =
            Packet(
                messageId = "MSG-B0-001",
                sourceId = "A",
                destinationId = "D",
                createdAt = 1000L,
                ttl = 5,
                payload = "Hello D"
            )

        var state =
            PacketState(
                packet = packet,
                currentNodeId = packet.sourceId,
                remainingTtl = packet.ttl
            )

        /*
         * Forward packet using the exact path
         * returned by B0.
         *
         * route.path[0] is A itself,
         * so forwarding starts from index 1.
         */
        for (
        nextNode in
        route.path.drop(1)
        ) {

            state =
                state.forwardTo(
                    nextNodeId =
                        nextNode.nodeId
                )
        }

        /*
         * Packet should now be at D.
         */
        assertEquals(
            "D",
            state.currentNodeId
        )

        /*
         * It crossed:
         *
         * A -> B
         * B -> D
         *
         * = 2 hops
         */
        assertEquals(
            2,
            state.hopCount
        )

        /*
         * Initial TTL = 5
         * after 2 hops = 3
         */
        assertEquals(
            3,
            state.remainingTtl
        )

        /*
         * Destination reached.
         */
        if (
            state.currentNodeId ==
            packet.destinationId
        ) {
            state =
                state.markDelivered()
        }

        assertTrue(
            state.delivered
        )

        println()
        println("===== B0 PACKET ROUTING TEST =====")
        println("Selected route: ${route.path.map { it.nodeId }}")
        println("Route cost: ${route.totalCost}")
        println("Packet ID: ${packet.messageId}")
        println("Final node: ${state.currentNodeId}")
        println("Hop count: ${state.hopCount}")
        println("Remaining TTL: ${state.remainingTtl}")
        println("Delivered: ${state.delivered}")
        println("==================================")
        println()
    }
}
