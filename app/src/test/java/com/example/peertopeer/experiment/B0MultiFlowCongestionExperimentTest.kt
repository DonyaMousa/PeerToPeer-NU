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
import org.junit.Assert.assertTrue
import org.junit.Test

class B0MultiFlowCongestionExperimentTest {

    @Test
    fun `multiple b0 flows overload shared shortest path relay`() {

        val a = Node("A", "Node A")
        val c = Node("C", "Node C")
        val e = Node("E", "Node E")
        val f = Node("F", "Node F")

        val b = Node("B", "Relay B")
        val d = Node("D", "Destination D")

        val graph = Graph()

        listOf(
            a,
            b,
            c,
            d,
            e,
            f
        ).forEach {
            graph.addNode(it)
        }

        /*
         * Every source reaches D through B.
         */

        graph.addEdge(
            Edge("A", "B", 1)
        )

        graph.addEdge(
            Edge("C", "B", 1)
        )

        graph.addEdge(
            Edge("E", "B", 1)
        )

        graph.addEdge(
            Edge("F", "B", 1)
        )

        graph.addEdge(
            Edge("B", "D", 1)
        )

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine =
                    DijkstraEngine()
            )

        /*
         * Confirm B0 routes every source
         * through relay B.
         */

        listOf(
            a,
            c,
            e,
            f
        ).forEach { source ->

            val route =
                routingTable.getRoute(
                    source = source,
                    destination = d
                )

            assertEquals(
                "B",
                route!!.nextHop!!.nodeId
            )
        }

        val simulation =
            SimulationEngine()

        /*
         * Relay B:
         *
         * service time = 5
         * queue capacity = 5
         */
        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 5,
                serviceTime = 5L,
                simulationEngine =
                    simulation
            ) { _, _ ->
                // No extra action required.
            }

        /*
         * Each source sends 5 packets.
         *
         * Individually:
         *
         * interval = 6
         * service time = 5
         *
         * so one source alone would be safe.
         *
         * But four sources together produce
         * much more traffic at B.
         */

        val sources =
            listOf(
                "A",
                "C",
                "E",
                "F"
            )

        val packetsPerSource = 5

        sources.forEachIndexed {
                sourceIndex,
                sourceId ->

            val generator =
                TrafficGenerator(
                    simulationEngine =
                        simulation
                )

            generator.schedulePackets(
                count =
                    packetsPerSource,
                startTime =
                    sourceIndex.toLong(),
                interval = 6L,
                sourceId =
                    sourceId,
                destinationId = "D",
                ttl = 10
            ) { packet ->

                /*
                 * B0's selected next hop
                 * is B for all sources.
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
        }

        simulation.run()

        val generatedPackets =
            sources.size *
                    packetsPerSource

        val processed =
            relayB.processedPackets

        val dropped =
            relayB.droppedPackets

        val dropRate =
            dropped.toDouble() /
                    generatedPackets.toDouble()

        println()
        println(
            "===== B0 MULTI-FLOW CONGESTION ====="
        )

        println(
            "Sources: $sources"
        )

        println(
            "Packets per source: $packetsPerSource"
        )

        println(
            "Generated packets: $generatedPackets"
        )

        println(
            "Processed at relay B: $processed"
        )

        println(
            "Dropped at relay B: $dropped"
        )

        println(
            "Drop rate: ${dropRate * 100.0}%"
        )

        println(
            "Max queue size: ${relayB.maxQueueSize}"
        )

        println(
            "Average queue wait: " +
                    relayB.averageQueueWaitingTime()
        )

        println(
            "Max queue wait: " +
                    relayB.maxQueueWaitingTime
        )

        println(
            "Route requests: " +
                    routingTable.telemetry.routeRequests
        )

        println(
            "Cache hits: " +
                    routingTable.telemetry.cacheHits
        )

        println(
            "Cache misses: " +
                    routingTable.telemetry.cacheMisses
        )

        println(
            "Dijkstra calculations: " +
                    routingTable.telemetry.routeCalculations
        )

        println(
            "===================================="
        )

        println()

        assertEquals(
            generatedPackets,
            processed + dropped
        )

        assertTrue(
            dropped > 0
        )

        assertEquals(
            5,
            relayB.maxQueueSize
        )
    }
}
