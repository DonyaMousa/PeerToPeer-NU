package com.example.peertopeer.network.forwarding

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.network.model.ProtocolType
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.carble.CarbleMediumStage
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleRegime
import com.example.peertopeer.routing.carble.CarbleRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeState
import com.example.peertopeer.routing.mm.MultiMetricRoutingEngine
import com.example.peertopeer.routing.mm.MultiMetricCostCalculator

class RealProtocolRouter(private val metrics: LinkMetricsStore) {
    companion object {
        private const val ROUTE_SWITCH_MARGIN = 0.05
        /** Compatibility helper: reports direct candidacy; it no longer gives that path priority. */
        internal fun preferredDirectPath(graph: Graph, sourceId: String, destinationId: String): List<String>? =
            if (graph.containsEdge(sourceId, destinationId)) listOf(sourceId, destinationId) else null
    }

    data class LinkTrace(
        val from: String,
        val to: String,
        val cost: Double,
        val reliabilityPenalty: Double,
        val delayPenalty: Double,
        val queuePenalty: Double,
        val instabilityPenalty: Double,
        val resourcePenalty: Double,
        val q: Double?
    )

    data class CandidateTrace(
        val path: List<String>,
        val totalCost: Double,
        val routeQ: Double?,
        val links: List<LinkTrace>,
        val selected: Boolean
    )

    data class RouteTrace(
        val destinationId: String,
        val selectedPath: List<String>,
        val totalCost: Double,
        val routeQ: Double?,
        val reason: String,
        val candidates: List<CandidateTrace>
    )

    data class TwoHopCandidate(
        val relayId: String,
        val path: List<String>,
        val totalCost: Double,
        val firstHopCost: Double,
        val secondHopCost: Double,
        val firstHopConfidence: Double?,
        val secondHopConfidence: Double?,
        val routeConfidence: Double?
    )

    private val dijkstra = DijkstraEngine()
    private val mm = MultiMetricRoutingEngine()
    private val mmCostCalculator = MultiMetricCostCalculator()
    private val twoRhEvaluator = TwoRegimeRouteEvaluator(metrics.stateStore)
    private val carbleEvaluator = CarbleRouteEvaluator(metrics.stateStore)
    /** Holds a proposed stage until it appears twice; Q itself is never altered. */
    private data class StageMemory(var stable: String? = null, var candidate: String? = null, var confirmations: Int = 0)
    private val stageMemory = mutableMapOf<String, StageMemory>()
    private val routeMemory = mutableMapOf<String, List<String>>()
    @Volatile private var latestTrace: RouteTrace? = null

    fun clearStageHysteresis() {
        stageMemory.clear()
        routeMemory.clear()
        latestTrace = null
    }

    fun latestRouteTrace(): RouteTrace? = latestTrace

    private data class StageResolution(val rawStage: String, val decisionStage: String, val status: String)

    private fun stableStage(protocol: ProtocolType, sourceId: String, destinationId: String, proposed: String): StageResolution {
        if (protocol !in setOf(ProtocolType.CARBLE, ProtocolType.TWO_RH)) return StageResolution(proposed, proposed, "NOT_APPLICABLE")
        val memory = stageMemory.getOrPut("${protocol.name}:$sourceId:$destinationId") { StageMemory() }
        val current = memory.stable
        if (current == null) {
            memory.stable = proposed
            return StageResolution(proposed, proposed, "INITIAL")
        }
        if (current == proposed) {
            memory.candidate = null
            memory.confirmations = 0
            return StageResolution(proposed, current, "STABLE")
        }
        if (memory.candidate == proposed) memory.confirmations++ else {
            memory.candidate = proposed
            memory.confirmations = 1
        }
        if (memory.confirmations >= 2) {
            memory.stable = proposed
            memory.candidate = null
            memory.confirmations = 0
            return StageResolution(proposed, proposed, "CHANGED_AFTER_TWO_OBSERVATIONS")
        }
        return StageResolution(proposed, memory.stable ?: proposed, "HELD_PENDING_CONFIRMATION")
    }

