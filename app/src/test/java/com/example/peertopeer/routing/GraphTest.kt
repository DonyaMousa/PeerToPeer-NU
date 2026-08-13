package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphTest {

    @Test
    fun graph_creates_nodes_and_edges() {

        val graph = Graph()

        val a = Node("A", "Phone A")
        val b = Node("B", "Phone B")
        val c = Node("C", "Phone C")

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)

        graph.addEdge(Edge("A", "B"))
        graph.addEdge(Edge("B", "C"))

        assertEquals(3, graph.getNodes().size)
        assertEquals(2, graph.getEdges().size)
    }
    @Test
    fun graph_returns_neighbors() {

        val graph = Graph()

        graph.addNode(Node("A", "Phone A"))
        graph.addNode(Node("B", "Phone B"))
        graph.addNode(Node("C", "Phone C"))

        graph.addEdge(Edge("A", "B"))
        graph.addEdge(Edge("A", "C"))

        val neighbors = graph.getNeighbors("A")

        assertEquals(2, neighbors.size)
    }
    @Test
    fun removing_node_removes_connected_edges() {

        val graph = Graph()

        graph.addNode(Node("A", "Phone A"))
        graph.addNode(Node("B", "Phone B"))

        graph.addEdge(Edge("A", "B"))

        graph.removeNode("B")

        assertEquals(1, graph.getNodes().size)
        assertEquals(0, graph.getEdges().size)
    }
    @Test
    fun graph_creates_day_one_topology() {

        val graph = Graph()

        graph.addNode(Node("A", "Node A"))
        graph.addNode(Node("B", "Node B"))
        graph.addNode(Node("C", "Node C"))
        graph.addNode(Node("D", "Node D"))

        graph.addEdge(Edge("A", "B"))
        graph.addEdge(Edge("B", "C"))
        graph.addEdge(Edge("A", "D"))
        graph.addEdge(Edge("C", "D"))

        assertEquals(4, graph.getNodes().size)
        assertEquals(4, graph.getEdges().size)
    }
    @Test
    fun graph_can_find_node_by_id() {

        val graph = Graph()

        val a = Node("A", "Phone A")

        graph.addNode(a)

        assertEquals(
            a,
            graph.getNode("A")
        )
    }
    @Test
    fun graph_returns_null_for_unknown_node() {

        val graph = Graph()

        assertEquals(
            null,
            graph.getNode("UNKNOWN")
        )
    }
    @Test
    fun graph_updates_existing_edge() {

        val graph = Graph()

        graph.addNode(Node("A", "Phone A"))
        graph.addNode(Node("B", "Phone B"))

        graph.addEdge(
            Edge("A", "B", 1)
        )

        graph.addEdge(
            Edge("A", "B", 5)
        )

        assertEquals(
            1,
            graph.getEdges().size
        )

        assertEquals(
            5,
            graph.edgeCost("A", "B")
        )
    }
    @Test
    fun graph_rejects_edge_to_unknown_node() {

        val graph = Graph()

        graph.addNode(
            Node("A", "Phone A")
        )

        try {

            graph.addEdge(
                Edge("A", "B")
            )

            throw AssertionError(
                "Expected addEdge to reject unknown node"
            )

        } catch (exception: IllegalArgumentException) {

            assertEquals(
                "Destination node does not exist: B",
                exception.message
            )
        }
    }
    @Test
    fun graph_rejects_self_loop() {

        val graph = Graph()

        graph.addNode(
            Node("A", "Phone A")
        )

        try {

            graph.addEdge(
                Edge("A", "A")
            )

            throw AssertionError(
                "Expected self-loop to be rejected"
            )

        } catch (exception: IllegalArgumentException) {

            assertEquals(
                "A node cannot connect to itself.",
                exception.message
            )
        }
    }
    @Test
    fun graph_rejects_non_positive_weight() {

        val graph = Graph()

        graph.addNode(Node("A", "Phone A"))
        graph.addNode(Node("B", "Phone B"))

        try {

            graph.addEdge(
                Edge(
                    from = "A",
                    to = "B",
                    weight = 0
                )
            )

            throw AssertionError(
                "Expected zero-weight edge to be rejected"
            )

        } catch (exception: IllegalArgumentException) {

            assertEquals(
                "Edge weight must be greater than zero.",
                exception.message
            )
        }
    }
        @Test
        fun `topology version changes when graph changes`() {

            val graph = Graph()

            assertEquals(
                0L,
                graph.getTopologyVersion()
            )

            graph.addNode(
                Node("A", "Phone A")
            )

            assertEquals(
                1L,
                graph.getTopologyVersion()
            )

            graph.addNode(
                Node("A", "Phone A")
            )

            assertEquals(
                1L,
                graph.getTopologyVersion()
            )

            graph.addNode(
                Node("B", "Phone B")
            )

            assertEquals(
                2L,
                graph.getTopologyVersion()
            )

            graph.addEdge(
                Edge("A", "B", 1)
            )

            assertEquals(
                3L,
                graph.getTopologyVersion()
            )

            graph.addEdge(
                Edge("A", "B", 1)
            )

            assertEquals(
                3L,
                graph.getTopologyVersion()
            )

            graph.addEdge(
                Edge("A", "B", 5)
            )

            assertEquals(
                4L,
                graph.getTopologyVersion()
            )

            graph.removeEdge(
                "A",
                "B"
            )

            assertEquals(
                5L,
                graph.getTopologyVersion()
            )
        }
    }
