package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import java.util.PriorityQueue

class DijkstraEngine : RoutingEngine {

    private data class QueueEntry(
        val node: Node,
        val distance: Int
    )

    override fun findRoute(
        graph: Graph,
        source: Node,
        destination: Node
    ): RouteResult? {

        if (!graph.containsNode(source)) {
            return null
        }

        if (!graph.containsNode(destination)) {
            return null
        }

        if (source == destination) {
            return RouteResult(
                path = listOf(source),
                totalCost = 0,
                nextHop = null
            )
        }

        val distances = mutableMapOf<String, Int>()
        val previous = mutableMapOf<String, String?>()

        for (node in graph.getNodes()) {
            distances[node.nodeId] = Int.MAX_VALUE
            previous[node.nodeId] = null
        }

        distances[source.nodeId] = 0

        val queue = PriorityQueue<QueueEntry>(
            compareBy<QueueEntry> { it.distance }
                .thenBy { it.node.nodeId }
        )

        queue.add(
            QueueEntry(
                node = source,
                distance = 0
            )
        )

        while (queue.isNotEmpty()) {

            val current =
                queue.poll() ?: continue

            val currentId =
                current.node.nodeId

            val knownDistance =
                distances[currentId]
                    ?: continue

            /*
             * Ignore an outdated queue entry.
             */
            if (current.distance != knownDistance) {
                continue
            }

            if (currentId == destination.nodeId) {
                break
            }

            val neighbors =
                graph.getNeighbors(currentId)

            for (edge in neighbors) {

                val neighbor =
                    graph.getNode(edge.to)
                        ?: continue

                /*
                 * Protect against integer overflow.
                 */
                if (current.distance >
                    Int.MAX_VALUE - edge.weight
                ) {
                    continue
                }

                val newDistance =
                    current.distance + edge.weight

                val neighborId =
                    neighbor.nodeId

                val oldDistance =
                    distances[neighborId]
                        ?: Int.MAX_VALUE

                if (newDistance < oldDistance) {

                    distances[neighborId] =
                        newDistance

                    previous[neighborId] =
                        currentId

                    queue.add(
                        QueueEntry(
                            node = neighbor,
                            distance = newDistance
                        )
                    )
                }

                /*
                 * Equal-cost paths need deterministic handling.
                 */
                else if (
                    newDistance == oldDistance
                ) {

                    val existingPrevious =
                        previous[neighborId]

                    if (
                        existingPrevious == null ||
                        currentId < existingPrevious
                    ) {

                        previous[neighborId] =
                            currentId

                        queue.add(
                            QueueEntry(
                                node = neighbor,
                                distance = newDistance
                            )
                        )
                    }
                }
            }
        }

        val totalCost =
            distances[destination.nodeId]
                ?: return null

        if (totalCost == Int.MAX_VALUE) {
            return null
        }

        val pathIds =
            mutableListOf<String>()

        var currentId: String? =
            destination.nodeId

        while (currentId != null) {

            pathIds.add(currentId)

            currentId =
                previous[currentId]
        }

        pathIds.reverse()

        if (pathIds.firstOrNull() != source.nodeId) {
            return null
        }

        val path =
            pathIds.mapNotNull { graph.getNode(it) }

        if (path.size != pathIds.size) {
            return null
        }

        val nextHop =
            path.getOrNull(1)

        return RouteResult(
            path = path,
            totalCost = totalCost,
            nextHop = nextHop
        )
    }
}