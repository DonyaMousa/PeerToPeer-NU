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
import org.junit.Assert
import org.junit.Test

class B0CongestionExperimentTest {

    @Test
    fun `b0 shortest path overloads shared relay under high traffic`() {

        val a = Node("A", "Node A")
        val b = Node("B", "Node B")
        val c = Node("C", "Node C")
        val d = Node("D", "Node D")
        val e = Node("E", "Node E")

        val graph = Graph()

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
         * Shortest route:
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
         * Longer alternative:
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
                routingEngine = DijkstraEngine()
            )

        /*
         * Confirm B0 selects A -> B -> D.
         */
        val route =
            routingTable.getRoute(
                source = a,
                destination = d
            )

        Assert.assertEquals(
            listOf("A", "B", "D"),
            route!!.path.map { it.nodeId }
        )

        val simulation =
            SimulationEngine()

        /*
         * Relay B:
         *
         * queue capacity = 3
         * service time = 5
         *
         * B is intentionally slower
         * than incoming traffic.
         */
        val processedPackets =
            mutableListOf<String>()

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 3,
                serviceTime = 5L,
                simulationEngine = simulation
            ) { packetState, _ ->

                processedPackets.add(
                    packetState.packet.messageId
                )
            }

        /*
         * Generate packets every 1 time unit.
         *
         * Arrival rate is much faster than
         * B's service rate.
         */
        val generator =
            TrafficGenerator(
                simulationEngine = simulation
            )

        generator.schedulePackets(
            count = 10,
            startTime = 0L,
            interval = 1L,
            sourceId = "A",
            destinationId = "D",
            ttl = 10
        ) { packet ->

            /*
             * B0 says next hop from A is B.
             *
             * For this congestion experiment,
             * we feed the packet into B's
             * relay queue.
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

        val generatedPackets = 10

        val processed =
            relayB.processedPackets

        val dropped =
            relayB.droppedPackets

        val deliveryRatio =
            processed.toDouble() /
                    generatedPackets.toDouble()

        println()
        println(
            "===== B0 CONGESTION EXPERIMENT ====="
        )

        println(
            "Selected B0 route: " +
                    route.path.map { it.nodeId }
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
            "Relay acceptance ratio: " +
                    "${deliveryRatio * 100.0}%"
        )

        println(
            "Processed packet IDs: $processedPackets"
        )

        println(
            "Final B queue size: " +
                    relayB.queuedPackets()
        )

        println(
            "Max queue size: ${relayB.maxQueueSize}"
        )

        println(
            "Average queue wait: ${relayB.averageQueueWaitingTime()}"
        )

        println(
            "Max queue wait: ${relayB.maxQueueWaitingTime}"
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

        Assert.assertEquals(
            generatedPackets,
            processed + dropped
        )

        Assert.assertTrue(
            dropped > 0
        )

        Assert.assertEquals(
            0,
            relayB.queuedPackets()
        )

        Assert.assertEquals(
            3,
            relayB.maxQueueSize
        )

        Assert.assertTrue(
            relayB.averageQueueWaitingTime() > 0.0
        )

        Assert.assertTrue(
            relayB.maxQueueWaitingTime > 0L
        )
    }
}