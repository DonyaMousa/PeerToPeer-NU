package com.example.peertopeer.experiment

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import com.example.peertopeer.routing.RoutingTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredBaselineExperimentTest {

    @Test
    fun `b0 experiment produces structured config and result`() {

        val config =
            ExperimentConfig(
                experimentId = "B0-E001",
                baselineId = "B0",
                topologyType = "line-with-backup-path",
                nodeCount = 4,
                routeRequestCount = 200,
                topologyChangeCount = 1,
                randomSeed = null,
                notes = "100 requests before failure and 100 after failure"
            )

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")

        val graph =
            Graph()

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)
        graph.addNode(d)

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

        graph.addEdge(
            Edge(
                from = "A",
                to = "C",
                weight = 2
            )
        )

        graph.addEdge(
            Edge(
                from = "C",
                to = "D",
                weight = 2
            )
        )

        val telemetry =
            RoutingTelemetry()

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine = DijkstraEngine(),
                telemetry = telemetry
            )

        /*
         * Phase 1:
         * stable topology
         */
        repeat(100) {

            val route =
                routingTable.getRoute(
                    source = a,
                    destination = d
                )

            assertEquals(
                listOf(a, b, d),
                route!!.path
            )
        }

        /*
         * Inject topology failure.
         */
        graph.removeEdge(
            from = "B",
            to = "D"
        )

        /*
         * Phase 2:
         * new stable topology
         */
        repeat(100) {

            val route =
                routingTable.getRoute(
                    source = a,
                    destination = d
                )

            assertEquals(
                listOf(a, c, d),
                route!!.path
            )
        }

        val result =
            ExperimentResult(
                experimentId = config.experimentId,
                routeRequests = telemetry.routeRequests,
                cacheHits = telemetry.cacheHits,
                cacheMisses = telemetry.cacheMisses,
                routeCalculations = telemetry.routeCalculations,
                cacheInvalidations = telemetry.cacheInvalidations,
                successfulRoutes = telemetry.successfulRoutes,
                unreachableRoutes = telemetry.unreachableRoutes
            )

        println()
        println("===== STRUCTURED EXPERIMENT =====")

        println("CONFIG")
        println("Experiment ID: ${config.experimentId}")
        println("Baseline: ${config.baselineId}")
        println("Topology: ${config.topologyType}")
        println("Nodes: ${config.nodeCount}")
        println("Requested routes: ${config.routeRequestCount}")
        println("Topology changes: ${config.topologyChangeCount}")
        println("Random seed: ${config.randomSeed}")
        println("Notes: ${config.notes}")

        println()

        println("RESULT")
        println("Route requests: ${result.routeRequests}")
        println("Cache hits: ${result.cacheHits}")
        println("Cache misses: ${result.cacheMisses}")
        println("Route calculations: ${result.routeCalculations}")
        println("Cache invalidations: ${result.cacheInvalidations}")
        println("Successful routes: ${result.successfulRoutes}")
        println("Unreachable routes: ${result.unreachableRoutes}")

        println("===============================")
        println()

        assertEquals(
            config.routeRequestCount,
            result.routeRequests
        )

        assertEquals(
            config.topologyChangeCount,
            result.cacheInvalidations
        )

        assertEquals(
            198,
            result.cacheHits
        )

        assertEquals(
            2,
            result.routeCalculations
        )
    }
}
