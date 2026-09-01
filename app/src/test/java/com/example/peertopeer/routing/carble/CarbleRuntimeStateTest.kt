package com.example.peertopeer.routing.carble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbleRuntimeStateTest {


    @Test
    fun packet_state_store_creates_and_updates_state() {

        val store =
            CarblePacketStateStore()


        val initial =
            store.getOrCreate(
                "MSG-1"
            )


        assertEquals(
            "MSG-1",
            initial.messageId
        )

        assertEquals(
            1,
            initial.copyBudgetRemaining
        )


        store.update(

            initial.copy(

                regime =
                    CarbleRegime.MEDIUM,

                mediumStage =
                    CarbleMediumStage.M2,

                primaryNextHopId =
                    "B",

                backupNextHopId =
                    "C"
            )
        )


        val updated =
            store.get(
                "MSG-1"
            )


        assertEquals(
            CarbleRegime.MEDIUM,
            updated?.regime
        )

        assertEquals(
            CarbleMediumStage.M2,
            updated?.mediumStage
        )

        assertEquals(
            "B",
            updated?.primaryNextHopId
        )

        assertEquals(
            "C",
            updated?.backupNextHopId
        )
    }


    @Test
    fun removing_packet_state_clears_it() {

        val store =
            CarblePacketStateStore()


        store.getOrCreate(
            "MSG-1"
        )


        store.remove(
            "MSG-1"
        )


        assertNull(
            store.get(
                "MSG-1"
            )
        )
    }


    @Test
    fun high_forward_contains_no_medium_stage() {

        val decision =
            CarbleRouteDecision.Forward(

                path =
                    listOf(
                        "A",
                        "B",
                        "C"
                    ),

                currentHopConfidence =
                    0.90,

                routeConfidence =
                    0.85,

                regime =
                    CarbleRegime.HIGH,

                mediumStage =
                    null,

                reason =
                    CarbleDecisionReason
                        .HEALTHY_ROUTE
            )


        assertEquals(
            CarbleRegime.HIGH,
            decision.regime
        )
    }


    @Test
    fun m1_forward_is_supported() {

        val decision =
            CarbleRouteDecision.Forward(

                path =
                    listOf(
                        "A",
                        "B"
                    ),

                currentHopConfidence =
                    0.70,

                routeConfidence =
                    0.70,

                regime =
                    CarbleRegime.MEDIUM,

                mediumStage =
                    CarbleMediumStage.M1,

                reason =
                    CarbleDecisionReason
                        .LOCAL_MEDIUM
            )


        assertEquals(
            CarbleMediumStage.M1,
            decision.mediumStage
        )
    }


    @Test
    fun m2_has_distinct_primary_and_backup_next_hops() {

        val decision =
            CarbleRouteDecision
                .ForwardWithFailover(

                    primaryPath =
                        listOf(
                            "A",
                            "B",
                            "D"
                        ),

                    backupPath =
                        listOf(
                            "A",
                            "C",
                            "D"
                        ),

                    currentHopConfidence =
                        0.60,

                    routeConfidence =
                        0.60,

                    reason =
                        CarbleDecisionReason
                            .LOCAL_MEDIUM
                )


        assertEquals(
            "B",
            decision.primaryPath[1]
        )

        assertEquals(
            "C",
            decision.backupPath?.get(1)
        )
    }


    @Test
    fun m3_delayed_backup_has_positive_delay() {

        val decision =
            CarbleRouteDecision
                .ForwardWithDelayedBackup(

                    primaryPath =
                        listOf(
                            "A",
                            "B",
                            "D"
                        ),

                    backupPath =
                        listOf(
                            "A",
                            "C",
                            "D"
                        ),

                    backupDelay =
                        2L,

                    currentHopConfidence =
                        0.50,

                    routeConfidence =
                        0.50,

                    reason =
                        CarbleDecisionReason
                            .LOCAL_MEDIUM
                )


        assertTrue(
            decision.backupDelay > 0L
        )
    }


    @Test
    fun low_carry_contains_bounded_reevaluation() {

        val decision =
            CarbleRouteDecision.Carry(

                confidence =
                    0.30,

                reevaluationNumber =
                    1,

                reevaluationDelay =
                    5L
            )


        assertEquals(
            1,
            decision.reevaluationNumber
        )

        assertEquals(
            5L,
            decision.reevaluationDelay
        )
    }


    @Test
    fun low_probe_contains_current_route() {

        val decision =
            CarbleRouteDecision.Probe(

                path =
                    listOf(
                        "A",
                        "B"
                    ),

                confidence =
                    0.30
            )


        assertEquals(
            "B",
            decision.path[1]
        )
    }
}