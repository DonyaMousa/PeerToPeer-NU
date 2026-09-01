package com.example.peertopeer.simulation.experiment.export

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.ResourceSampleRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.result.RunSummary
import com.example.peertopeer.simulation.experiment.runner.CarbleExperimentRunner
import java.io.File

class CarbleCsvExporter(
    private val outputDirectory: File
) {

    init {
        outputDirectory.mkdirs()
    }
    private val regimeEventExporter =
        CarbleRegimeEventCsvExporter(
            outputDirectory
        )

    fun exportRun(
        config: ExperimentConfig,
        output: CarbleExperimentRunner.RunOutput
    ) {
        appendRunConfig(config)
        appendPackets(output.packets)
        appendTransmissions(output.transmissions)
        appendRoutingEvents(output.routingEvents)
        appendTopologyEvents(output.topologyEvents)
        appendQueueEvents(output.queueEvents)
        appendResourceSamples(output.resourceSamples)
        appendSummary(output.summary)
        appendAdaptation(config, output)

        regimeEventExporter.append(
            output.regimeEvents
        )
    }

    private fun appendRunConfig(
        config: ExperimentConfig
    ) {
        val file =
            File(outputDirectory, "runs.csv")

        writeHeaderIfNeeded(
            file,
            listOf(
                "experimentSetId",
                "runId",
                "protocol",
                "protocolVersion",
                "runIndex",
                "seed",
                "scenarioId",
                "scenarioName",
                "topologyType",
                "nodeCount",
                "packetCount",
                "packetInterval",
                "packetTtl",
                "payloadBytes",
                "sourceCount",
                "queueCapacity",
                "serviceTime",
                "maxAttempts",
                "retryDelay",
                "linkModel",
                "linkSuccessProbability",
                "topologyFailureProbability",
                "topologyDecisionTimes",
                "conditionName",
                "gitCommit",
                "notes",
                "burstProbability",
                "burstSize",
                "burstSpacing"
            )
        )

        appendRow(
            file,
            listOf(
                config.experimentSetId,
                config.runId,
                config.protocol,
                config.protocolVersion,
                config.runIndex,
                config.seed,
                config.scenario.scenarioId,
                config.scenario.scenarioName,
                config.scenario.topologyType,
                config.scenario.nodeCount,
                config.traffic.packetCount,
                config.traffic.packetInterval,
                config.traffic.packetTtl,
                config.traffic.payloadBytes,
                config.traffic.sourceCount,
                config.scenario.queueCapacity,
                config.scenario.serviceTime,
                config.link.maxAttempts,
                config.link.retryDelay,
                config.link.modelName,
                config.link.successProbability,
                config.scenario.topologyFailureProbability,
                config.scenario.topologyDecisionTimes
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(";"),
                config.scenario.conditionName,
                config.gitCommit,
                config.notes,
                config.traffic.burstProbability,
                config.traffic.burstSize,
                config.traffic.burstSpacing
            )
        )
    }

    private fun appendPackets(
        records: List<PacketRecord>
    ) {
        val file =
            File(outputDirectory, "packet_results.csv")

        writeHeaderIfNeeded(
            file,
            listOf(
                "runId",
                "messageId",
                "sourceId",
                "destinationId",
                "createdAt",
                "deliveredAt",
                "droppedAt",
                "delivered",
                "dropped",
                "dropReason",
                "hopCount",
                "endToEndLatency",
                "terminationTime"
            )
        )

        records.forEach { r ->
            appendRow(
                file,
                listOf(
                    r.runId,
                    r.messageId,
                    r.sourceId,
                    r.destinationId,
                    r.createdAt,
                    r.deliveredAt,
                    r.droppedAt,
                    r.delivered,
                    r.dropped,
                    r.dropReason,
                    r.hopCount,
                    r.endToEndLatency,
                    r.terminationTime
                )
            )
        }
    }

    private fun appendTransmissions(
        records: List<TransmissionRecord>
    ) {
        val file =
            File(outputDirectory, "transmission_events.csv")

        writeHeaderIfNeeded(
            file,
            listOf(
                "runId",
                "messageId",
                "fromNodeId",
                "toNodeId",
                "logicalHopIndex",
                "attemptNumber",
                "attemptTime",
                "success"
            )
        )

        records.forEach { r ->
            appendRow(
                file,
                listOf(
                    r.runId,
                    r.messageId,
                    r.fromNodeId,
                    r.toNodeId,
                    r.logicalHopIndex,
                    r.attemptNumber,
                    r.attemptTime,
                    r.success
                )
            )
        }
    }

    private fun appendRoutingEvents(
        records: List<RoutingEventRecord>
    ) {
        if (records.isEmpty()) return

        val file =
            File(outputDirectory, "routing_events.csv")

        writeHeaderIfNeeded(
            file,
            researchFields(records.first())
                .map { it.name }
        )

        records.forEach { r ->
            appendRow(file, reflectValues(r))
        }
    }

    private fun appendTopologyEvents(
        records: List<TopologyEventRecord>
    ) {
        val file =
            File(outputDirectory, "topology_events.csv")

        writeHeaderIfNeeded(
            file,
            listOf(
                "runId",
                "eventTime",
                "fromNodeId",
                "toNodeId",
                "eventType",
                "oldWeight",
                "newWeight"
            )
        )

        records.forEach { r ->
            appendRow(
                file,
                listOf(
                    r.runId,
                    r.eventTime,
                    r.fromNodeId,
                    r.toNodeId,
                    r.eventType,
                    r.oldWeight,
                    r.newWeight
                )
            )
        }
    }

    private fun appendQueueEvents(
        records: List<QueueEventRecord>
    ) {
        if (records.isEmpty()) return

        val file =
            File(outputDirectory, "queue_events.csv")

        writeHeaderIfNeeded(
            file,
            researchFields(records.first())
                .map { it.name }
        )

        records.forEach { r ->
            appendRow(file, reflectValues(r))
        }
    }

    private fun appendResourceSamples(
        records: List<ResourceSampleRecord>
    ) {
        val file =
            File(outputDirectory, "resource_samples.csv")

        writeHeaderIfNeeded(
            file,
            listOf(
                "runId",
                "nodeId",
                "sampleTime",
                "packetsTransmitted",
                "packetsReceived",
                "packetsForwarded",
                "physicalAttempts",
                "retransmissions",
                "queueOccupancy",
                "routingCalculations",
                "batteryPercent",
                "batteryChargeMicroAh",
                "currentMicroAmp",
                "temperatureCelsius",
                "cpuUsagePercent",
                "memoryBytes"
            )
        )

        records.forEach { r ->
            appendRow(
                file,
                listOf(
                    r.runId,
                    r.nodeId,
                    r.sampleTime,
                    r.packetsTransmitted,
                    r.packetsReceived,
                    r.packetsForwarded,
                    r.physicalAttempts,
                    r.retransmissions,
                    r.queueOccupancy,
                    r.routingCalculations,
                    r.batteryPercent,
                    r.batteryChargeMicroAh,
                    r.currentMicroAmp,
                    r.temperatureCelsius,
                    r.cpuUsagePercent,
                    r.memoryBytes
                )
            )
        }
    }

    private fun appendSummary(
        summary: RunSummary
    ) {
        val file =
            File(outputDirectory, "run_summary.csv")

        writeHeaderIfNeeded(
            file,
            researchFields(summary)
                .map { it.name }
        )

        appendRow(
            file,
            reflectValues(summary)
        )
    }

    private fun appendAdaptation(
        config: ExperimentConfig,
        output: CarbleExperimentRunner.RunOutput
    ) {
        val file =
            File(outputDirectory, "carble_adaptation.csv")

        writeHeaderIfNeeded(
            file,
            listOf(
                "experimentSetId",
                "runId",
                "scenarioId",
                "runIndex",
                "seed",
                "highDecisions",
                "mediumDecisions",
                "lowDecisions",
                "m1Decisions",
                "m2Decisions",
                "m3Decisions",
                "downstreamWarnings",
                "backupPrepared",
                "backupActivations",
                "backupSuccesses",
                "backupFailures",
                "duplicateSuppressions",
                "mediumToHighRecoveries",
                "mediumToLowEscalations",
                "lowToMediumRecoveries",
                "lowToHighRecoveries",
                "carryDecisions",
                "probeDecisions",
                "probeSuccesses",
                "probeFailures",
                "copyBudgetExhaustions",
                "fallbackDrops"
            )
        )

        val a = output.adaptation

        appendRow(
            file,
            listOf(
                config.experimentSetId,
                config.runId,
                config.scenario.scenarioId,
                config.runIndex,
                config.seed,
                a.highDecisions,
                a.mediumDecisions,
                a.lowDecisions,
                a.m1Decisions,
                a.m2Decisions,
                a.m3Decisions,
                a.downstreamWarnings,
                a.backupPrepared,
                a.backupActivations,
                a.backupSuccesses,
                a.backupFailures,
                a.duplicateSuppressions,
                a.mediumToHighRecoveries,
                a.mediumToLowEscalations,
                a.lowToMediumRecoveries,
                a.lowToHighRecoveries,
                a.carryDecisions,
                a.probeDecisions,
                a.probeSuccesses,
                a.probeFailures,
                a.copyBudgetExhaustions,
                a.fallbackDrops
            )
        )
    }

    private fun researchFields(
        value: Any
    ) =
        value.javaClass
            .declaredFields
            .filter {
                !it.isSynthetic &&
                        !it.name.startsWith("$")
            }

    private fun reflectValues(
        value: Any
    ): List<Any?> {
        return researchFields(value)
            .map { field ->
                field.isAccessible = true
                field.get(value)
            }
    }

    private fun writeHeaderIfNeeded(
        file: File,
        header: List<String>
    ) {
        if (
            !file.exists() ||
            file.length() == 0L
        ) {
            file.appendText(
                header.joinToString(",") {
                    csv(it)
                } + "\n"
            )
        }
    }

    private fun appendRow(
        file: File,
        values: List<Any?>
    ) {
        file.appendText(
            values.joinToString(",") {
                csv(it)
            } + "\n"
        )
    }

    private fun csv(
        value: Any?
    ): String {
        if (value == null) return ""

        val text = value.toString()

        return "\"" +
                text.replace(
                    "\"",
                    "\"\""
                ) +
                "\""
    }
}
