package com.example.peertopeer.simulation.experiment.export

import com.example.peertopeer.simulation.experiment.runner.TwoRegimeExperimentRunner

object TwoRegimeAdaptationCsvExporter {

    private val header =
        listOf(
            "runId",
            "scenarioId",
            "seed",
            "highDecisions",
            "lowDecisions",
            "carryDecisions",
            "probeDecisions",
            "probeSuccesses",
            "probeFailures",
            "lowToHighRecoveries",
            "fallbackDrops"
        )

    fun export(
        rows: List<Row>
    ): String {

        return buildString {

            appendLine(
                header.joinToString(",")
            )

            rows.forEach { row ->

                appendLine(
                    listOf(
                        csv(row.runId),
                        csv(row.scenarioId),
                        row.seed.toString(),
                        row.output.adaptation.highDecisions.toString(),
                        row.output.adaptation.lowDecisions.toString(),
                        row.output.adaptation.carryDecisions.toString(),
                        row.output.adaptation.probeDecisions.toString(),
                        row.output.adaptation.probeSuccesses.toString(),
                        row.output.adaptation.probeFailures.toString(),
                        row.output.adaptation.lowToHighRecoveries.toString(),
                        row.output.adaptation.fallbackDrops.toString()
                    ).joinToString(",")
                )
            }
        }
    }

    data class Row(
        val runId: String,
        val scenarioId: String,
        val seed: Long,
        val output: TwoRegimeExperimentRunner.RunOutput
    )

    private fun csv(
        value: String
    ): String {

        val escaped =
            value.replace(
                "\"",
                "\"\""
            )

        return "\"$escaped\""
    }
}
