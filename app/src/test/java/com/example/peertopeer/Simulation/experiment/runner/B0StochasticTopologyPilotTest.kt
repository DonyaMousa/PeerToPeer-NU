package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Test

class B0StochasticTopologyPilotTest {

    @Test
    fun run_seeded_topology_pilot() {

        val runner =
            B0ExperimentRunner()

        println()
        println(
            "seed,pdr,meanLatency,physicalAttempts,topologyEvents,cacheInvalidations,routeCalculations,routeChanges"
        )

        for (seed in 1L..10L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-S02-PILOT",

                    runId =
                        "B0-S02-R$seed",

                    protocol =
                        "B0",

                    protocolVersion =
                        "B0-FREEZE-CANDIDATE",

                    runIndex =
                        seed.toInt(),

                    seed =
                        seed,

                    traffic =
                        TrafficConfig(
                            packetCount = 50,
                            packetInterval = 10,
                            packetTtl = 30,
                            payloadBytes = 32,
                            sourceCount = 1
                        ),

                    link =
                        LinkConfig(
                            maxAttempts = 3,
                            retryDelay = 1,
                            modelName =
                                "seeded-topology"
                        ),

                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S02-SEEDED-TOPOLOGY",

                            scenarioName =
                                "Seeded stochastic topology variation",

                            topologyType =
                                "dual-path",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1,

                            conditionName =
                                "stochastic-topology",
                            topologyFailureProbability = 0.30,

                            topologyDecisionTimes =
                                listOf(
                                    50L,
                                    100L,
                                    150L,
                                    200L,
                                    250L,
                                    300L,
                                    350L,
                                    400L
                                ),

                            notes =
                                "Primary N2-N4 link state varies using seeded random decisions."
                        ),

                    notes =
                        "S02 stochastic topology pilot."
                )

            val result =
                runner.runSeededTopologyScenario(
                    config = config,
                )

            val s =
                result.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${result.topologyEvents.size}," +
                        "${s.cacheInvalidations}," +
                        "${s.routeCalculations}," +
                        "${s.routeChanges}"
            )
        }
    }
}
