package com.example.peertopeer.routing.mm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiMetricRoutingEngineTest {

    private val engine =
        MultiMetricRoutingEngine()

    @Test
    fun healthy_short_path_is_selected() {

        /*
         *       B
         *      / \
         *     A   D
         *
         * Alternative:
         *
         * A -> C -> E -> D
         */

        val neighbors =
            mapOf(
                "A" to
                        listOf(
                            "B",
                            "C"
                        ),

                "B" to
                        listOf(
                            "D"
                        ),

                "C" to
                        listOf(
                            "E"
                        ),

                "E" to
                        listOf(
                            "D"
                        ),

                "D" to
                        emptyList()
            )

        val states =
            mutableMapOf<
                    Pair<String, String>,
                    MultiMetricLinkState
                    >()

        fun healthy(
            from: String,
            to: String
        ) =
            MultiMetricLinkState(
                fromNodeId = from,
                toNodeId = to,

                successRate = 1.0,

                observedDelay = 1.0,
                delayReference = 10.0,

                queueOccupancy = 0,
                queueCapacity = 10,

                recentLinkChanges = 0,
                instabilityReference = 5,

                energyPenaltyNormalized =
                    0.0
            )

        states[
            "A" to "B"
        ] =
            healthy(
                "A",
                "B"
            )

        states[
            "B" to "D"
        ] =
            healthy(
                "B",
                "D"
            )

        states[
            "A" to "C"
        ] =
            healthy(
                "A",
                "C"
            )

        states[
            "C" to "E"
        ] =
            healthy(
                "C",
                "E"
            )

        states[
            "E" to "D"
        ] =
            healthy(
                "E",
                "D"
            )

        val result =
            engine.findPath(
                sourceId = "A",
                destinationId = "D",

                neighborProvider = {
                        nodeId ->

                    neighbors[
                        nodeId
                    ]
                        ?: emptyList()
                },

                linkStateProvider = {
                        from,
                        to ->

                    states[
                        from to to
                    ]
                }
            )

        assertNotNull(
            result
        )

        assertEquals(
            listOf(
                "A",
                "B",
                "D"
            ),
            result!!.path
        )
    }

    @Test
    fun unreliable_short_path_can_lose_to_longer_healthy_path() {

        val neighbors =
            mapOf(
                "A" to
                        listOf(
                            "B",
                            "C"
                        ),

                "B" to
                        listOf(
                            "D"
                        ),

                "C" to
                        listOf(
                            "E"
                        ),

                "E" to
                        listOf(
                            "D"
                        ),

                "D" to
                        emptyList()
            )

        val states =
            mutableMapOf<
                    Pair<String, String>,
                    MultiMetricLinkState
                    >()

        fun state(
            from: String,
            to: String,
            successRate: Double
        ) =
            MultiMetricLinkState(
                fromNodeId = from,
                toNodeId = to,

                successRate =
                    successRate,

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

        /*
         * Short path:
         *
         * A -> B -> D
         *
         * Both links deliberately poor.
         */
        states[
            "A" to "B"
        ] =
            state(
                "A",
                "B",
                successRate = 0.20
            )

        states[
            "B" to "D"
        ] =
            state(
                "B",
                "D",
                successRate = 0.20
            )

        /*
         * Longer path:
         *
         * A -> C -> E -> D
         *
         * Healthy links.
         */
        states[
            "A" to "C"
        ] =
            state(
                "A",
                "C",
                successRate = 1.0
            )

        states[
            "C" to "E"
        ] =
            state(
                "C",
                "E",
                successRate = 1.0
            )

        states[
            "E" to "D"
        ] =
            state(
                "E",
                "D",
                successRate = 1.0
            )

        val result =
            engine.findPath(
                sourceId = "A",
                destinationId = "D",

                neighborProvider = {
                        nodeId ->

                    neighbors[
                        nodeId
                    ]
                        ?: emptyList()
                },

                linkStateProvider = {
                        from,
                        to ->

                    states[
                        from to to
                    ]
                }
            )

        assertNotNull(
            result
        )

        assertEquals(
            listOf(
                "A",
                "C",
                "E",
                "D"
            ),
            result!!.path
        )

        assertTrue(
            result.totalCost >
                    0.0
        )
    }

    @Test
    fun no_route_returns_null() {

        val neighbors =
            mapOf(
                "A" to
                        listOf(
                            "B"
                        ),

                "B" to
                        emptyList<String>(),

                "D" to
                        emptyList()
            )

        val result =
            engine.findPath(
                sourceId = "A",
                destinationId = "D",

                neighborProvider = {
                        nodeId ->

                    neighbors[
                        nodeId
                    ]
                        ?: emptyList()
                },

                linkStateProvider = {
                        from,
                        to ->

                    MultiMetricLinkState(
                        fromNodeId = from,
                        toNodeId = to,

                        successRate = 1.0,

                        observedDelay = 1.0,
                        delayReference = 10.0,

                        queueOccupancy = 0,
                        queueCapacity = 10,

                        recentLinkChanges = 0,
                        instabilityReference = 5
                    )
                }
            )

        assertEquals(
            null,
            result
        )
    }
}
