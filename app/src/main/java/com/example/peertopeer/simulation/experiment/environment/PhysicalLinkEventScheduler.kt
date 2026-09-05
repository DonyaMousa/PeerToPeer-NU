package com.example.peertopeer.simulation.experiment.environment

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.experiment.instrumentation.ExperimentInstrumentation
import com.example.peertopeer.simulation.experiment.record.TopologyEventRecord
import com.example.peertopeer.simulation.experiment.record.TopologyEventType

/**
 * Applies protocol-independent physical topology events to a simulation graph
 * and records the same event through the protocol's instrumentation layer.
 *
 * For MM/2RH/CARBLE, MMInstrumentation converts the recorded topology event
 * into observation-tracker instability evidence. B0 receives the same graph
 * mutation and canonical topology record, but naturally has no confidence
 * state to update.
 */
object PhysicalLinkEventScheduler {

    data class LinkEvent(
        val time: Long,
        val fromNodeId: String,
        val toNodeId: String,
        val type: TopologyEventType,
        val weightWhenUp: Int = 1
    ) {
        init {
            require(time >= 0L) {
                "event time must be non-negative"
            }
            require(fromNodeId.isNotBlank()) {
                "fromNodeId must not be blank"
            }
            require(toNodeId.isNotBlank()) {
                "toNodeId must not be blank"
            }
            require(fromNodeId != toNodeId) {
                "physical link endpoints must be different"
            }
            require(weightWhenUp > 0) {
                "weightWhenUp must be positive"
            }
            require(
                type == TopologyEventType.LINK_DOWN ||
                        type == TopologyEventType.LINK_UP
            ) {
                "PhysicalLinkEventScheduler supports LINK_DOWN/LINK_UP only"
            }
        }
    }

    fun install(
        engine: SimulationEngine,
        graph: Graph,
        instrumentation: ExperimentInstrumentation,
        runId: String,
        events: List<LinkEvent>
    ) {
        require(runId.isNotBlank()) {
            "runId must not be blank"
        }

        events
            .sortedBy { it.time }
            .forEach { event ->
                engine.schedule(event.time) {
                    applyEvent(
                        graph = graph,
                        instrumentation = instrumentation,
                        runId = runId,
                        event = event
                    )
                }
            }
    }

    fun flap(
        fromNodeId: String,
        toNodeId: String,
        downAt: Long,
        upAt: Long,
        weightWhenUp: Int = 1
    ): List<LinkEvent> {
        require(upAt > downAt) {
            "upAt must be later than downAt"
        }

        return listOf(
            LinkEvent(
                time = downAt,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                type = TopologyEventType.LINK_DOWN,
                weightWhenUp = weightWhenUp
            ),
            LinkEvent(
                time = upAt,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                type = TopologyEventType.LINK_UP,
                weightWhenUp = weightWhenUp
            )
        )
    }

    private fun applyEvent(
        graph: Graph,
        instrumentation: ExperimentInstrumentation,
        runId: String,
        event: LinkEvent
    ) {
        when (event.type) {
            TopologyEventType.LINK_DOWN -> {
                val oldWeight = graph.edgeCost(
                    event.fromNodeId,
                    event.toNodeId
                ) ?: return

                val removed = graph.removeEdge(
                    event.fromNodeId,
                    event.toNodeId
                )

                if (!removed) {
                    return
                }

                instrumentation.onTopologyEvent(
                    TopologyEventRecord(
                        runId = runId,
                        eventTime = event.time,
                        fromNodeId = event.fromNodeId,
                        toNodeId = event.toNodeId,
                        eventType = TopologyEventType.LINK_DOWN,
                        oldWeight = oldWeight,
                        newWeight = null
                    )
                )
            }

            TopologyEventType.LINK_UP -> {
                if (graph.containsEdge(
                        event.fromNodeId,
                        event.toNodeId
                    )
                ) {
                    return
                }

                graph.addEdge(
                    from = event.fromNodeId,
                    to = event.toNodeId,
                    weight = event.weightWhenUp
                )

                instrumentation.onTopologyEvent(
                    TopologyEventRecord(
                        runId = runId,
                        eventTime = event.time,
                        fromNodeId = event.fromNodeId,
                        toNodeId = event.toNodeId,
                        eventType = TopologyEventType.LINK_UP,
                        oldWeight = null,
                        newWeight = event.weightWhenUp
                    )
                )
            }

            TopologyEventType.LINK_WEIGHT_CHANGED -> {
                error(
                    "LINK_WEIGHT_CHANGED is not supported by " +
                            "PhysicalLinkEventScheduler"
                )
            }
        }
    }
}
