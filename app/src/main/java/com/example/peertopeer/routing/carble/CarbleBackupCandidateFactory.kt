package com.example.peertopeer.routing.carble

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import java.util.PriorityQueue

class CarbleBackupCandidateFactory(

    private val graph:
    Graph,

    private val stateStore:
    MultiMetricStateStore

) {

    // =====================================================
    // PATH SEARCH ENTRY
    // =====================================================

    private data class PathEntry(

        val nodeId:
        String,

        val cost:
        Int
    )


    // =====================================================
    // CREATE CANDIDATES
    // =====================================================

    fun createCandidates(
        currentNodeId: String,
        destinationId: String
    ): List<CarbleBackupCandidate> {

        require(
            currentNodeId.isNotBlank()
        ) {
            "currentNodeId must not be blank."
        }

        require(
            destinationId.isNotBlank()
        ) {
            "destinationId must not be blank."
        }

        require(
            graph.containsNode(
                currentNodeId
            )
        ) {
            "Current node $currentNodeId does not exist."
        }

        require(
            graph.containsNode(
                destinationId
            )
        ) {
            "Destination node $destinationId does not exist."
        }


        /*
         * Already at destination.
         *
         * There is no forwarding candidate.
         */
        if (
            currentNodeId ==
            destinationId
        ) {

            return emptyList()
        }


        /*
         * Baseline graph distance from the current node
         * toward the destination.
         *
         * Used only to normalize candidate progress.
         */
        val currentDistance =
            shortestDistance(

                sourceId =
                    currentNodeId,

                destinationId =
                    destinationId,

                blockedNodeIds =
                    emptySet()
            )


        /*
         * If the current node itself cannot reach the
         * destination, there is no valid candidate set.
         */
        if (
            currentDistance == null
        ) {

            return emptyList()
        }


        val candidates =
            mutableListOf<CarbleBackupCandidate>()


        // =================================================
        // EVERY ACTUAL GRAPH NEIGHBOR
        // =================================================

        graph.getNeighbors(
            currentNodeId
        )
            .forEach { edge ->

                val neighborId =
                    edge.to


                /*
                 * Find a continuation from this neighbor
                 * toward the destination.
                 *
                 * IMPORTANT:
                 *
                 * currentNodeId is blocked after the first
                 * hop.
                 *
                 * This prevents backup routes such as:
                 *
                 * N1 -> N3 -> N1 -> N2 -> N4
                 *
                 * which would immediately loop back through
                 * the node that selected the backup.
                 */
                val continuation =
                    shortestPath(

                        sourceId =
                            neighborId,

                        destinationId =
                            destinationId,

                        blockedNodeIds =
                            setOf(
                                currentNodeId
                            )
                    )
                        ?: return@forEach


                /*
                 * Complete candidate path.
                 *
                 * Example:
                 *
                 * current      = N1
                 * neighbor     = N3
                 * continuation = [N3, N4]
                 *
                 * result       = [N1, N3, N4]
                 */
                val candidatePath =
                    buildList {

                        add(
                            currentNodeId
                        )

                        addAll(
                            continuation
                        )
                    }


                val linkState =
                    stateStore.get(
                        currentNodeId,
                        neighborId
                    )
                        ?: defaultState(
                            fromNodeId =
                                currentNodeId,

                            toNodeId =
                                neighborId
                        )


                // =========================================
                // D — DELIVERY PROBABILITY
                // =========================================

                val deliveryProbability =
                    linkState.successRate
                        .coerceIn(
                            0.0,
                            1.0
                        )


                // =========================================
                // F — FRESHNESS
                // =========================================

                /*
                 * Same simulation-v1 limitation as the
                 * CARBLE confidence model.
                 *
                 * Real observation age will replace this
                 * during physical BLE integration.
                 */
                val freshness =
                    1.0


                // =========================================
                // A — QUEUE AVAILABILITY
                // =========================================

                val queuePenalty =
                    if (
                        linkState.queueCapacity <= 0
                    ) {

                        1.0

                    } else {

                        (
                                linkState.queueOccupancy
                                    .toDouble() /
                                        linkState.queueCapacity
                                            .toDouble()
                                )
                            .coerceIn(
                                0.0,
                                1.0
                            )
                    }


                val queueAvailability =
                    (
                            1.0 -
                                    queuePenalty
                            )
                        .coerceIn(
                            0.0,
                            1.0
                        )


                // =========================================
                // R — CONTACT STABILITY
                // =========================================

                val instabilityPenalty =
                    if (
                        linkState.instabilityReference <= 0
                    ) {

                        1.0

                    } else {

                        (
                                linkState.recentLinkChanges
                                    .toDouble() /
                                        linkState.instabilityReference
                                            .toDouble()
                                )
                            .coerceIn(
                                0.0,
                                1.0
                            )
                    }


                val contactStability =
                    (
                            1.0 -
                                    instabilityPenalty
                            )
                        .coerceIn(
                            0.0,
                            1.0
                        )


                // =========================================
                // P — FORWARD PROGRESS
                // =========================================

                /*
                 * How much closer to the destination does
                 * this first hop move us?
                 *
                 * P =
                 *
                 * (distanceCurrent - distanceNeighbor)
                 * ---------------------------------------
                 *            distanceCurrent
                 *
                 * clipped to [0, 1].
                 */
                val neighborDistance =
                    pathCost(
                        continuation
                    )


                val progress =
                    if (
                        currentDistance <= 0
                    ) {

                        1.0

                    } else {

                        (
                                (
                                        currentDistance -
                                                neighborDistance
                                        ).toDouble() /
                                        currentDistance
                                            .toDouble()
                                )
                            .coerceIn(
                                0.0,
                                1.0
                            )
                    }


                candidates.add(

                    CarbleBackupCandidate(

                        nextHopId =
                            neighborId,

                        path =
                            candidatePath,

                        deliveryProbability =
                            deliveryProbability,

                        progress =
                            progress,

                        freshness =
                            freshness,

                        queueAvailability =
                            queueAvailability,

                        contactStability =
                            contactStability
                    )
                )
            }


        /*
         * Stable deterministic order.
         */
        return candidates
            .sortedBy {
                it.nextHopId
            }
    }


    // =====================================================
    // SHORTEST DISTANCE
    // =====================================================

    private fun shortestDistance(
        sourceId: String,
        destinationId: String,
        blockedNodeIds: Set<String>
    ): Int? {

        val path =
            shortestPath(

                sourceId =
                    sourceId,

                destinationId =
                    destinationId,

                blockedNodeIds =
                    blockedNodeIds
            )
                ?: return null


        return pathCost(
            path
        )
    }


    // =====================================================
    // SHORTEST PATH
    // =====================================================

    private fun shortestPath(
        sourceId: String,
        destinationId: String,
        blockedNodeIds: Set<String>
    ): List<String>? {

        /*
         * Source/destination themselves must remain usable.
         */
        if (
            sourceId in blockedNodeIds ||
            destinationId in blockedNodeIds
        ) {

            return null
        }


        if (
            sourceId ==
            destinationId
        ) {

            return listOf(
                sourceId
            )
        }


        val distances =
            mutableMapOf<String, Int>()

        val previous =
            mutableMapOf<String, String>()

        val visited =
            mutableSetOf<String>()


        val queue =
            PriorityQueue<PathEntry>(
                compareBy<PathEntry> {
                    it.cost
                }
                    .thenBy {
                        it.nodeId
                    }
            )


        distances[
            sourceId
        ] =
            0


        queue.add(

            PathEntry(

                nodeId =
                    sourceId,

                cost =
                    0
            )
        )


        while (
            queue.isNotEmpty()
        ) {

            val current =
                queue.poll()


            if (
                !visited.add(
                    current.nodeId
                )
            ) {

                continue
            }


            if (
                current.nodeId ==
                destinationId
            ) {

                break
            }


            graph.getNeighbors(
                current.nodeId
            )
                .forEach { edge ->

                    val neighborId =
                        edge.to


                    if (
                        neighborId in
                        blockedNodeIds
                    ) {

                        return@forEach
                    }


                    if (
                        neighborId in
                        visited
                    ) {

                        return@forEach
                    }


                    val candidateCost =
                        current.cost +
                                edge.weight


                    val knownCost =
                        distances[
                            neighborId
                        ]


                    if (
                        knownCost == null ||
                        candidateCost <
                        knownCost
                    ) {

                        distances[
                            neighborId
                        ] =
                            candidateCost


                        previous[
                            neighborId
                        ] =
                            current.nodeId


                        queue.add(

                            PathEntry(

                                nodeId =
                                    neighborId,

                                cost =
                                    candidateCost
                            )
                        )
                    }
                }
        }


        if (
            destinationId !in
            distances
        ) {

            return null
        }


        // =================================================
        // RECONSTRUCT
        // =================================================

        val reversedPath =
            mutableListOf<String>()


        var currentNodeId:
                String? =
            destinationId


        while (
            currentNodeId != null
        ) {

            reversedPath.add(
                currentNodeId
            )


            if (
                currentNodeId ==
                sourceId
            ) {

                break
            }


            currentNodeId =
                previous[
                    currentNodeId
                ]
        }


        if (
            reversedPath.lastOrNull() !=
            sourceId
        ) {

            return null
        }


        return reversedPath
            .asReversed()
    }


    // =====================================================
    // PATH COST
    // =====================================================

    private fun pathCost(
        path: List<String>
    ): Int {

        if (
            path.size <= 1
        ) {

            return 0
        }


        var total =
            0


        for (
        index in 0 until
                path.lastIndex
        ) {

            val cost =
                graph.edgeCost(

                    from =
                        path[
                            index
                        ],

                    to =
                        path[
                            index + 1
                        ]
                )
                    ?: error(
                        "Path contains missing edge " +
                                "${path[index]} -> ${path[index + 1]}."
                    )


            total +=
                cost
        }


        return total
    }


    // =====================================================
    // BOOTSTRAP STATE
    // =====================================================

    private fun defaultState(
        fromNodeId: String,
        toNodeId: String
    ): MultiMetricLinkState {

        /*
         * Same bootstrap assumptions used by MM, 2RH,
         * and CARBLE route evaluation.
         */
        return MultiMetricLinkState(

            fromNodeId =
                fromNodeId,

            toNodeId =
                toNodeId,

            successRate =
                1.0,

            observedDelay =
                1.0,

            delayReference =
                10.0,

            queueOccupancy =
                0,

            queueCapacity =
                10,

            recentLinkChanges =
                0,

            instabilityReference =
                5,

            energyPenaltyNormalized =
                0.0
        )
    }
}