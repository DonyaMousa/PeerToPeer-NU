package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BaselineTimingExperimentTest {

    private data class TestGraph(
        val graph: Graph,
        val source: Node,
        val destination: Node
    )

    @Test
    fun `b0 dijkstra timing across increasing line graph sizes`() {

        val graphSizes =
            listOf(
                10,
                25,
                50,
                100
            )

        val warmupRuns = 20
        val measuredRuns = 200

        val engine =
            DijkstraEngine()

        println()
        println("===== B0 DIJKSTRA TIMING EXPERIMENT =====")
        println("Topology: line")
        println("Warmup runs per size: $warmupRuns")
        println("Measured runs per size: $measuredRuns")
        println()

        for (nodeCount in graphSizes) {

            val testGraph =
                createLineGraph(nodeCount)

            /*
             * Warm up the JVM before recording measurements.
             */
            repeat(warmupRuns) {

                val route =
                    engine.findRoute(
                        graph = testGraph.graph,
                        source = testGraph.source,
                        destination = testGraph.destination
                    )

                assertNotNull(route)
            }

            val measurementsNs =
                mutableListOf<Long>()

            repeat(measuredRuns) {

                val start =
                    System.nanoTime()

                val route =
                    engine.findRoute(
                        graph = testGraph.graph,
                        source = testGraph.source,
                        destination = testGraph.destination
                    )

                val end =
                    System.nanoTime()

                assertNotNull(route)

                /*
                 * A line graph containing N nodes should produce
                 * a source-to-destination path containing N nodes.
                 */
                assertEquals(
                    nodeCount,
                    route!!.path.size
                )

                measurementsNs.add(
                    end - start
                )
            }

            val sorted =
                measurementsNs.sorted()

            val medianNs =
                sorted[sorted.size / 2]

            val averageNs =
                measurementsNs.average()

            val minNs =
                sorted.first()

            val maxNs =
                sorted.last()

            /*
             * Convert nanoseconds to microseconds
             * for easier reading.
             */
            val medianUs =
                medianNs / 1_000.0

            val averageUs =
                averageNs / 1_000.0

            val minUs =
                minNs / 1_000.0

            val maxUs =
                maxNs / 1_000.0

            println("Nodes: $nodeCount")
            println("Median: $medianUs us")
            println("Average: $averageUs us")
            println("Min: $minUs us")
            println("Max: $maxUs us")
            println("------------------------------")
        }

        println("==========================================")
        println()
    }

    private fun createLineGraph(
        nodeCount: Int
    ): TestGraph {

        require(nodeCount >= 2)

        val graph =
            Graph()

        val nodes =
            (0 until nodeCount).map { index ->

                Node(
                    nodeId =
                        "N${index.toString().padStart(3, '0')}",
                    displayName =
                        "Node $index"
                )
            }

        nodes.forEach { node ->
            graph.addNode(node)
        }

        for (index in 0 until nodes.lastIndex) {

            graph.addEdge(
                Edge(
                    from = nodes[index].nodeId,
                    to = nodes[index + 1].nodeId,
                    weight = 1
                )
            )
        }

        return TestGraph(
            graph = graph,
            source = nodes.first(),
            destination = nodes.last()
        )
    }
}
