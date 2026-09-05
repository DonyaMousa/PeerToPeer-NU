package com.example.peertopeer.Simulation.experiment.environment

import com.example.peertopeer.simulation.experiment.environment.PairedLinkOutcomeOracle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairedLinkOutcomeOracleTest {

    @Test
    fun protocol_specific_message_prefixes_share_same_physical_sample() {
        val oracle =
            PairedLinkOutcomeOracle(
                seed = 2001L,
                experimentSalt = "PF_B2"
            )

        val b0 = oracle.sampleForTesting(
            fromNodeId = "N1",
            toNodeId = "N2",
            messageId = "PFB2-B0-SEED-2001-MSG-17",
            attemptNumber = 1,
            attemptTime = 101L
        )

        val carble = oracle.sampleForTesting(
            fromNodeId = "N1",
            toNodeId = "N2",
            messageId = "PFB2-CARBLE-SEED-2001-MSG-17",
            attemptNumber = 1,
            attemptTime = 101L
        )

        assertEquals(b0, carble, 0.0)
    }

    @Test
    fun undirected_link_direction_uses_same_sample() {
        val oracle =
            PairedLinkOutcomeOracle(
                seed = 2001L,
                experimentSalt = "PF_B2"
            )

        val forward = oracle.sampleForTesting(
            "N1", "N2",
            "ANY-MSG-3",
            1,
            101L
        )

        val reverse = oracle.sampleForTesting(
            "N2", "N1",
            "OTHER-MSG-3",
            1,
            101L
        )

        assertEquals(forward, reverse, 0.0)
    }

    @Test
    fun different_attempt_numbers_use_different_samples() {
        val oracle =
            PairedLinkOutcomeOracle(
                seed = 2001L,
                experimentSalt = "PF_B2"
            )

        val first = oracle.sampleForTesting(
            "N1", "N2",
            "ANY-MSG-3",
            1,
            101L
        )

        val second = oracle.sampleForTesting(
            "N1", "N2",
            "ANY-MSG-3",
            2,
            102L
        )

        assertNotEquals(first, second, 0.0)
    }
    @Test
    fun repeated_logical_transmission_at_different_time_uses_different_sample() {
        val oracle =
            PairedLinkOutcomeOracle(
                seed = 2001L,
                experimentSalt = "PF_B2"
            )

        val first = oracle.sampleForTesting(
            "N1", "N2",
            "ANY-MSG-3",
            1,
            101L
        )

        val later = oracle.sampleForTesting(
            "N1", "N2",
            "ANY-MSG-3",
            1,
            201L
        )

        assertNotEquals(first, later, 0.0)
    }

}
