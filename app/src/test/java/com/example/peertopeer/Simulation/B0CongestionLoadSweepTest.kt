package com.example.peertopeer.experiment

import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.network.PacketState
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.RoutingTable
import com.example.peertopeer.simulation.SimulatedServiceNode
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.TrafficGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

class B0CongestionLoadSweepTest {

    data class LoadResult(
        val interval: Long,
        val generated: Int,
        val processed: Int,
        val dropped: Int,
        val maxQueueSize: Int,
        val averageQueueWait: Double,
        val maxQueueWait: Long
    )

    @Test
    fun `b0 congestion increases as traffic load increases`() {

        val intervals =
            listOf(
                6L,
                5L,
                3L,
                1L
            )

        val results =
            intervals.map { interval ->

                runScenario(
                    interval = interval
                )
            }

        println()
        println("===== B0 CONGESTION LOAD SWEEP =====")
        println(
            "Interval | Generated | Processed | Dropped | MaxQueue | AvgWait | MaxWait"
        )

        results.forEach { result ->

            println(
                "${result.interval} | " +
                        "${result.generated} | " +
                        "${result.processed} | " +
                        "${result.dropped} | " +
                        "${result.maxQueueSize} | " +
                        "${result.averageQueueWait} | " +
                        "${result.maxQueueWait}"
            )
        }

        println("====================================")
        println()

        /*
         * Low-load scenario should not lose packets.
         */
        assertEquals(
            0,
            results.first {
                it.interval == 6L
            }.dropped
        )

        /*
         * Very-high-load scenario should
         * experience queue overflow.
         */
        val veryHighLoad =
            results.first {
                it.interval == 1L
            }

        assertEquals(
            5,
            veryHighLoad.dropped
        )

        assertEquals(
            3,
            veryHighLoad.maxQueueSize
        )
    }

    private fun runScenario(
        interval: Long
    ): LoadResult {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")
        val e = Node("E", "Node E")

        val graph =
            Graph()

        listOf(
            a,
            b,
            c,
            d,
            e
        ).forEach {
            graph.addNode(it)
        }

        /*
         * B0 preferred route:
         *
         * A -> B -> D
         *
         * 2 hops
         */
        graph.addEdge(
            Edge("A", "B", 1)
        )

        graph.addEdge(
            Edge("B", "D", 1)
        )

        /*
         * Longer available route:
         *
         * A -> C -> E -> D
         *
         * 3 hops
         */
        graph.addEdge(
            Edge("A", "C", 1)
        )

        graph.addEdge(
            Edge("C", "E", 1)
        )

        graph.addEdge(
            Edge("E", "D", 1)
        )

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine =
                    DijkstraEngine()
            )

        /*
         * Confirm that B0 continues
         * selecting B.
         */
        val route =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        assertEquals(
            listOf(
                "A",
                "B",
                "D"
            ),
            route!!.path.map {
                it.nodeId
            }
        )

        val simulation =
            SimulationEngine()

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 3,
                serviceTime = 5L,
                simulationEngine =
                    simulation
            ) { _, _ ->

                /*
                 * No special action required.
                 *
                 * We use relay telemetry
                 * after the simulation.
                 */
            }

        val generator =
            TrafficGenerator(
                simulationEngine =
                    simulation
            )

        val generatedPackets = 10

        generator.schedulePackets(
            count = generatedPackets,
            startTime = 0L,
            interval = interval,
            sourceId = "A",
            destinationId = "D",
            ttl = 10
        ) { packet ->

            /*
             * B0 selected B as next hop.
             */
            val packetAtB =
                PacketState(
                    packet = packet,
                    currentNodeId = "B",
                    remainingTtl =
                        packet.ttl - 1,
                    hopCount = 1
                )

            relayB.receive(
                packetAtB
            )
        }

        simulation.run()

        return LoadResult(
            interval = interval,
            generated =
                generatedPackets,
            processed =
                relayB.processedPackets,
            dropped =
                relayB.droppedPackets,
            maxQueueSize =
                relayB.maxQueueSize,
            averageQueueWait =
                relayB.averageQueueWaitingTime(),
            maxQueueWait =
                relayB.maxQueueWaitingTime
        )
    }
}
