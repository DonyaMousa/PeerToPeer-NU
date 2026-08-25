package com.example.peertopeer.simulation.experiment.result

import com.example.peertopeer.network.PacketDropReason
import com.example.peertopeer.simulation.experiment.config.ExperimentConfig
import com.example.peertopeer.simulation.experiment.record.PacketRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventRecord
import com.example.peertopeer.simulation.experiment.record.QueueEventType
import com.example.peertopeer.simulation.experiment.record.ResourceSampleRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventRecord
import com.example.peertopeer.simulation.experiment.record.RoutingEventType
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventType
import com.example.peertopeer.simulation.experiment.record.TransmissionRecord
import com.example.peertopeer.routing.RoutingTelemetry
import kotlin.math.ceil

object ExperimentAggregator {

    fun aggregate(
        config: ExperimentConfig,
        packets: List<PacketRecord>,
        transmissions: List<TransmissionRecord>,
        routingEvents: List<RoutingEventRecord>,
        topologyEvents: List<TopologyEventRecord>,
        queueEvents: List<QueueEventRecord>,
        resourceSamples: List<ResourceSampleRecord>,
        routingTelemetry: RoutingTelemetry
    ): RunSummary {

        // =================================================
        // PACKETS
        // =================================================

        val generatedPackets =
            packets.size

        val deliveredPackets =
            packets.count {
                it.delivered
            }

        val droppedPackets =
            packets.count {
                it.dropped
            }

        val pdr =
            if (generatedPackets == 0) {
                0.0
            } else {
                deliveredPackets.toDouble() /
                        generatedPackets.toDouble()
            }


        // =================================================
        // LATENCY
        // =================================================

        val deliveredLatencies =
            packets
                .filter {
                    it.delivered
                }
                .mapNotNull {
                    it.endToEndLatency
                }

        val meanLatency =
            meanOrNull(
                deliveredLatencies
            )

        val p50Latency =
            medianOrNull(
                deliveredLatencies
            )

        val p95Latency =
            percentileNearestRank(
                deliveredLatencies,
                0.95
            )

        val p99Latency =
            percentileNearestRank(
                deliveredLatencies,
                0.99
            )

        val maxLatency =
            deliveredLatencies.maxOrNull()


        // =================================================
        // FAILED PACKET TERMINATION
        // =================================================

        val failureTerminationTimes =
            packets
                .filter {
                    it.dropped
                }
                .mapNotNull {
                    it.terminationTime
                }

        val meanFailureTerminationTime =
            meanOrNull(
                failureTerminationTimes
            )

        val maxFailureTerminationTime =
            failureTerminationTimes.maxOrNull()


        // =================================================
        // DROP BREAKDOWN
        // =================================================

        val noRouteDrops =
            countDrops(
                packets,
                PacketDropReason.NO_ROUTE
            )

        val retryExhaustedDrops =
            countDrops(
                packets,
                PacketDropReason.RETRY_EXHAUSTED
            )

        val queueFullDrops =
            countDrops(
                packets,
                PacketDropReason.QUEUE_FULL
            )

        val ttlExpiredDrops =
            countDrops(
                packets,
                PacketDropReason.TTL_EXPIRED
            )

        val linkUnavailableDrops =
            countDrops(
                packets,
                PacketDropReason.LINK_UNAVAILABLE
            )


        // =================================================
        // TRANSMISSION TELEMETRY
        // =================================================

        val physicalAttempts =
            transmissions.size.toLong()

        val successfulPhysicalAttempts =
            transmissions.count {
                it.success
            }.toLong()

        val failedPhysicalAttempts =
            transmissions.count {
                !it.success
            }.toLong()

        /*
         * One logical hop is identified by:
         *
         * run
         * message
         * logicalHopIndex
         *
         * If logicalHopIndex has not yet been populated,
         * we safely fall back to:
         *
         * message + from + to
         *
         * This fallback is acceptable for the current
         * B0 experiments, but later the runner should
         * populate logicalHopIndex explicitly.
         */
        val logicalHopAttempts =
            transmissions
                .groupBy {
                    logicalHopIdentity(it)
                }
                .size
                .toLong()

        val retransmissions =
            (physicalAttempts -
                    logicalHopAttempts)
                .coerceAtLeast(0)

        val physicalAttemptsPerDeliveredPacket =
            if (deliveredPackets == 0) {
                null
            } else {
                physicalAttempts.toDouble() /
                        deliveredPackets.toDouble()
            }


        // =================================================
        // BYTE EFFICIENCY
        // =================================================

        val usefulDeliveredBytes =
            deliveredPackets.toLong() *
                    config.traffic.payloadBytes.toLong()

        val physicalAttemptsPerUsefulDeliveredByte =
            if (usefulDeliveredBytes <= 0L) {
                null
            } else {
                physicalAttempts.toDouble() /
                        usefulDeliveredBytes.toDouble()
            }


        // =================================================
        // ROUTING
        // =================================================

        val routeRequests =
            routingEvents.count {
                it.eventType ==
                        RoutingEventType.ROUTE_REQUEST
            }.toLong()

        val routesFound =
            routingEvents.count {
                it.eventType ==
                        RoutingEventType.ROUTE_FOUND
            }.toLong()

        val routeChanges =
            routingEvents.count {
                it.eventType ==
                        RoutingEventType.ROUTE_CHANGED
            }.toLong()

        val noRouteEvents =
            routingEvents.count {
                it.eventType ==
                        RoutingEventType.NO_ROUTE
            }.toLong()


        // =================================================
        // TOPOLOGY
        // =================================================

        val topologyEventCount =
            topologyEvents.size.toLong()

        val linkUpEvents =
            topologyEvents.count {
                it.eventType ==
                        TopologyEventType.LINK_UP
            }.toLong()

        val linkDownEvents =
            topologyEvents.count {
                it.eventType ==
                        TopologyEventType.LINK_DOWN
            }.toLong()

        val linkWeightChangeEvents =
            topologyEvents.count {
                it.eventType ==
                        TopologyEventType.LINK_WEIGHT_CHANGED
            }.toLong()


        // =================================================
        // QUEUE
        // =================================================

        val queueEnqueueEvents =
            queueEvents.count {
                it.eventType ==
                        QueueEventType.ENQUEUED
            }.toLong()

        val queueDequeueEvents =
            queueEvents.count {
                it.eventType ==
                        QueueEventType.DEQUEUED
            }.toLong()

        val queueFullEvents =
            queueEvents.count {
                it.eventType ==
                        QueueEventType.DROPPED_FULL
            }.toLong()

        val maximumQueueOccupancy =
            queueEvents
                .maxOfOrNull {
                    it.queueSizeAfterEvent
                }

        val queueWaits =
            queueEvents
                .mapNotNull {
                    it.waitTime
                }

        val meanQueueWait =
            meanOrNull(
                queueWaits
            )

        val p95QueueWait =
            percentileNearestRank(
                queueWaits,
                0.95
            )

        val maxQueueWait =
            queueWaits.maxOrNull()


        // =================================================
        // RESOURCE / SUSTAINABILITY PROXIES
        // =================================================

        /*
         * ResourceSampleRecord may contain multiple
         * time samples for one node.
         *
         * packetsForwarded is treated as cumulative,
         * therefore use each node's maximum observed
         * value rather than summing samples.
         */
        val forwardingBurdenByNode =
            resourceSamples
                .groupBy {
                    it.nodeId
                }
                .mapValues {
                        (_, samples) ->

                    samples.maxOfOrNull {
                        it.packetsForwarded
                    } ?: 0L
                }

        val forwardingBurdens =
            forwardingBurdenByNode.values
                .toList()

        val worstNodeForwardingBurden =
            forwardingBurdens.maxOrNull()

        val meanNodeForwardingBurden =
            if (forwardingBurdens.isEmpty()) {
                null
            } else {
                forwardingBurdens.average()
            }

        val forwardingBurdenImbalance =
            if (
                worstNodeForwardingBurden == null ||
                meanNodeForwardingBurden == null ||
                meanNodeForwardingBurden == 0.0
            ) {
                null
            } else {
                worstNodeForwardingBurden.toDouble() /
                        meanNodeForwardingBurden
            }


        // =================================================
        // FINAL SUMMARY
        // =================================================

        return RunSummary(

            runId =
                config.runId,

            protocol =
                config.protocol,

            scenarioId =
                config.scenario.scenarioId,

            seed =
                config.seed,

            runIndex =
                config.runIndex,

            generatedPackets =
                generatedPackets,

            deliveredPackets =
                deliveredPackets,

            droppedPackets =
                droppedPackets,

            packetDeliveryRatio =
                pdr,

            meanLatency =
                meanLatency,

            p50Latency =
                p50Latency,

            p95Latency =
                p95Latency,

            p99Latency =
                p99Latency,

            maxLatency =
                maxLatency,

            meanFailureTerminationTime =
                meanFailureTerminationTime,

            maxFailureTerminationTime =
                maxFailureTerminationTime,

            noRouteDrops =
                noRouteDrops,

            retryExhaustedDrops =
                retryExhaustedDrops,

            queueFullDrops =
                queueFullDrops,

            ttlExpiredDrops =
                ttlExpiredDrops,

            linkUnavailableDrops =
                linkUnavailableDrops,

            logicalHopAttempts =
                logicalHopAttempts,

            physicalAttempts =
                physicalAttempts,

            successfulPhysicalAttempts =
                successfulPhysicalAttempts,

            failedPhysicalAttempts =
                failedPhysicalAttempts,

            retransmissions =
                retransmissions,

            physicalAttemptsPerDeliveredPacket =
                physicalAttemptsPerDeliveredPacket,

            usefulDeliveredBytes =
                usefulDeliveredBytes,

            physicalAttemptsPerUsefulDeliveredByte =
                physicalAttemptsPerUsefulDeliveredByte,

            // -----------------------------------------------------
            // ROUTING EVENTS
            // -----------------------------------------------------

            routeRequests =
                routeRequests,

            routesFound =
                routesFound,

            routeChanges =
                routeChanges,

            noRouteEvents =
                noRouteEvents,

            // -----------------------------------------------------
            // ROUTING CACHE / COMPUTATION
            // -----------------------------------------------------

            cacheHits =
                routingTelemetry.cacheHits.toLong(),

            cacheMisses =
                routingTelemetry.cacheMisses.toLong(),

            routeCalculations =
                routingTelemetry.routeCalculations.toLong(),

            cacheInvalidations =
                routingTelemetry.cacheInvalidations.toLong(),

            successfulRouteCalculations =
                routingTelemetry.successfulRoutes.toLong(),

            unreachableRouteCalculations =
                routingTelemetry.unreachableRoutes.toLong(),

            // -----------------------------------------------------
            // TOPOLOGY
            // -----------------------------------------------------

            topologyEvents =
                topologyEventCount,

            linkUpEvents =
                linkUpEvents,

            linkDownEvents =
                linkDownEvents,

            linkWeightChangeEvents =
                linkWeightChangeEvents,

            // -----------------------------------------------------
            // QUEUE
            // -----------------------------------------------------

            queueEnqueueEvents =
                queueEnqueueEvents,

            queueDequeueEvents =
                queueDequeueEvents,

            queueFullEvents =
                queueFullEvents,

            maximumQueueOccupancy =
                maximumQueueOccupancy,

            meanQueueWait =
                meanQueueWait,

            p95QueueWait =
                p95QueueWait,

            maxQueueWait =
                maxQueueWait,

            // -----------------------------------------------------
            // RESOURCE
            // -----------------------------------------------------

            worstNodeForwardingBurden =
                worstNodeForwardingBurden,

            meanNodeForwardingBurden =
                meanNodeForwardingBurden,

            forwardingBurdenImbalance =
                forwardingBurdenImbalance,

            // -----------------------------------------------------
            // NOTES
            // -----------------------------------------------------

            notes =
                config.notes
        )
    }


