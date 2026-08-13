package com.example.peertopeer.experiment

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import com.example.peertopeer.routing.RoutingTelemetry
import org.junit.Assert
import org.junit.Test

class BaselineExperimentTest {

    @Test
    fun `b0 repeated requests and topology failure experiment`() {

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
         * PHASE 1
         *
         * Simulate 100 route requests while
         * topology remains stable.
         */
        repeat(100) {

            val route =
                routingTable.getRoute(
                    source = a,
                    destination = d
                )

            Assert.assertEquals(
                listOf(a, b, d),
                route!!.path
            )
        }

        /*
         * PHASE 2
         *
         * Simulate failure of the active B-D link.
         */
        graph.removeEdge(
            from = "B",
            to = "D"
        )

        /*
         * PHASE 3
         *
         * Simulate another 100 route requests
         * after the topology changed.
         */
        repeat(100) {

            val route =
                routingTable.getRoute(
                    source = a,
                    destination = d
                )

            Assert.assertEquals(
                listOf(a, c, d),
                route!!.path
            )
        }

        println()
        println("===== B0 BASELINE EXPERIMENT =====")
        println("Route requests: ${telemetry.routeRequests}")
        println("Cache hits: ${telemetry.cacheHits}")
        println("Cache misses: ${telemetry.cacheMisses}")
        println("Route calculations: ${telemetry.routeCalculations}")
        println("Cache invalidations: ${telemetry.cacheInvalidations}")
        println("Successful routes: ${telemetry.successfulRoutes}")
        println("Unreachable routes: ${telemetry.unreachableRoutes}")
        println("==================================")
        println()

        Assert.assertEquals(
            200,
            telemetry.routeRequests
        )

        Assert.assertEquals(
            198,
            telemetry.cacheHits
        )

        Assert.assertEquals(
            2,
            telemetry.cacheMisses
        )

        Assert.assertEquals(
            2,
            telemetry.routeCalculations
        )

        Assert.assertEquals(
            1,
            telemetry.cacheInvalidations
        )

        Assert.assertEquals(
            2,
            telemetry.successfulRoutes
        )

        Assert.assertEquals(
            0,
            telemetry.unreachableRoutes
        )
    }
}