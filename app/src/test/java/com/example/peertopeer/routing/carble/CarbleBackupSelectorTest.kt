package com.example.peertopeer.routing.carble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarbleBackupSelectorTest {

    private val selector =
        CarbleBackupSelector()


    // =====================================================
    // SCORE
    // =====================================================

    @Test
    fun perfect_candidate_scores_one() {

        val candidate =
            candidate(

                nextHopId =
                    "B",

                delivery =
                    1.0,

                progress =
                    1.0,

                freshness =
                    1.0,

                queue =
                    1.0,

                stability =
                    1.0
            )


        val score =
            selector.calculateScore(
                candidate
            )


        assertEquals(
            1.0,
            score,
            0.000001
        )
    }


    @Test
    fun candidate_score_uses_expected_weights() {

        val candidate =
            candidate(

                nextHopId =
                    "B",

                delivery =
                    0.8,

                progress =
                    0.6,

                freshness =
                    1.0,

                queue =
                    0.5,

                stability =
                    0.9
            )


        /*
         * .35 * .8 = .280
         * .25 * .6 = .150
         * .15 * 1  = .150
         * .15 * .5 = .075
         * .10 * .9 = .090
         *
         * total = .745
         */
        val score =
            selector.calculateScore(
                candidate
            )


        assertEquals(
            0.745,
            score,
            0.000001
        )
    }


    // =====================================================
    // PRIMARY EXCLUSION
    // =====================================================

    @Test
    fun primary_next_hop_cannot_be_selected_as_backup() {

        val primary =
            candidate(
                nextHopId =
                    "B",

                delivery =
                    1.0
            )

        val alternate =
            candidate(
                nextHopId =
                    "C",

                delivery =
                    0.7
            )


        val result =
            selector.selectBackup(

                primaryNextHopId =
                    "B",

                previousNodeId =
                    null,

                candidates =
                    listOf(
                        primary,
                        alternate
                    )
            )


        assertEquals(
            "C",
            result?.candidate?.nextHopId
        )
    }


    // =====================================================
    // PREVIOUS NODE EXCLUSION
    // =====================================================

    @Test
    fun previous_node_cannot_be_immediate_backup() {

        val previous =
            candidate(
                nextHopId =
                    "A",

                delivery =
                    1.0
            )

        val alternate =
            candidate(
                nextHopId =
                    "C",

                delivery =
                    0.6
            )


        val result =
            selector.selectBackup(

                primaryNextHopId =
                    "B",

                previousNodeId =
                    "A",

                candidates =
                    listOf(
                        previous,
                        alternate
                    )
            )


        assertEquals(
            "C",
            result?.candidate?.nextHopId
        )
    }


    // =====================================================
    // EXCLUDED NODE
    // =====================================================

    @Test
    fun excluded_node_cannot_be_selected() {

        val excluded =
            candidate(
                nextHopId =
                    "C",

                delivery =
                    1.0
            )

        val valid =
            candidate(
                nextHopId =
                    "D",

                delivery =
                    0.6
            )


        val result =
            selector.selectBackup(

                primaryNextHopId =
                    "B",

                previousNodeId =
                    null,

                excludedNodeIds =
                    setOf(
                        "C"
                    ),

                candidates =
                    listOf(
                        excluded,
                        valid
                    )
            )


        assertEquals(
            "D",
            result?.candidate?.nextHopId
        )
    }


    // =====================================================
    // BEST SCORE
    // =====================================================

    @Test
    fun highest_scoring_eligible_candidate_is_selected() {

        val weaker =
            candidate(

                nextHopId =
                    "C",

                delivery =
                    0.50,

                progress =
                    0.50
            )

        val stronger =
            candidate(

                nextHopId =
                    "D",

                delivery =
                    0.90,

                progress =
                    0.90
            )


        val result =
            selector.selectBackup(

                primaryNextHopId =
                    "B",

                previousNodeId =
                    null,

                candidates =
                    listOf(
                        weaker,
                        stronger
                    )
            )


        assertEquals(
            "D",
            result?.candidate?.nextHopId
        )
    }


    // =====================================================
    // DETERMINISTIC TIE
    // =====================================================

    @Test
    fun equal_scores_use_deterministic_next_hop_tie_break() {

        val candidateD =
            candidate(
                nextHopId =
                    "D",

                delivery =
                    0.8
            )

        val candidateC =
            candidate(
                nextHopId =
                    "C",

                delivery =
                    0.8
            )


        val result =
            selector.selectBackup(

                primaryNextHopId =
                    "B",

                previousNodeId =
                    null,

                candidates =
                    listOf(
                        candidateD,
                        candidateC
                    )
            )


        /*
         * Same score.
         *
         * Lexicographically smaller nextHopId wins so
         * experiments remain deterministic.
         */
        assertEquals(
            "C",
            result?.candidate?.nextHopId
        )
    }


    // =====================================================
    // NO BACKUP
    // =====================================================

    @Test
    fun returns_null_when_no_eligible_backup_exists() {

        val primaryOnly =
            candidate(
                nextHopId =
                    "B",

                delivery =
                    1.0
            )


        val result =
            selector.selectBackup(

                primaryNextHopId =
                    "B",

                previousNodeId =
                    null,

                candidates =
                    listOf(
                        primaryOnly
                    )
            )


        assertNull(
            result
        )
    }


    // =====================================================
    // HELPER
    // =====================================================

    private fun candidate(
        nextHopId: String,
        delivery: Double = 0.8,
        progress: Double = 0.8,
        freshness: Double = 1.0,
        queue: Double = 1.0,
        stability: Double = 1.0
    ): CarbleBackupCandidate {

        return CarbleBackupCandidate(

            nextHopId =
                nextHopId,

            path =
                listOf(
                    "CURRENT",
                    nextHopId,
                    "DESTINATION"
                ),

            deliveryProbability =
                delivery,

            progress =
                progress,

            freshness =
                freshness,

            queueAvailability =
                queue,

            contactStability =
                stability
        )
    }
}