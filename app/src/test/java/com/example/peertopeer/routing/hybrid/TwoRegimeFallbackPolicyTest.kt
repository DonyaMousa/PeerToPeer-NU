package com.example.peertopeer.routing.hybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoRegimeFallbackPolicyTest {

    @Test
    fun first_low_evaluation_allows_carry() {

        val policy =
            TwoRegimeFallbackPolicy(
                maxReevaluations = 3,
                reevaluationDelay = 5L
            )

        val decision =
            policy.decide(
                completedReevaluations = 0
            )

        assertEquals(
            TwoRegimeFallbackPolicy.Action.CARRY_AND_REEVALUATE,
            decision.action
        )

        assertEquals(
            1,
            decision.nextReevaluationNumber
        )
    }

    @Test
    fun fallback_remains_bounded() {

        val policy =
            TwoRegimeFallbackPolicy(
                maxReevaluations = 3
            )

        val decision =
            policy.decide(
                completedReevaluations = 2
            )

        assertEquals(
            TwoRegimeFallbackPolicy.Action.CARRY_AND_REEVALUATE,
            decision.action
        )

        assertEquals(
            3,
            decision.nextReevaluationNumber
        )
    }

    @Test
    fun exhausted_budget_drops_packet() {

        val policy =
            TwoRegimeFallbackPolicy(
                maxReevaluations = 3
            )

        val decision =
            policy.decide(
                completedReevaluations = 3
            )

        assertEquals(
            TwoRegimeFallbackPolicy.Action.DROP,
            decision.action
        )

        assertEquals(
            3,
            decision.nextReevaluationNumber
        )
    }

    @Test(
        expected =
            IllegalArgumentException::class
    )
    fun negative_completed_count_is_rejected() {

        val policy =
            TwoRegimeFallbackPolicy()

        policy.decide(
            completedReevaluations = -1
        )
    }
}