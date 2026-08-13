package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DijkstraEngineTest {

    private val engine = DijkstraEngine()

    @Test
    fun `finds direct route`() {

        val a = Node("A", "Phone A")
        val b = Node("B", "Phone B")

        val graph = Graph()

        graph.addNode(a)
        graph.addNode(b)

        graph.addEdge(
            Edge("A", "B")
        )

        val result = engine.findRoute(
            graph = graph,
            source = a,
            destination = b
        )

        assertNotNull(result)

        assertEquals(
            listOf(a, b),
            result!!.path
        )

        assertEquals(
            1,
            result.totalCost
        )

        assertEquals(
            b,
            result.nextHop
        )
    }
}
