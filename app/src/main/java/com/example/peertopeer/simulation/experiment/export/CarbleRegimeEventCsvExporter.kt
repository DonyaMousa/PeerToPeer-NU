package com.example.peertopeer.simulation.experiment.export

import com.example.peertopeer.routing.carble.CarbleRegimeEventRecord
import java.io.File

class CarbleRegimeEventCsvExporter(

    private val outputDirectory: File

) {

    init {
        outputDirectory.mkdirs()
    }

    fun append(
        records: List<CarbleRegimeEventRecord>
    ) {

        if (
            records.isEmpty()
        ) {
            return
        }

        val file =
            File(
                outputDirectory,
                "carble_regime_events.csv"
            )

        writeHeaderIfNeeded(
            file
        )

        records.forEach { r ->

            appendRow(
                file,
                listOf(
                    r.runId,
                    r.messageId,
                    r.eventTime,
                    r.currentNodeId,
                    r.destinationId,
                    r.currentHopConfidence,
                    r.routeConfidence,
                    r.previousRegime,
                    r.regime,
                    r.mediumStage,
                    r.reason,
                    r.bottleneckFromNodeId,
                    r.bottleneckToNodeId,
                    r.primaryNextHopId,
                    r.backupNextHopId,
                    r.action
                )
            )
        }
    }


    private fun writeHeaderIfNeeded(
        file: File
    ) {

        if (
            file.exists() &&
            file.length() > 0L
        ) {
            return
        }

        val header =
            listOf(
                "runId",
                "messageId",
                "eventTime",
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

        file.appendText(
            header.joinToString(
                separator = ","
            ) {
                csv(it)
            } + "\n"
        )
    }


    private fun appendRow(
        file: File,
        values: List<Any?>
    ) {

        file.appendText(
            values.joinToString(
                separator = ","
            ) {
                csv(it)
            } + "\n"
        )
    }


    private fun csv(
        value: Any?
    ): String {

        if (
            value == null
        ) {
            return ""
        }

        val text =
            value.toString()

        return "\"" +
                text.replace(
                    "\"",
                    "\"\""
                ) +
                "\""
    }
}
