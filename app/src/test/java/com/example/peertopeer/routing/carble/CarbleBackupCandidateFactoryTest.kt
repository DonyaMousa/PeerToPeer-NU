package com.example.peertopeer.routing.carble

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbleBackupCandidateFactoryTest {


    // =====================================================
    // DUAL PATH
    // =====================================================

    @Test
    fun dual_path_generates_two_forward_candidates() {

        val graph =
            createDualPathGraph()

        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    MultiMetricStateStore()
            )


        val candidates =
            factory.createCandidates(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4"
            )


        val nextHops =
            candidates
                .map {
                    it.nextHopId
                }


        assertEquals(
            listOf(
                "N2",
                "N3"
            ),
            nextHops
        )


        assertEquals(
            listOf(
                "N1",
                "N2",
                "N4"
            ),
            candidates[0].path
        )


        assertEquals(
            listOf(
                "N1",
                "N3",
                "N4"
            ),
            candidates[1].path
        )
    }


    // =====================================================
    // NO IMMEDIATE LOOP
    // =====================================================

    @Test
    fun candidate_does_not_loop_back_through_current_node() {

        val graph =
            createDualPathGraph()

        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    MultiMetricStateStore()
            )


        val candidates =
            factory.createCandidates(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4"
            )


        /*
         * N0 is a physical neighbor of N1.
         *
         * But after moving:
         *
         * N1 -> N0
         *
         * the only path back toward N4 would require
         * returning through N1.
         *
         * That is intentionally rejected.
         */
        assertFalse(

            candidates.any {
                it.nextHopId ==
                        "N0"
            }
        )
    }


    // =====================================================
    // DIRECT DESTINATION
    // =====================================================

    @Test
    fun destination_neighbor_is_valid_candidate() {

        val graph =
            Graph()


        addNode(
            graph,
            "A"
        )

        addNode(
            graph,
            "B"
        )


        graph.addEdge(
            "A",
            "B",
            1
        )


        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    MultiMetricStateStore()
            )


        val candidates =
            factory.createCandidates(

                currentNodeId =
                    "A",

                destinationId =
                    "B"
            )


        assertEquals(
            1,
            candidates.size
        )


        assertEquals(
            "B",
            candidates.first()
                .nextHopId
        )


        assertEquals(
            listOf(
                "A",
                "B"
            ),
            candidates.first()
                .path
        )


        assertEquals(
            1.0,
            candidates.first()
                .progress,
            0.000001
        )
    }


    // =====================================================
    // OBSERVED STATE
    // =====================================================

    @Test
    fun candidate_uses_mm_observation_state() {

        val graph =
            createDualPathGraph()


        val store =
            MultiMetricStateStore()


        store.update(

            MultiMetricLinkState(

                fromNodeId =
                    "N1",

                toNodeId =
                    "N3",

                successRate =
                    0.60,

                observedDelay =
                    2.0,

                delayReference =
                    10.0,

                queueOccupancy =
                    4,

                queueCapacity =
                    10,

                recentLinkChanges =
                    2,

                instabilityReference =
                    5,

                energyPenaltyNormalized =
                    0.0
            )
        )


        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    store
            )


        val candidate =
            factory.createCandidates(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4"
            )
                .first {
                    it.nextHopId ==
                            "N3"
                }


        assertEquals(
            0.60,
            candidate.deliveryProbability,
            0.000001
        )


        /*
         * queue occupancy = 4 / 10
         *
         * availability = .6
         */
        assertEquals(
            0.60,
            candidate.queueAvailability,
            0.000001
        )


        /*
         * changes = 2 / 5
         *
         * stability = .6
         */
        assertEquals(
            0.60,
            candidate.contactStability,
            0.000001
        )


        assertEquals(
            1.0,
            candidate.freshness,
            0.000001
        )
    }


    // =====================================================
    // PROGRESS
    // =====================================================

    @Test
    fun progress_reflects_remaining_graph_distance() {

        val graph =
            createDualPathGraph()


        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    MultiMetricStateStore()
            )


        val candidate =
            factory.createCandidates(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4"
            )
                .first {
                    it.nextHopId ==
                            "N2"
                }


        /*
         * N1 -> N4 shortest cost = 2
         *
         * N2 -> N4 remaining cost = 1
         *
         * progress =
         *
         * (2 - 1) / 2
         *
         * = .5
         */
        assertEquals(
            0.5,
            candidate.progress,
            0.000001
        )
    }


    // =====================================================
    // BROKEN CONTINUATION
    // =====================================================

    @Test
    fun neighbor_without_destination_continuation_is_rejected() {

        val graph =
            createDualPathGraph()


        /*
         * Break N3's forward continuation.
         */
        graph.removeEdge(
            "N3",
            "N4"
        )


        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    MultiMetricStateStore()
            )


        val candidates =
            factory.createCandidates(

                currentNodeId =
                    "N1",

                destinationId =
                    "N4"
            )


        assertTrue(

            candidates.any {
                it.nextHopId ==
                        "N2"
            }
        )


        assertFalse(

            candidates.any {
                it.nextHopId ==
                        "N3"
            }
        )
    }


    // =====================================================
    // DESTINATION
    // =====================================================

    @Test
    fun destination_has_no_backup_candidates() {

        val graph =
            createDualPathGraph()


        val factory =
            CarbleBackupCandidateFactory(

                graph =
                    graph,

                stateStore =
                    MultiMetricStateStore()
            )


        val candidates =
            factory.createCandidates(

                currentNodeId =
                    "N4",

                destinationId =
                    "N4"
            )


        assertTrue(
            candidates.isEmpty()
        )
    }


    // =====================================================
    // HELPERS
    // =====================================================

    private fun createDualPathGraph():
            Graph {

        val graph =
            Graph()


        repeat(
            5
        ) { index ->

            addNode(
                graph,
                "N$index"
            )
        }


        graph.addEdge(
            "N0",
            "N1",
            1
        )

        graph.addEdge(
            "N1",
            "N2",
            1
        )

        graph.addEdge(
            "N2",
            "N4",
            1
        )

        graph.addEdge(
            "N1",
            "N3",
            1
        )

        graph.addEdge(
            "N3",
            "N4",
            1
        )


        return graph
    }


    private fun addNode(
        graph: Graph,
        nodeId: String
    ) {

        graph.addNode(

            Node(

                nodeId =
                    nodeId,

                displayName =
                    nodeId
            )
        )
    }
}