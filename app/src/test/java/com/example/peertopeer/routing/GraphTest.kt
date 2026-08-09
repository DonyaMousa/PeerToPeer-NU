package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Edge
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
}