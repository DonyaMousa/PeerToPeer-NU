package com.example.peertopeer.network.forwarding

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealProtocolRouterRouteTest {
    @Test
    fun readyDirectEdgeIsPreferredAndRemovingItAllowsRelayRoute() {
        val graph = Graph()
        listOf("AAAAAAAA", "BBBBBBBB", "CCCCCCCC").forEach { graph.addNode(Node(it, it)) }
        graph.addEdge("AAAAAAAA", "BBBBBBBB")
        graph.addEdge("BBBBBBBB", "CCCCCCCC")
        graph.addEdge("AAAAAAAA", "CCCCCCCC")

        assertEquals(
            listOf("AAAAAAAA", "CCCCCCCC"),
            RealProtocolRouter.preferredDirectPath(graph, "AAAAAAAA", "CCCCCCCC")
        )

        graph.removeEdge("AAAAAAAA", "CCCCCCCC")
        assertNull(RealProtocolRouter.preferredDirectPath(graph, "AAAAAAAA", "CCCCCCCC"))
    }
}