    fun decide(protocol: ProtocolType, graph: Graph, currentNodeId: String, destinationId: String, previousNodeId: String? = null): ForwardingDecision {
        // Never immediately send a relayed packet back to the device it just
        // arrived from. Without this guard, transient/stale multi-metric state
        // can create A↔B ping-pong loops in a live mesh.
        if (previousNodeId != null && previousNodeId != destinationId) {
            graph.removeEdge(currentNodeId, previousNodeId)
        }
        return when (protocol) {
            ProtocolType.B0 -> {
                latestTrace = null
                b0(graph, currentNodeId, destinationId)
            }
            ProtocolType.MM -> mm(graph, currentNodeId, destinationId, "MM")
            ProtocolType.TWO_RH -> twoRh(graph, currentNodeId, destinationId)
            ProtocolType.CARBLE -> carble(graph, currentNodeId, destinationId, previousNodeId)
        }
    }

    private fun b0(graph: Graph, sourceId: String, destinationId: String): ForwardingDecision {
        val source = graph.getNode(sourceId) ?: return ForwardingDecision.Drop("source unknown")
        val dest = graph.getNode(destinationId) ?: return ForwardingDecision.Drop("destination unknown")
        val route = dijkstra.findRoute(graph, source, dest) ?: return ForwardingDecision.Drop("no route")
        return route.nextHop?.let { ForwardingDecision.Forward(it.nodeId, "B0") }
            ?: ForwardingDecision.Drop("already at destination")
    }

    private fun mmRoute(graph: Graph, sourceId: String, destinationId: String): MultiMetricRoutingEngine.Route? {
        graph.getNodes().forEach { n ->
            graph.getNeighbors(n.nodeId).forEach { e -> metrics.ensure(e.from, e.to) }
        }
        val best = mm.findPath(
            sourceId = sourceId,
            destinationId = destinationId,
            neighborProvider = { node -> graph.getNeighbors(node).map { it.to } },
            linkStateProvider = { a, b -> metrics.stateStore.get(a, b) }
        ) ?: run {
            latestTrace = null
            return null
        }

        val memoryKey = "$sourceId|$destinationId"
        val previousPath = routeMemory[memoryKey]
        val previousCost = previousPath?.let { pathCost(graph, it) }
        val holdPrevious = previousPath != null &&
            previousPath != best.path &&
            previousCost != null &&
            best.totalCost >= previousCost * (1.0 - ROUTE_SWITCH_MARGIN)
        val selected = if (holdPrevious) {
            MultiMetricRoutingEngine.Route(previousPath, previousCost!!)
        } else best
        val reason = when {
            previousPath == null -> "INITIAL_LOWEST_MM_COST"
            previousCost == null -> "PREVIOUS_ROUTE_UNAVAILABLE"
            holdPrevious -> "HELD_BY_5_PERCENT_HYSTERESIS"
            previousPath != selected.path -> "LOWER_MM_COST"
            else -> "CURRENT_ROUTE_STILL_BEST"
        }
        routeMemory[memoryKey] = selected.path
        latestTrace = buildRouteTrace(graph, destinationId, selected, reason)
        return selected
    }

    private fun pathCost(graph: Graph, path: List<String>): Double? {
        if (path.size < 2) return null
        var total = 0.0
        for (index in 0 until path.lastIndex) {
            val from = path[index]
            val to = path[index + 1]
            if (!graph.containsEdge(from, to)) return null
            val state = metrics.stateStore.get(from, to) ?: return null
            total += mmCostCalculator.calculate(state).totalCost
        }
        return total
    }

