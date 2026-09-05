package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.runner.RoutingScalabilityBenchmarkRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoutingScalabilityBenchmarkTest {

    @Test
    fun final_scalability_benchmark_all_protocols() {

        val runner =
            RoutingScalabilityBenchmarkRunner(
                warmupDecisions =
                    100,
                measuredDecisions =
                    500,
                queueCapacity =
                    20,
                hysteresisFraction =
                    0.05
            )

        val results =
            runner.runAll(
                seeds =
                    (1L..30L)
                        .toList(),
                nodeCounts =
                    listOf(
                        10,
                        25,
                        50,
                        100,
                        200
                    )
            )

        /*
         * 4 protocols
         * x 2 topology densities
         * x 5 node counts
         * x 30 independent graph seeds
         *
         * = 1200 benchmark rows.
         */
        assertEquals(
            4 *
                    2 *
                    5 *
                    30,
            results.size
        )

        results.forEach { result ->

            assertEquals(
                100,
                result.warmupDecisions
            )

            assertEquals(
                500,
                result.measuredDecisions
            )

            /*
             * Every graph is connected by construction and
             * every benchmark pair contains distinct nodes.
             *
             * Healthy routing/controller state should
             * therefore yield a usable path for all 500
             * measured decisions.
             */
            assertEquals(
                "Unexpected no-path/controller fallback in " +
                        "${result.protocol} " +
                        "${result.topology} " +
                        "N=${result.nodeCount} " +
                        "seed=${result.seed}",
                500,
                result.pathFoundCount
            )

            assertTrue(
                result.undirectedEdgeCount >=
                        result.nodeCount - 1
            )

            assertTrue(
                result.meanPathHops >
                        0.0
            )

            assertTrue(
                result.meanLatencyNs >
                        0.0
            )

            assertTrue(
                result.medianLatencyNs >
                        0.0
            )

            assertTrue(
                result.p95LatencyNs >
                        0.0
            )

            assertTrue(
                result.sampleSdLatencyNs >=
                        0.0
            )

            assertTrue(
                result.minLatencyNs >
                        0L
            )

            assertTrue(
                result.maxLatencyNs >=
                        result.minLatencyNs
            )
        }

        // =================================================
        // PAIRED DESIGN AUDIT
        // =================================================

        RoutingScalabilityBenchmarkRunner
            .Topology
            .entries
            .forEach { topology ->

                listOf(
                    10,
                    25,
                    50,
                    100,
                    200
                )
                    .forEach { nodeCount ->

                        (1L..30L)
                            .forEach { seed ->

                                val block =
                                    results.filter {
                                        it.topology ==
                                                topology &&
                                                it.nodeCount ==
                                                nodeCount &&
                                                it.seed ==
                                                seed
                                    }

                                assertEquals(
                                    4,
                                    block.size
                                )

                                /*
                                 * All four protocols must
                                 * use exactly the same graph
                                 * fixture for this paired
                                 * topology/nodeCount/seed block.
                                 */
                                assertEquals(
                                    1,
                                    block
                                        .map {
                                            it.undirectedEdgeCount
                                        }
                                        .distinct()
                                        .size
                                )
                            }
                    }
            }

        // =================================================
        // EXPORT
        // =================================================

        val outputDirectory =
            File(
                "build/research/" +
                        "CARBLE-ROUTING-SCALABILITY"
            )

        val csv =
            runner.exportCsv(
                results =
                    results,
                outputDirectory =
                    outputDirectory
            )

        assertTrue(
            csv.exists()
        )

        assertTrue(
            csv.length() >
                    0L
        )

        // =================================================
        // COMPACT CONSOLE SUMMARY
        //
        // Each displayed latency is the mean, across 30
        // independent graph seeds, of the within-seed
        // median over 500 measured decisions.
        // =================================================

        println()
        println(
            "=============================================================================================="
        )

        println(
            "CARBLE COMPUTATIONAL SCALABILITY BENCHMARK v1"
        )

        println(
            "protocol,topology,nodeCount,meanEdges," +
                    "meanOfSeedMedianUs,meanOfSeedP95Us," +
                    "meanPathHops"
        )

        RoutingScalabilityBenchmarkRunner
            .Topology
            .entries
            .forEach { topology ->

                listOf(
                    10,
                    25,
                    50,
                    100,
                    200
                )
                    .forEach { nodeCount ->

                        RoutingScalabilityBenchmarkRunner
                            .Protocol
                            .entries
                            .forEach { protocol ->

                                val group =
                                    results.filter {
                                        it.protocol ==
                                                protocol &&
                                                it.topology ==
                                                topology &&
                                                it.nodeCount ==
                                                nodeCount
                                    }

                                assertEquals(
                                    30,
                                    group.size
                                )

                                println(
                                    "$protocol," +
                                            "$topology," +
                                            "$nodeCount," +
                                            "${group.map {
                                                it.undirectedEdgeCount
                                                    .toDouble()
                                            }.average()}," +
                                            "${group.map {
                                                it.medianLatencyUs
                                            }.average()}," +
                                            "${group.map {
                                                it.p95LatencyUs
                                            }.average()}," +
                                            "${group.map {
                                                it.meanPathHops
                                            }.average()}"
                                )
                            }
                    }
            }

        println()
        println(
            "Rows: ${results.size}"
        )

        println(
            "CSV: ${csv.absolutePath}"
        )

        println(
            "=============================================================================================="
        )
    }
}
