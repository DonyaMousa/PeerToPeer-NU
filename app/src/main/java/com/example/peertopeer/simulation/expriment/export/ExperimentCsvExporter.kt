package com.example.peertopeer.simulation.experiment.export

import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.ResourceSampleRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.simulation.experiment.result.RunSummary
import com.example.peertopeer.simulation.experiment.runner.B0ExperimentRunner
import java.io.File

class ExperimentCsvExporter(
    private val outputDirectory: File
) {

    init {
        outputDirectory.mkdirs()
    }

    fun exportRun(
        config: ExperimentConfig,
        output: B0ExperimentRunner.RunOutput
    ) {

        appendRunConfig(
            config
        )

        appendPackets(
            output.packets
        )

        appendTransmissions(
            output.transmissions
        )

        appendRoutingEvents(
            output.routingEvents
        )

        appendTopologyEvents(
            output.topologyEvents
        )

        appendQueueEvents(
            output.queueEvents
        )

        appendResourceSamples(
            output.resourceSamples
        )

        appendSummary(
            output.summary
        )
    }

    // =====================================================
    // RUN CONFIGURATION
    // =====================================================

    private fun appendRunConfig(
        config: ExperimentConfig
    ) {

        val file =
            File(
                outputDirectory,
                "runs.csv"
            )

        /*
         * IMPORTANT:
         *
         * Header order MUST exactly match
         * appendRow() value order below.
         */
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

                /*
                 * Stochastic traffic configuration.
                 */
                "burstProbability",
                "burstSize",
                "burstSpacing",

                "queueCapacity",
                "serviceTime",

                "maxAttempts",
                "retryDelay",
                "linkModel",

                /*
                 * Stochastic link configuration.
                 */
                "linkSuccessProbability",

                /*
                 * Stochastic topology configuration.
                 */
                "topologyFailureProbability",
                "topologyDecisionTimes",

                "conditionName",
                "gitCommit",
                "notes"
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

                /*
                 * Must match the three traffic headers above.
                 */
                config.traffic.burstProbability,
                config.traffic.burstSize,
                config.traffic.burstSpacing,

                config.scenario.queueCapacity,
                config.scenario.serviceTime,

                config.link.maxAttempts,
                config.link.retryDelay,
                config.link.modelName,

                config.link.successProbability,

                config.scenario.topologyFailureProbability,

                config.scenario
                    .topologyDecisionTimes
                    .takeIf {
                        it.isNotEmpty()
                    }
                    ?.joinToString(
                        separator = ";"
                    ),

                config.scenario.conditionName,
                config.gitCommit,
                config.notes
            )
        )
    }

    // =====================================================
    // PACKETS
    // =====================================================

    private fun appendPackets(
        records: List<PacketRecord>
    ) {

        val file =
            File(
                outputDirectory,
                "packet_results.csv"
            )

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

    // =====================================================
    // TRANSMISSIONS
    // =====================================================

    private fun appendTransmissions(
        records: List<TransmissionRecord>
    ) {

        val file =
            File(
                outputDirectory,
                "transmission_events.csv"
            )

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

    // =====================================================
    // ROUTING EVENTS
    // =====================================================

    private fun appendRoutingEvents(
        records: List<RoutingEventRecord>
    ) {

        val file =
            File(
                outputDirectory,
                "routing_events.csv"
            )

        if (records.isEmpty()) {
            return
        }

        val header =
            researchFields(
                records.first()
            ).map {
                it.name
            }

        writeHeaderIfNeeded(
            file,
            header
        )

        records.forEach { r ->

            appendRow(
                file,
                reflectValues(
                    r
                )
            )
        }
    }

    // =====================================================
    // TOPOLOGY EVENTS
    // =====================================================

    private fun appendTopologyEvents(
        records: List<TopologyEventRecord>
    ) {

        val file =
            File(
                outputDirectory,
                "topology_events.csv"
            )

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

    // =====================================================
    // QUEUE EVENTS
    // =====================================================

    private fun appendQueueEvents(
        records: List<QueueEventRecord>
    ) {

        val file =
            File(
                outputDirectory,
                "queue_events.csv"
            )

        if (records.isEmpty()) {
            return
        }

        val header =
            researchFields(
                records.first()
            ).map {
                it.name
            }

        writeHeaderIfNeeded(
            file,
            header
        )

        records.forEach { r ->

            appendRow(
                file,
                reflectValues(
                    r
                )
            )
        }
    }

    // =====================================================
    // RESOURCE SAMPLES
    // =====================================================

    private fun appendResourceSamples(
        records: List<ResourceSampleRecord>
    ) {

        val file =
            File(
                outputDirectory,
                "resource_samples.csv"
            )

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

    // =====================================================
    // RUN SUMMARY
    // =====================================================

    private fun appendSummary(
        summary: RunSummary
    ) {

        val file =
            File(
                outputDirectory,
                "run_summary.csv"
            )

        val header =
            researchFields(
                summary
            ).map {
                it.name
            }

        writeHeaderIfNeeded(
            file,
            header
        )

        appendRow(
            file,
            reflectValues(
                summary
            )
        )
    }

    // =====================================================
    // REFLECTION HELPERS
    // =====================================================

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

        return researchFields(
            value
        ).map { field ->

            field.isAccessible =
                true

            field.get(
                value
            )
        }
    }

    // =====================================================
    // CSV HELPERS
    // =====================================================

    private fun writeHeaderIfNeeded(
        file: File,
        header: List<String>
    ) {

        if (
            !file.exists() ||
            file.length() == 0L
        ) {

            file.appendText(
                header.joinToString(
                    separator = ","
                ) {
                    csv(
                        it
                    )
                } + "\n"
            )
        }
    }

    private fun appendRow(
        file: File,
        values: List<Any?>
    ) {

        file.appendText(
            values.joinToString(
                separator = ","
            ) {
                csv(
                    it
                )
            } + "\n"
        )
    }

    private fun csv(
        value: Any?
    ): String {

        if (value == null) {
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