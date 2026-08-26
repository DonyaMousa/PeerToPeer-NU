package com.example.peertopeer.Simulation.experiment.runner

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.config.LinkConfig
import com.example.peertopeer.simulation.experiment.config.ScenarioConfig
import com.example.peertopeer.simulation.experiment.config.TrafficConfig
import com.example.peertopeer.simulation.experiment.export.ExperimentCsvExporter
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B0CsvExporterTest {

    @Test
    fun healthy_run_exports_all_research_csv_files() {

        val outputDirectory =
            File(
                "build/test-results/b0-csv-export"
            )

        /*
         * Clean old output so this test proves
         * that the current run created the files.
         */
        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }

        val config =
            ExperimentConfig(
                experimentSetId =
                    "B0-CSV-EXPORT-TEST",

                runId =
                    "B0-CSV-HEALTHY-R001",

                protocol =
                    "B0",

                protocolVersion =
                    "B0-FREEZE-CANDIDATE",

                runIndex =
                    1,

                seed =
                    1L,

                traffic =
                    TrafficConfig(
                        packetCount = 20,
                        packetInterval = 10,
                        packetTtl = 20,
                        payloadBytes = 32,
                        sourceCount = 1
                    ),

                link =
                    LinkConfig(
                        maxAttempts = 3,
                        retryDelay = 1,
                        modelName =
                            "deterministic-healthy"
                    ),

                scenario =
                    ScenarioConfig(
                        scenarioId =
                            "B0-HEALTHY-LINE-05",

                        scenarioName =
                            "Healthy five-node line",

                        topologyType =
                            "line",

                        nodeCount =
                            5,

                        queueCapacity =
                            10,

                        serviceTime =
                            1,

                        conditionName =
                            "healthy",

                        notes =
                            "CSV exporter validation."
                    ),

                notes =
                    "Verifies research CSV output."
            )

        val runner =
            B0ExperimentRunner()

        val output =
            runner.runHealthyLine(
                config
            )

        val exporter =
            ExperimentCsvExporter(
                outputDirectory
            )

        exporter.exportRun(
            config = config,
            output = output
        )

        val expectedFiles =
            listOf(
                "runs.csv",
                "packet_results.csv",
                "transmission_events.csv",
                "routing_events.csv",
                "topology_events.csv",
                "queue_events.csv",
                "resource_samples.csv",
                "run_summary.csv"
            )

        expectedFiles.forEach { fileName ->

            val file =
                File(
                    outputDirectory,
                    fileName
                )

            println(
                "$fileName -> " +
                        "exists=${file.exists()}, " +
                        "size=${if (file.exists()) file.length() else 0}"
            )

            assertTrue(
                "$fileName was not created.",
                file.exists()
            )

            assertTrue(
                "$fileName is empty.",
                file.length() > 0L
            )
        }
    }
}
