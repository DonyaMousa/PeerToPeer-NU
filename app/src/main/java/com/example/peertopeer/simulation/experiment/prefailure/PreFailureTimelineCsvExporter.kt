package com.example.peertopeer.simulation.experiment.export

import com.example.peertopeer.simulation.experiment.prefailure.PreFailureResult
import java.io.File

class PreFailureTimelineCsvExporter(
    private val outputDirectory: File
) {

    init {
        outputDirectory.mkdirs()
    }

    fun export(
        result: PreFailureResult
    ) {

        val file =
            File(
                outputDirectory,
                "prefailure_timeline.csv"
            )

        file.writeText(
            listOf(
                "runId",
                "seed",
                "phaseIndex",
                "configuredSuccessProbability",
                "eventTime",
                "messageId",
                "currentNodeId",
                "destinationId",
                "currentHopConfidence",
                "routeConfidence",
                "previousRegime",
                "regime",
                "mediumStage",
                "reason",
                "bottleneckFromNodeId",
                "bottleneckToNodeId",
                "primaryNextHopId",
                "backupNextHopId",
                "action"
            )
                .joinToString(",") {
                    csv(it)
                } + "\n"
        )

        result.regimeEvents
            .forEach { event ->

                val phase =
                    result.profile
                        .phaseAt(
                            event.eventTime
                        )

                file.appendText(
                    listOf(
                        result.runId,
                        result.seed,
                        phase.phaseIndex,
                        phase.successProbability,
                        event.eventTime,
                        event.messageId,
                        event.currentNodeId,
                        event.destinationId,
                        event.currentHopConfidence,
                        event.routeConfidence,
                        event.previousRegime,
                        event.regime,
                        event.mediumStage,
                        event.reason,
                        event.bottleneckFromNodeId,
                        event.bottleneckToNodeId,
                        event.primaryNextHopId,
                        event.backupNextHopId,
                        event.action
                    )
                        .joinToString(",") {
                            csv(it)
                        } + "\n"
                )
            }
    }


    private fun csv(
        value: Any?
    ): String {

        if (
            value == null
        ) {
            return ""
        }

        return "\"" +
                value
                    .toString()
                    .replace(
                        "\"",
                        "\"\""
                    ) +
                "\""
    }
}