    private fun buildRouteTrace(
        graph: Graph,
        destinationId: String,
        selected: MultiMetricRoutingEngine.Route,
        reason: String
    ): RouteTrace {
        val sourceId = selected.path.first()
        val candidatePaths = buildList {
            if (graph.containsEdge(sourceId, destinationId)) add(listOf(sourceId, destinationId))
            graph.getNeighbors(sourceId).map { it.to }.distinct().forEach { relay ->
                if (relay != destinationId && graph.containsEdge(relay, destinationId)) {
                    add(listOf(sourceId, relay, destinationId))
                }
            }
            add(selected.path)
        }.distinct()
        val candidates = candidatePaths.mapNotNull candidatePath@ { path ->
            val links = path.zipWithNext().mapNotNull link@ { (from, to) ->
                val state = metrics.stateStore.get(from, to) ?: return@link null
                val breakdown = mmCostCalculator.calculate(state)
                LinkTrace(
                    from = from,
                    to = to,
                    cost = breakdown.totalCost,
                    reliabilityPenalty = breakdown.reliabilityNormalized,
                    delayPenalty = breakdown.delayNormalized,
                    queuePenalty = breakdown.queueNormalized,
                    instabilityPenalty = breakdown.instabilityNormalized,
                    resourcePenalty = breakdown.energyNormalized,
                    q = metrics.snapshot(from, to)?.confidence
                )
            }
            if (links.size != path.size - 1) return@candidatePath null
            CandidateTrace(
                path = path,
                totalCost = links.sumOf { it.cost },
                routeQ = links.mapNotNull { it.q }.minOrNull(),
                links = links,
                selected = path == selected.path
            )
        }.sortedWith(compareBy<CandidateTrace> { it.totalCost }.thenBy { it.path.joinToString("|") })
        val chosen = candidates.firstOrNull { it.selected }
        return RouteTrace(
            destinationId = destinationId,
            selectedPath = selected.path,
            totalCost = selected.totalCost,
            routeQ = chosen?.routeQ,
            reason = reason,
            candidates = candidates
        )
    }

    private fun mm(graph: Graph, sourceId: String, destinationId: String, stage: String): ForwardingDecision {
        val route = mmRoute(graph, sourceId, destinationId) ?: return ForwardingDecision.Drop("no route", stage)
        val hop = route.nextHop ?: return ForwardingDecision.Drop("already at destination", stage)
        val trace = latestTrace
        return ForwardingDecision.Forward(hop, stage, route = route.path, routeCost = route.totalCost, routeReason = trace?.reason)
    }

    private fun twoRh(graph: Graph, sourceId: String, destinationId: String): ForwardingDecision {
        val route = mmRoute(graph, sourceId, destinationId) ?: return ForwardingDecision.Drop("no route", "LOW")
        if (route.path.size < 2) return ForwardingDecision.Drop("already at destination", "HIGH")
        val eval = twoRhEvaluator.evaluate(route.path)
        val stageResolution = stableStage(
            protocol = ProtocolType.TWO_RH,
            sourceId = sourceId,
            destinationId = destinationId,
            proposed = if (eval.currentHopState == TwoRegimeState.HIGH) "HIGH" else "LOW"
        )
        val stage = stageResolution.decisionStage
        return if (stage == "HIGH") {
            ForwardingDecision.Forward(
                nextHopId = route.path[1],
                stage = "HIGH",
                confidence = eval.currentHopConfidence,
                route = route.path,
                routeConfidence = eval.routeConfidence,
                routeCost = route.totalCost,
                routeReason = latestTrace?.reason,
                rawStage = stageResolution.rawStage,
                hysteresisStatus = stageResolution.status
            )
        } else {
            ForwardingDecision.Carry(
                delayMs = 1_000L,
                stage = "LOW",
                confidence = eval.currentHopConfidence,
                route = route.path,
                routeConfidence = eval.routeConfidence,
                routeCost = route.totalCost,
                routeReason = latestTrace?.reason,
                rawStage = stageResolution.rawStage,
                hysteresisStatus = stageResolution.status
            )
        }
    }

