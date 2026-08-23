package com.example.peertopeer.simulation

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class B0DynamicRouteProviderTest {

    @Test
    fun route_changes_when_topology_changes() {

        val graph = Graph()

        // -------------------------------------------------
        // Nodes
        // -------------------------------------------------

        graph.addNode(
            Node(
                nodeId = "A",
                displayName = "A"
            )
        )

        graph.addNode(
            Node(
                nodeId = "B",
                displayName = "B"
            )
        )

        graph.addNode(
            Node(
                nodeId = "C",
                displayName = "C"
            )
        )

        graph.addNode(
            Node(
                nodeId = "E",
                displayName = "E"
            )
        )

        graph.addNode(
            Node(
                nodeId = "D",
                displayName = "D"
            )
        )

        // -------------------------------------------------
        // Initial topology
        //
        //      B
        //     / \
        //    A   D
        //     \
        //      C - E - D
        //
        // Primary:
        // A -> B -> D
        //
        // Alternate:
        // A -> C -> E -> D
        // -------------------------------------------------

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
            from = "A",
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

        val routeProvider =
            B0DynamicRouteProvider(
                graph = graph,
                routingEngine = DijkstraEngine()
            )

        // -------------------------------------------------
        // STATE 1 — Normal
        // -------------------------------------------------

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

        // -------------------------------------------------
        // STATE 2 — Primary link fails
        //
        // B-D disappears.
        // B0 should use:
        //
        // A -> C -> E -> D
        // -------------------------------------------------

        graph.removeEdge(
            from = "B",
            to = "D"
        )

        val alternateRoute =
            routeProvider.findPath(
                currentNodeId = "A",
                destinationId = "D"
            )

        assertEquals(
            listOf("A", "C", "E", "D"),
            alternateRoute
        )

        println(
            "After B-D failure: $alternateRoute"
        )

        // -------------------------------------------------
        // STATE 3 — Partition
        //
        // Remove E-D too.
        //
        // No route from A to D should remain.
        // -------------------------------------------------

        graph.removeEdge(
            from = "E",
            to = "D"
        )

        val partitionedRoute =
            routeProvider.findPath(
                currentNodeId = "A",
                destinationId = "D"
            )

        assertNull(
            partitionedRoute
        )

        println(
            "After partition: $partitionedRoute"
        )

        // -------------------------------------------------
        // STATE 4 — Recovery
        //
        // B-D comes back.
        // -------------------------------------------------

        graph.addEdge(
            from = "B",
            to = "D",
            weight = 1
        )

        val recoveredRoute =
            routeProvider.findPath(
                currentNodeId = "A",
                destinationId = "D"
            )

        assertEquals(
            listOf("A", "B", "D"),
            recoveredRoute
        )

        println(
            "After recovery: $recoveredRoute"
        )

        println(
            "===== B0 DYNAMIC ROUTE PROVIDER ====="
        )

        println(
            "Initial: $initialRoute"
        )

        println(
            "Alternate: $alternateRoute"
        )

        println(
            "Partitioned: $partitionedRoute"
        )

        println(
            "Recovered: $recoveredRoute"
        )

        println(
            "====================================="
        )
    }
}