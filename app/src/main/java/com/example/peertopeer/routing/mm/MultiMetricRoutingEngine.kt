package com.example.peertopeer.routing.mm

import java.util.PriorityQueue

class MultiMetricRoutingEngine(

    private val costCalculator:
    MultiMetricCostCalculator =
        MultiMetricCostCalculator()

) {

    data class Route(

        val path: List<String>,
        val totalCost: Double

    ) {

        val nextHop: String?
            get() =
                if (path.size >= 2) {
                    path[1]
                } else {
                    null
                }
    }

    private data class QueueEntry(

        val nodeId: String,
        val distance: Double

    )

    /*
     * -----------------------------------------------------
     * neighborProvider
     * -----------------------------------------------------
     *
     * Gives the currently available neighbors of a node.
     *
     * We intentionally keep the MM engine independent from
     * Graph for now.
     *
     * Later MMRouteProvider will adapt our existing Graph
     * into this interface.
     *
     * -----------------------------------------------------
     * linkStateProvider
     * -----------------------------------------------------
     *
     * Supplies current metrics for each available edge.
     */
    fun findPath(
        sourceId: String,
        destinationId: String,

        neighborProvider:
            (String) -> Collection<String>,

        linkStateProvider:
            (
            fromNodeId: String,
            toNodeId: String
        ) -> MultiMetricLinkState?

    ): Route? {

        require(sourceId.isNotBlank())
        require(destinationId.isNotBlank())

        if (
            sourceId ==
            destinationId
        ) {

            return Route(
                path =
                    listOf(
                        sourceId
                    ),

                totalCost =
                    0.0
            )
        }

        val distances =
            mutableMapOf<
                    String,
                    Double
                    >()

        val previous =
            mutableMapOf<
                    String,
                    String
                    >()

        /*
         * Deterministic ordering:
         *
         * 1. smaller total cost
         * 2. nodeId alphabetically if cost ties
         */
        val queue =
            PriorityQueue<QueueEntry>(
                compareBy<QueueEntry> {
                    it.distance
                }.thenBy {
                    it.nodeId
                }
            )

        distances[sourceId] =
            0.0

        queue.add(
            QueueEntry(
                nodeId =
                    sourceId,

                distance =
                    0.0
            )
        )

        while (
            queue.isNotEmpty()
        ) {

            val current =
                queue.poll()

            val knownDistance =
                distances[
                    current.nodeId
                ]
                    ?: continue

            /*
             * Ignore stale priority-queue entries.
             */
            if (
                current.distance >
                knownDistance
            ) {
                continue
            }

            if (
                current.nodeId ==
                destinationId
            ) {
                break
            }

            /*
             * Sorting gives deterministic exploration when
             * graph order itself is not guaranteed.
             */
            val neighbors =
                neighborProvider(
                    current.nodeId
                )
                    .sorted()

            for (
            neighbor in neighbors
            ) {

                val state =
                    linkStateProvider(
                        current.nodeId,
                        neighbor
                    )
                        ?: continue

                val edgeCost =
                    costCalculator
                        .calculate(
                            state
                        )
                        .totalCost

                val candidateDistance =
                    knownDistance +
                            edgeCost

                val existingDistance =
                    distances[
                        neighbor
                    ]

                val shouldUpdate =
                    existingDistance == null ||
                            candidateDistance <
                            existingDistance

                if (
                    shouldUpdate
                ) {

                    distances[
                        neighbor
                    ] =
                        candidateDistance

                    previous[
                        neighbor
                    ] =
                        current.nodeId

                    queue.add(
                        QueueEntry(
                            nodeId =
                                neighbor,

                            distance =
                                candidateDistance
                        )
                    )
                }
            }
        }

        val destinationCost =
            distances[
                destinationId
            ]
                ?: return null

        val reversedPath =
            mutableListOf<String>()

        var currentNode:
                String? =
            destinationId

        while (
            currentNode != null
        ) {

            reversedPath.add(
                currentNode
            )

            if (
                currentNode ==
                sourceId
            ) {
                break
            }

            currentNode =
                previous[
                    currentNode
                ]
        }

        if (
            reversedPath.lastOrNull() !=
            sourceId
        ) {

            return null
        }

        val path =
            reversedPath
                .asReversed()

        return Route(
            path =
                path,

            totalCost =
                destinationCost
        )
    }
}
