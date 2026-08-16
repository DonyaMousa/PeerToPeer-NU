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

class B0MultiFlowSourceSweepTest {

    data class SourceSweepResult(
        val sourceCount: Int,
        val generated: Int,
        val processed: Int,
        val dropped: Int,
        val dropRate: Double,
        val maxQueueSize: Int,
        val averageQueueWait: Double,
        val maxQueueWait: Long
    )

    @Test
    fun `shared relay congestion increases as source count grows`() {

        val sourceCounts =
            listOf(
                1,
                2,
                3,
                4
            )

        val results =
            sourceCounts.map { sourceCount ->

                runScenario(
                    sourceCount = sourceCount
                )
            }

        println()
        println(
            "===== B0 MULTI-FLOW SOURCE SWEEP ====="
        )

        println(
            "Sources | Generated | Processed | Dropped | DropRate | MaxQueue | AvgWait | MaxWait"
        )

        results.forEach { result ->

            println(
                "${result.sourceCount} | " +
                        "${result.generated} | " +
                        "${result.processed} | " +
                        "${result.dropped} | " +
                        "${result.dropRate * 100.0}% | " +
                        "${result.maxQueueSize} | " +
                        "${result.averageQueueWait} | " +
                        "${result.maxQueueWait}"
            )
        }

        println(
            "======================================"
        )
        println()

        /*
         * One source alone should be safe.
         */
        assertEquals(
            0,
            results.first {
                it.sourceCount == 1
            }.dropped
        )

        /*
         * Four sources reproduce our
         * overloaded shared-relay case.
         */
        assertEquals(
            9,
            results.first {
                it.sourceCount == 4
            }.dropped
        )
    }

    private fun runScenario(
        sourceCount: Int
    ): SourceSweepResult {

        val allSourceIds =
            listOf(
                "A",
                "C",
                "E",
                "F"
            )

        val selectedSourceIds =
            allSourceIds.take(
                sourceCount
            )

        val graph =
            Graph()

        val destination =
            Node(
                "D",
                "Destination D"
            )

        val relay =
            Node(
                "B",
                "Relay B"
            )

        graph.addNode(destination)
        graph.addNode(relay)

        selectedSourceIds.forEach { id ->

            graph.addNode(
                Node(
                    id,
                    "Node $id"
                )
            )

            graph.addEdge(
                Edge(
                    from = id,
                    to = "B",
                    weight = 1
                )
            )
        }

        graph.addEdge(
            Edge(
                from = "B",
                to = "D",
                weight = 1
            )
        )

        val routingTable =
            RoutingTable(
                graph = graph,
                routingEngine =
                    DijkstraEngine()
            )

        /*
         * Confirm every source uses B.
         */
        selectedSourceIds.forEach { id ->

            val source =
                graph.getNode(id)!!

            val route =
                routingTable.getRoute(
                    source = source,
                    destination =
                        destination
                )

            assertEquals(
                "B",
                route!!
                    .nextHop!!
                    .nodeId
            )
        }

        val simulation =
            SimulationEngine()

        val relayB =
            SimulatedServiceNode(
                nodeId = "B",
                queueCapacity = 5,
                serviceTime = 5L,
                simulationEngine =
                    simulation
            ) { _, _ ->
                // Nothing else required.
            }

        val packetsPerSource = 5

        selectedSourceIds
            .forEachIndexed {
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

        val generated =
            sourceCount *
                    packetsPerSource

        val processed =
            relayB.processedPackets

        val dropped =
            relayB.droppedPackets

        val dropRate =
            dropped.toDouble() /
                    generated.toDouble()

        return SourceSweepResult(
            sourceCount =
                sourceCount,
            generated =
                generated,
            processed =
                processed,
            dropped =
                dropped,
            dropRate =
                dropRate,
            maxQueueSize =
                relayB.maxQueueSize,
            averageQueueWait =
                relayB.averageQueueWaitingTime(),
            maxQueueWait =
                relayB.maxQueueWaitingTime
        )
    }
}
