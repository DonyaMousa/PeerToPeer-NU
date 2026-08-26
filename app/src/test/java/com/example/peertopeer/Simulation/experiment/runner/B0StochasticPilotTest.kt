package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Test

class B0StochasticPilotTest {

    @Test
    fun run_seeded_retry_pilot() {

        val runner =
            B0ExperimentRunner()

        println()
        println(
            "seed,pdr,meanLatency,physicalAttempts,retransmissions"
        )

        for (seed in 1L..10L) {

            val config =
                ExperimentConfig(
                    experimentSetId =
                        "B0-S01-PILOT",

                    runId =
                        "B0-S01-R$seed",

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
                            packetCount = 100,
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
                                "seeded-bernoulli-link-p080",
                            successProbability = 0.80
                        ),

                    scenario =
                        ScenarioConfig(
                            scenarioId =
                                "B0-S01-SEEDED-RETRY",

                            scenarioName =
                                "Seeded retry pilot",

                            topologyType =
                                "line",

                            nodeCount =
                                5,

                            queueCapacity =
                                20,

                            serviceTime =
                                1,

                            conditionName =
                                "stochastic-retry",
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
                                "Success probability 0.80."
                        ),

                    notes =
                        "Pilot replication."
                )

            val result =
                runner.runSeededRetryScenario(
                    config = config,
                )

            val s =
                result.summary

            println(
                "$seed," +
                        "${s.packetDeliveryRatio}," +
                        "${s.meanLatency}," +
                        "${s.physicalAttempts}," +
                        "${s.retransmissions}"
            )
        }
    }
}
