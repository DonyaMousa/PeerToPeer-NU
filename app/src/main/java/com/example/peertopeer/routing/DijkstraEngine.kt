package com.example.peertopeer.routing

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import java.util.PriorityQueue

class DijkstraEngine : RoutingEngine {

    private data class QueueEntry(
        val nodeId: String,
        val distance: Int
    )

    override fun findRoute(
        graph: Graph,
        source: Node,
        destination: Node
    ): RouteResult? {

        val sourceId = source.nodeId
        val destinationId = destination.nodeId

        // -------------------------------------------------
        // VALIDATION
        // -------------------------------------------------

        if (!graph.containsNode(sourceId)) {
            return null
        }

        if (!graph.containsNode(destinationId)) {
            return null
        }

        if (sourceId == destinationId) {
            return RouteResult(
                path = listOf(source),
                totalCost = 0,
                nextHop = null
            )
        }

        // -------------------------------------------------
        // DIJKSTRA STATE
        // -------------------------------------------------

        val distances =
            mutableMapOf<String, Int>()

        val previous =
            mutableMapOf<String, String?>()

        for (node in graph.getNodes()) {

            distances[node.nodeId] =
                Int.MAX_VALUE

            previous[node.nodeId] =
                null
        }

        distances[sourceId] = 0

        val queue =
            PriorityQueue<QueueEntry>(
                compareBy<QueueEntry> { it.distance }
                    .thenBy { it.nodeId }
            )

        queue.add(
            QueueEntry(
                nodeId = sourceId,
                distance = 0
            )
        )

        // -------------------------------------------------
        // MAIN ALGORITHM
        // -------------------------------------------------

        while (queue.isNotEmpty()) {

            val current =
                queue.poll()

            val currentId =
                current.nodeId

            val knownDistance =
                distances[currentId]
                    ?: continue

            /*
             * Ignore outdated priority-queue entries.
             */
            if (current.distance != knownDistance) {
                continue
            }

            /*
             * Destination reached with its shortest cost.
             */
            if (currentId == destinationId) {
                break
            }

            val neighbors =
                graph.getNeighbors(currentId)

            for (edge in neighbors) {

                val neighborId =
                    edge.to

                val weight =
                    edge.weight

                /*
                 * Graph should already guarantee
                 * positive weights.
                 */
                if (weight <= 0) {
                    continue
                }

                /*
                 * Protect against Int overflow.
                 */
                if (
                    knownDistance >
                    Int.MAX_VALUE - weight
                ) {
                    continue
                }

                val newDistance =
                    knownDistance + weight

                val oldDistance =
                    distances[neighborId]
                        ?: continue

                // -----------------------------------------
                // BETTER PATH
                // -----------------------------------------

                if (newDistance < oldDistance) {

                    distances[neighborId] =
                        newDistance

                    previous[neighborId] =
                        currentId

                    queue.add(
                        QueueEntry(
                            nodeId = neighborId,
                            distance = newDistance
                        )
                    )
                }

                // -----------------------------------------
                // EQUAL-COST PATH
                // deterministic tie handling
                // -----------------------------------------

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
                    }
                }
            }
        }

        // -------------------------------------------------
        // CHECK REACHABILITY
        // -------------------------------------------------

        val totalCost =
            distances[destinationId]
                ?: return null

        if (totalCost == Int.MAX_VALUE) {
            return null
        }

        // -------------------------------------------------
        // RECONSTRUCT PATH
        // -------------------------------------------------

        val pathIds =
            mutableListOf<String>()

        val reconstructionGuard =
            mutableSetOf<String>()

        var currentId: String? =
            destinationId

        while (currentId != null) {

            /*
             * Defensive protection against
             * an invalid predecessor cycle.
             */
            if (!reconstructionGuard.add(currentId)) {
                return null
            }

            pathIds.add(currentId)

            if (currentId == sourceId) {
                break
            }

            currentId =
                previous[currentId]
        }

        pathIds.reverse()

        if (
            pathIds.firstOrNull()
            != sourceId
        ) {
            return null
        }

        // -------------------------------------------------
        // CONVERT NODE IDS BACK TO Node OBJECTS
        // -------------------------------------------------

        val path =
            mutableListOf<Node>()

        for (nodeId in pathIds) {

            val node =
                graph.getNode(nodeId)
                    ?: return null

            path.add(node)
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