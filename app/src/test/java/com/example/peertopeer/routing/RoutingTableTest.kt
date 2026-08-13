package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingTableTest {

    /*
     * Test-only wrapper.
     *
     * It still uses the real DijkstraEngine,
     * but counts how many times routing
     * computation is requested.
     */
    private class CountingRoutingEngine(
        private val delegate: RoutingEngine =
            DijkstraEngine()
    ) : RoutingEngine {

        var callCount: Int = 0
            private set

        override fun findRoute(
            graph: Graph,
            source: Node,
            destination: Node
        ): RouteResult? {

            callCount++

            return delegate.findRoute(
                graph = graph,
                source = source,
                destination = destination
            )
        }
    }

    @Test
    fun `cached route avoids repeated dijkstra execution`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")

        val graph = Graph()

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)

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
                to = "C",
                weight = 1
            )
        )

        val countingEngine =
            CountingRoutingEngine()

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = countingEngine
            )

        /*
         * First request:
         *
         * cache miss -> Dijkstra must run.
         */
        val firstRoute =
            routingTable.getRoute(
                source = a,
                destination = c
            )

        assertNotNull(firstRoute)

        assertEquals(
            1,
            countingEngine.callCount
        )

        assertEquals(
            listOf(a, b, c),
            firstRoute!!.path
        )

        /*
         * Second identical request:
         *
         * cache hit -> Dijkstra must NOT run.
         */
        val secondRoute =
            routingTable.getRoute(
                source = a,
                destination = c
            )

        assertNotNull(secondRoute)

        /*
         * Most important assertion.
         *
         * Still 1, not 2.
         */
        assertEquals(
            1,
            countingEngine.callCount
        )

        assertEquals(
            firstRoute,
            secondRoute
        )

        assertEquals(
            1,
            routingTable.size()
        )
    }
    @Test
    fun `topology change invalidates cached route and recalculates`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")

        val graph = Graph()

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)
        graph.addNode(d)

        graph.addEdge(Edge("A", "B", 1))
        graph.addEdge(Edge("B", "D", 1))

        graph.addEdge(Edge("A", "C", 2))
        graph.addEdge(Edge("C", "D", 2))

        val countingEngine =
            CountingRoutingEngine()

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = countingEngine
            )

        val firstRoute =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNotNull(firstRoute)

        assertEquals(
            listOf(a, b, d),
            firstRoute!!.path
        )

        assertEquals(
            2,
            firstRoute.totalCost
        )

        assertEquals(
            1,
            countingEngine.callCount
        )

        /*
         * Same request again.
         * Must come from cache.
         */
        routingTable.getRoute(
            source = a,
            destination = d
        )

        assertEquals(
            1,
            countingEngine.callCount
        )

        /*
         * Simulate link failure.
         */
        graph.removeEdge(
            from = "B",
            to = "D"
        )

        /*
         * The old cached route is now stale.
         * RoutingTable should invalidate it
         * and run Dijkstra again.
         */
        val repairedRoute =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNotNull(repairedRoute)

        assertEquals(
            listOf(a, c, d),
            repairedRoute!!.path
        )

        assertEquals(
            4,
            repairedRoute.totalCost
        )

        assertEquals(
            c,
            repairedRoute.nextHop
        )

        assertEquals(
            2,
            countingEngine.callCount
        )

        assertEquals(
            1,
            routingTable.size()
        )
    }
    @Test
    fun `routing table adapts across sequential topology changes`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")

        val graph = Graph()

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)
        graph.addNode(d)

        /*
         * Initial topology:
         *
         * A --1-- B --1-- D
         *  \
         *   2
         *    \
         *     C --2-- D
         */
        graph.addEdge(Edge("A", "B", 1))
        graph.addEdge(Edge("B", "D", 1))

        graph.addEdge(Edge("A", "C", 2))
        graph.addEdge(Edge("C", "D", 2))

        val countingEngine =
            CountingRoutingEngine()

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = countingEngine
            )

        /*
         * --------------------------------
         * STEP 1
         * --------------------------------
         *
         * Best route:
         * A -> B -> D
         */
        val step1 =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNotNull(step1)

        assertEquals(
            listOf(a, b, d),
            step1!!.path
        )

        assertEquals(
            2,
            step1.totalCost
        )

        assertEquals(
            1,
            countingEngine.callCount
        )

        /*
         * --------------------------------
         * STEP 2
         * --------------------------------
         *
         * B-D fails.
         *
         * New route should be:
         * A -> C -> D
         */
        graph.removeEdge(
            from = "B",
            to = "D"
        )

        val step2 =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNotNull(step2)

        assertEquals(
            listOf(a, c, d),
            step2!!.path
        )

        assertEquals(
            4,
            step2.totalCost
        )

        assertEquals(
            2,
            countingEngine.callCount
        )

        /*
         * --------------------------------
         * STEP 3
         * --------------------------------
         *
         * C-D also fails.
         *
         * Destination D is now unreachable.
         */
        graph.removeEdge(
            from = "C",
            to = "D"
        )

        val step3 =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNull(step3)

        assertEquals(
            3,
            countingEngine.callCount
        )

        /*
         * --------------------------------
         * STEP 4
         * --------------------------------
         *
         * Restore B-D.
         *
         * Route should become available again:
         * A -> B -> D
         */
        graph.addEdge(
            Edge(
                from = "B",
                to = "D",
                weight = 1
            )
        )

        val step4 =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertNotNull(step4)

        assertEquals(
            listOf(a, b, d),
            step4!!.path
        )

        assertEquals(
            2,
            step4.totalCost
        )

        assertEquals(
            4,
            countingEngine.callCount
        )
    }
    @Test
    fun `routing telemetry records cache and topology behavior correctly`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")

        val graph = Graph()

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)
        graph.addNode(d)

        graph.addEdge(Edge("A", "B", 1))
        graph.addEdge(Edge("B", "D", 1))

        graph.addEdge(Edge("A", "C", 2))
        graph.addEdge(Edge("C", "D", 2))

        val telemetry = RoutingTelemetry()

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = DijkstraEngine(),
                telemetry = telemetry
            )

        /*
         * Request 1:
         * cache miss
         * successful route
         */
        routingTable.getRoute(
            source = a,
            destination = d
        )

        /*
         * Request 2:
         * same route
         * cache hit
         */
        routingTable.getRoute(
            source = a,
            destination = d
        )

        /*
         * Change topology.
         */
        graph.removeEdge(
            from = "B",
            to = "D"
        )

        /*
         * Request 3:
         * topology invalidation
         * cache miss
         * recalculation
         */
        routingTable.getRoute(
            source = a,
            destination = d
        )

        /*
         * Break remaining route.
         */
        graph.removeEdge(
            from = "C",
            to = "D"
        )

        /*
         * Request 4:
         * invalidation
         * cache miss
         * unreachable
         */
        routingTable.getRoute(
            source = a,
            destination = d
        )

        assertEquals(
            4,
            telemetry.routeRequests
        )

        assertEquals(
            1,
            telemetry.cacheHits
        )

        assertEquals(
            3,
            telemetry.cacheMisses
        )

        assertEquals(
            3,
            telemetry.routeCalculations
        )

        assertEquals(
            2,
            telemetry.cacheInvalidations
        )

        assertEquals(
            2,
            telemetry.successfulRoutes
        )

        assertEquals(
            1,
            telemetry.unreachableRoutes
        )
    }
}