    // =====================================================
    // HELPERS
    // =====================================================

    private fun countDrops(
        packets: List<PacketRecord>,
        reason: PacketDropReason
    ): Int {

        return packets.count {
            it.dropped &&
                    it.dropReason == reason
        }
    }


    private fun meanOrNull(
        values: List<Long>
    ): Double? {

        if (values.isEmpty()) {
            return null
        }

        return values.average()
    }


    /*
     * Exact median.
     *
     * Even-sized samples return the midpoint
     * as a Double.
     */
    private fun medianOrNull(
        values: List<Long>
    ): Double? {

        if (values.isEmpty()) {
            return null
        }

        val sorted =
            values.sorted()

        val middle =
            sorted.size / 2

        return if (
            sorted.size % 2 == 1
        ) {

            sorted[middle].toDouble()

        } else {

            (
                    sorted[middle - 1].toDouble() +
                            sorted[middle].toDouble()
                    ) / 2.0
        }
    }


    /*
     * Nearest-rank percentile.
     *
     * Example:
     *
     * p95:
     * ceil(0.95 * N)
     *
     * We are explicitly fixing the percentile
     * definition here so it does not silently
     * change later in the research.
     */
    private fun percentileNearestRank(
        values: List<Long>,
        percentile: Double
    ): Long? {

        if (values.isEmpty()) {
            return null
        }

        require(
            percentile > 0.0 &&
                    percentile <= 1.0
        ) {
            "percentile must be in (0, 1]."
        }

        val sorted =
            values.sorted()

        val rank =
            ceil(
                percentile *
                        sorted.size.toDouble()
            )
                .toInt()
                .coerceAtLeast(1)

        return sorted[
            rank - 1
        ]
    }


    private fun logicalHopIdentity(
        transmission: TransmissionRecord
    ): String {

        val hopIndex =
            transmission.logicalHopIndex

        return if (hopIndex != null) {

            "${transmission.messageId}:" +
                    "$hopIndex"

        } else {

            /*
             * Temporary compatibility fallback.
             *
             * The final experiment runner should supply
             * logicalHopIndex so that a future packet
             * revisiting the same edge cannot be merged
             * accidentally.
             */
            "${transmission.messageId}:" +
                    "${transmission.fromNodeId}:" +
                    transmission.toNodeId
        }
    }
}