    private fun carble(graph: Graph, sourceId: String, destinationId: String, previousNodeId: String?): ForwardingDecision {
        val primary = mmRoute(graph, sourceId, destinationId) ?: return ForwardingDecision.Drop("no route", "LOW")
        if (primary.path.size < 2) return ForwardingDecision.Drop("already at destination", "HIGH")
        val eval = carbleEvaluator.evaluate(primary.path)
        val proposedStage = when {
            eval.regime == CarbleRegime.HIGH -> "HIGH"
            eval.mediumStage == CarbleMediumStage.M1 -> "M1"
            eval.mediumStage == CarbleMediumStage.M2 -> "M2"
            eval.mediumStage == CarbleMediumStage.M3 -> "M3"
            else -> "LOW"
        }
        val stageResolution = stableStage(ProtocolType.CARBLE, sourceId, destinationId, proposedStage)
        val stage = stageResolution.decisionStage
        return when (stage) {
            "HIGH", "M1" -> ForwardingDecision.Forward(
                nextHopId = primary.path[1],
                stage = stage,
                confidence = eval.currentHopConfidence,
                route = primary.path,
                routeConfidence = eval.routeConfidence,
                routeCost = primary.totalCost,
                routeReason = latestTrace?.reason,
                rawStage = stageResolution.rawStage,
                hysteresisStatus = stageResolution.status
            )
            "M2", "M3" -> {
                val candidates = CarbleBackupCandidateFactory(graph, metrics.stateStore)
                    .createCandidates(sourceId, destinationId)
                val backupCandidate = CarbleBackupSelector()
                    .selectBackup(
                        primaryNextHopId = primary.path[1],
                        previousNodeId = previousNodeId,
                        candidates = candidates
                    )
                    ?.candidate
                ForwardingDecision.ForwardWithBackup(
                    primaryHopId = primary.path[1],
                    backupHopId = backupCandidate?.nextHopId,
                    backupDelayMs = if (stage == "M3") 1_000L else 0L,
                    stage = stage,
                    confidence = eval.currentHopConfidence,
                    route = primary.path,
                    backupRoute = backupCandidate?.path.orEmpty(),
                    routeConfidence = eval.routeConfidence,
                    routeCost = primary.totalCost,
                    routeReason = latestTrace?.reason,
                    rawStage = stageResolution.rawStage,
                    hysteresisStatus = stageResolution.status
                )
            }
            else -> ForwardingDecision.Carry(
                delayMs = 1_000L,
                stage = "LOW",
                confidence = eval.currentHopConfidence,
                route = primary.path,
                routeConfidence = eval.routeConfidence,
                routeCost = primary.totalCost,
                routeReason = latestTrace?.reason,
                rawStage = stageResolution.rawStage,
                hysteresisStatus = stageResolution.status
            )
        }
    }



    /**
     * Observational helper for the formal four-phone two-relay experiment.
     * It does not affect forwarding. It only reports the current two-hop
     * candidate costs/confidences that the live router is seeing.
     */
    fun twoHopCandidates(
        graph: Graph,
        sourceId: String,
        destinationId: String
    ): List<TwoHopCandidate> {
        return graph.getNeighbors(sourceId)
            .map { it.to }
            .filter { relay -> relay != destinationId && graph.containsEdge(relay, destinationId) }
            .distinct()
            .sorted()
            .mapNotNull { relay ->
                metrics.ensure(sourceId, relay)
                metrics.ensure(relay, destinationId)
                val firstState = metrics.stateStore.get(sourceId, relay) ?: return@mapNotNull null
                val secondState = metrics.stateStore.get(relay, destinationId) ?: return@mapNotNull null
                val firstCost = mmCostCalculator.calculate(firstState).totalCost
                val secondCost = mmCostCalculator.calculate(secondState).totalCost
                val totalCost = firstCost + secondCost
                val firstQ = metrics.snapshot(sourceId, relay)?.confidence
                val secondQ = metrics.snapshot(relay, destinationId)?.confidence
                val routeQ = listOfNotNull(firstQ, secondQ).minOrNull()
                TwoHopCandidate(
                    relayId = relay,
                    path = listOf(sourceId, relay, destinationId),
                    totalCost = totalCost,
                    firstHopCost = firstCost,
                    secondHopCost = secondCost,
                    firstHopConfidence = firstQ,
                    secondHopConfidence = secondQ,
                    routeConfidence = routeQ
                )
            }
            .sortedWith(compareBy<TwoHopCandidate> { it.totalCost }.thenBy { it.relayId })
    }

    fun probeNextHop(graph: Graph, currentNodeId: String, destinationId: String): String? {
        return mmRoute(graph, currentNodeId, destinationId)?.nextHop
    }

}
