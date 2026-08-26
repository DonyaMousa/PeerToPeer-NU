package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class B0SeedReproducibilityTest {

    @Test
    fun same_seed_reproduces_identical_stochastic_run() {

        val runner =
            B0ExperimentRunner()

        val configA =
            config(
                runId = "B0-SEED-A",
                seed = 17L
            )

        val configB =
            config(
                runId = "B0-SEED-B",
                seed = 17L
            )

        val a =
            runner.runSeededRetryScenario(
                config = configA,
            )

        val b =
            runner.runSeededRetryScenario(
                config = configB,
            )

        assertEquals(
            a.summary.deliveredPackets,
            b.summary.deliveredPackets
        )

        assertEquals(
            a.summary.droppedPackets,
            b.summary.droppedPackets
        )

        assertEquals(
            a.summary.physicalAttempts,
            b.summary.physicalAttempts
        )

        assertEquals(
            a.summary.failedPhysicalAttempts,
            b.summary.failedPhysicalAttempts
        )

        assertEquals(
            a.summary.retransmissions,
            b.summary.retransmissions
        )

        assertEquals(
            a.summary.meanLatency,
            b.summary.meanLatency
        )

        /*
         * Compare success/failure sequence while ignoring
         * runId differences.
         */
        val sequenceA =
            a.transmissions.map {
                Triple(
                    it.attemptNumber,
                    it.success,
                    it.logicalHopIndex
                )
            }

        val sequenceB =
            b.transmissions.map {
                Triple(
                    it.attemptNumber,
                    it.success,
                    it.logicalHopIndex
                )
            }

        assertEquals(
            sequenceA,
            sequenceB
        )
    }

    @Test
    fun different_seed_can_produce_different_run() {

        val runner =
            B0ExperimentRunner()

        val a =
            runner.runSeededRetryScenario(
                config(
                    "B0-SEED-17",
                    17L
                ),
            )

        val b =
            runner.runSeededRetryScenario(
                config(
                    "B0-SEED-18",
                    18L
                ),
            )

        val sequenceA =
            a.transmissions.map {
                it.success
            }

        val sequenceB =
            b.transmissions.map {
                it.success
            }

        assertNotEquals(
            sequenceA,
            sequenceB
        )
    }

    private fun config(
        runId: String,
        seed: Long
    ): ExperimentConfig {

        return ExperimentConfig(
            experimentSetId =
                "B0-DAY06-STOCHASTIC",

            runId =
                runId,

            protocol =
                "B0",

            protocolVersion =
                "B0-FREEZE-CANDIDATE",

            runIndex =
                1,

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
                        "seeded-bernoulli-link",
                    successProbability = 0.80

                ),

            scenario =
                ScenarioConfig(
                    scenarioId =
                        "B0-S01-SEEDED-RETRY",

                    scenarioName =
                        "Seeded stochastic retry line",

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

                    notes =
                        "Bernoulli physical-attempt success model."
                ),

            notes =
                "Seed reproducibility validation."
        )
    }
}
