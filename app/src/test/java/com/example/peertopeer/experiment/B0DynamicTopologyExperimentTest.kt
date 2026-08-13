package com.example.peertopeer.experiment

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import com.example.peertopeer.routing.RoutingTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class B0DynamicTopologyExperimentTest {

    @Test
    fun `b0 adapts across larger dynamic topology`() {

        val graph = Graph()

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")
        val e = Node("E", "Node E")
        val f = Node("F", "Node F")

        listOf(a, b, c, d, e, f).forEach {
            graph.addNode(it)
        }

        /*
         * Initial topology:
         *
         * A --1-- B --1-- D --1-- F
         *  \      |        /
         *   2     2       2
         *    \    |      /
         *     C --1-- E
         *
         * Primary route:
         * A -> B -> D -> F
         *
         * cost = 3
         */
        graph.addEdge(Edge("A", "B", 1))
        graph.addEdge(Edge("B", "D", 1))
        graph.addEdge(Edge("D", "F", 1))

        graph.addEdge(Edge("A", "C", 2))
        graph.addEdge(Edge("C", "E", 1))
        graph.addEdge(Edge("E", "F", 2))

        graph.addEdge(Edge("B", "C", 2))
        graph.addEdge(Edge("D", "E", 2))

        val telemetry = RoutingTelemetry()

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = DijkstraEngine(),
                telemetry = telemetry
            )

        /*
         * STEP 1
         *
         * Initial best route.
         */
        val step1 =
            routingTable.getRoute(
                source = a,
                destination = f
            )

        assertNotNull(step1)

        assertEquals(
            listOf(a, b, d, f),
            step1!!.path
        )

        assertEquals(
            3,
            step1.totalCost
        )

        /*
         * STEP 2
         *
         * Break D-F.
         *
         * Expected alternative:
         * A -> C -> E -> F
         *
         * cost = 5
         */
        graph.removeEdge(
            from = "D",
            to = "F"
        )

        val step2 =
            routingTable.getRoute(
                source = a,
                destination = f
            )

        assertNotNull(step2)

        assertEquals(
            listOf(a, c, e, f),
            step2!!.path
        )

        assertEquals(
            5,
            step2.totalCost
        )

        /*
         * STEP 3
         *
         * Break E-F as well.
         *
         * F becomes unreachable.
         */
        graph.removeEdge(
            from = "E",
            to = "F"
        )

        val step3 =
            routingTable.getRoute(
                source = a,
                destination = f
            )

        assertNull(step3)

        /*
         * STEP 4
         *
         * Restore D-F but with a higher cost.
         */
        graph.addEdge(
            Edge(
                from = "D",
                to = "F",
                weight = 3
            )
        )

        val step4 =
            routingTable.getRoute(
                source = a,
                destination = f
            )

        assertNotNull(step4)

        assertEquals(
            listOf(a, b, d, f),
            step4!!.path
        )

        assertEquals(
            5,
            step4.totalCost
        )

        /*
         * STEP 5
         *
         * Restore E-F with low cost.
         *
         * Now A-C-E-F becomes cheaper.
         */
        graph.addEdge(
            Edge(
                from = "E",
                to = "F",
                weight = 1
            )
        )

        val step5 =
            routingTable.getRoute(
                source = a,
                destination = f
            )

        assertNotNull(step5)

        assertEquals(
            listOf(a, c, e, f),
            step5!!.path
        )

        assertEquals(
            4,
            step5.totalCost
        )

        println()
        println("===== B0 DYNAMIC TOPOLOGY EXPERIMENT =====")
        println("Step 1 route: ${step1.path.map { it.nodeId }}")
        println("Step 1 cost: ${step1.totalCost}")

        println("Step 2 route: ${step2.path.map { it.nodeId }}")
        println("Step 2 cost: ${step2.totalCost}")

        println("Step 3 route: unreachable")

        println("Step 4 route: ${step4.path.map { it.nodeId }}")
        println("Step 4 cost: ${step4.totalCost}")

        println("Step 5 route: ${step5.path.map { it.nodeId }}")
        println("Step 5 cost: ${step5.totalCost}")

        println()
        println("Route requests: ${telemetry.routeRequests}")
        println("Cache hits: ${telemetry.cacheHits}")
        println("Cache misses: ${telemetry.cacheMisses}")
        println("Route calculations: ${telemetry.routeCalculations}")
        println("Cache invalidations: ${telemetry.cacheInvalidations}")
        println("Successful routes: ${telemetry.successfulRoutes}")
        println("Unreachable routes: ${telemetry.unreachableRoutes}")
        println("==========================================")
        println()
    }
}
