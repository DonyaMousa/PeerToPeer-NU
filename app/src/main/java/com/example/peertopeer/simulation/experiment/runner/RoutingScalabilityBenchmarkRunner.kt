package com.example.peertopeer.simulation.experiment.runner

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.routing.DijkstraEngine
import com.example.peertopeer.routing.carble.CarbleBackupCandidateFactory
import com.example.peertopeer.routing.carble.CarbleBackupSelector
import com.example.peertopeer.routing.carble.CarbleRouteDecision
import com.example.peertopeer.routing.carble.CarbleRouteEvaluator
import com.example.peertopeer.routing.hybrid.TwoRegimeFallbackPolicy
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteDecision
import com.example.peertopeer.routing.hybrid.TwoRegimeRouteEvaluator
import com.example.peertopeer.routing.mm.MultiMetricObservationTracker
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.simulation.B0DynamicRouteProvider
import com.example.peertopeer.simulation.CarbleRouteProvider
import com.example.peertopeer.simulation.MMRouteProvider
import com.example.peertopeer.simulation.TwoRegimeRouteProvider
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CARBLE COMPUTATIONAL SCALABILITY BENCHMARK v1
 *
 * Purpose:
 * Measure CPU-side fresh routing/controller decision latency as
 * graph size and graph density increase.
 *
 * IMPORTANT SCOPE
 * ---------------------------------------------------------
 * This is NOT packet-delivery latency and is NOT simulated BLE
 * time. It measures only the synchronous routing/controller
 * decision executed on the JVM.
 *
 * Timed work:
 *
 * B0:
 *   fresh B0 route-provider request with an empty route cache
 *   -> Dijkstra calculation
 *
 * MM:
 *   fresh MM route calculation
 *
 * 2RH:
 *   fresh MM route calculation
 *   + current-hop confidence classification
 *
 * CARBLE:
 *   fresh MM route calculation
 *   + Qcurrent/Qroute evaluation
 *   + CARBLE regime/action decision
 *
 * Provider construction, graph construction, state registration,
 * random-pair generation, and CSV writing are OUTSIDE the timed
 * interval.
 *
 * Each measured row represents one independent graph seed.
 * Per-decision timings are summarized inside that seed so later
 * statistical analysis can correctly use seed/run as the unit
 * rather than treating 500 decisions as 500 independent runs.
 */
class RoutingScalabilityBenchmarkRunner(
    private val warmupDecisions: Int = 100,
    private val measuredDecisions: Int = 500,
    private val queueCapacity: Int = 20,
    private val hysteresisFraction: Double = 0.05
) {

    init {
        require(warmupDecisions > 0)
        require(measuredDecisions > 0)
        require(queueCapacity > 0)
        require(hysteresisFraction in 0.0..1.0)
    }

    enum class Protocol {
        B0,
        MM,
        TWO_RH,
        CARBLE
    }

    enum class Topology {
        SPARSE,
        MODERATE
    }

    data class BenchmarkResult(
        val protocol: Protocol,
        val topology: Topology,
        val nodeCount: Int,
        val seed: Long,
        val undirectedEdgeCount: Int,
        val warmupDecisions: Int,
        val measuredDecisions: Int,
        val pathFoundCount: Int,
        val meanPathHops: Double,
        val meanLatencyNs: Double,
        val medianLatencyNs: Double,
        val p95LatencyNs: Double,
        val sampleSdLatencyNs: Double,
        val minLatencyNs: Long,
        val maxLatencyNs: Long
    ) {
        val meanLatencyUs: Double
            get() = meanLatencyNs / 1_000.0

        val medianLatencyUs: Double
            get() = medianLatencyNs / 1_000.0

        val p95LatencyUs: Double
            get() = p95LatencyNs / 1_000.0
    }

    private data class NodePair(
        val sourceId: String,
        val destinationId: String
    )

    private data class GraphFixture(
        val graph: Graph,
        val stateStore: MultiMetricStateStore,
        val undirectedEdgeCount: Int
    )

    private data class DecisionOutcome(
        val pathFound: Boolean,
        val pathHops: Int
    )

    /**
     * Final frozen matrix:
     *
     * 4 protocols
     * x 2 topology densities
     * x 5 node counts
     * x 30 graph seeds
     *
     * = 1200 independent benchmark rows.
     */
    fun runAll(
        seeds: List<Long> = (1L..30L).toList(),
        nodeCounts: List<Int> =
            listOf(
                10,
                25,
                50,
                100,
                200
            )
    ): List<BenchmarkResult> {

        require(seeds.isNotEmpty())
        require(nodeCounts.isNotEmpty())
        require(nodeCounts.all { it >= 2 })

        val results =
            mutableListOf<BenchmarkResult>()

        Topology.entries.forEach { topology ->

            nodeCounts.forEach { nodeCount ->

                seeds.forEach { seed ->

                    /*
                     * Same deterministic graph fixture and
                     * source/destination sequence are reused
                     * across all four protocol comparisons.
                     */
                    val fixture =
                        createFixture(
                            topology = topology,
                            nodeCount = nodeCount,
                            seed = seed
                        )

                    val pairs =
                        createNodePairs(
                            nodeCount = nodeCount,
                            topology = topology,
                            seed = seed,
                            count =
                                warmupDecisions +
                                        measuredDecisions
                        )

                    /*
                     * Counterbalance protocol execution order
                     * across cases to reduce systematic order
                     * effects from JIT/CPU drift.
                     */
                    val protocolOrder =
                        Protocol.entries
                            .toMutableList()
                            .also {
                                it.shuffle(
                                    Random(
                                        protocolOrderSeed(
                                            topology =
                                                topology,
                                            nodeCount =
                                                nodeCount,
                                            seed =
                                                seed
                                        )
                                    )
                                )
                            }

                    protocolOrder.forEach { protocol ->

                        results +=
                            benchmarkProtocol(
                                protocol = protocol,
                                topology = topology,
                                nodeCount = nodeCount,
                                seed = seed,
                                fixture = fixture,
                                pairs = pairs
                            )
                    }
                }
            }
        }

        return results.sortedWith(
            compareBy<BenchmarkResult>(
                { it.topology.ordinal },
                { it.nodeCount },
                { it.seed },
                { it.protocol.ordinal }
            )
        )
    }

    private fun benchmarkProtocol(
        protocol: Protocol,
        topology: Topology,
        nodeCount: Int,
        seed: Long,
        fixture: GraphFixture,
        pairs: List<NodePair>
    ): BenchmarkResult {

        require(
            pairs.size ==
                    warmupDecisions +
                    measuredDecisions
        )

        /*
         * Common routing instrumentation is enabled for all
         * protocols so route-request/found bookkeeping is not
         * selectively removed from one implementation.
         *
         * The recorder itself is case-local and is discarded
         * after this benchmark row.
         */
        val runId =
            "SCALABILITY-${topology.name}-N$nodeCount-" +
                    "${protocol.name}-SEED-$seed"

        val recorder =
            ExperimentRecorder(runId)

        val instrumentation =
            RecorderInstrumentation(recorder)

        // =================================================
        // WARM-UP
        // =================================================

        var warmupChecksum =
            0L

        repeat(warmupDecisions) { index ->

            val pair =
                pairs[index]

            val messageId =
                "$runId-WARMUP-$index"

            val outcome =
                executeFreshDecision(
                    protocol = protocol,
                    graph = fixture.graph,
                    stateStore =
                        fixture.stateStore,
                    instrumentation =
                        instrumentation,
                    runId =
                        runId,
                    pair = pair,
                    messageId = messageId,
                    measure = false
                ).second

            warmupChecksum +=
                if (outcome.pathFound) {
                    outcome.pathHops.toLong() + 1L
                } else {
                    1L
                }
        }

        /*
         * Read the checksum so warm-up work is observably
         * consumed by the benchmark method.
         */
        require(warmupChecksum > 0L)

        // =================================================
        // MEASURED DECISIONS
        // =================================================

        val latencies =
            LongArray(
                measuredDecisions
            )

        val pathHops =
            IntArray(
                measuredDecisions
            )

        var pathFoundCount =
            0

        repeat(measuredDecisions) { measuredIndex ->

            val pair =
                pairs[
                    warmupDecisions +
                            measuredIndex
                ]

            val messageId =
                "$runId-MEASURED-$measuredIndex"

            val (
                elapsedNs,
                outcome
            ) =
                executeFreshDecision(
                    protocol = protocol,
                    graph = fixture.graph,
                    stateStore =
                        fixture.stateStore,
                    instrumentation =
                        instrumentation,
                    runId =
                        runId,
                    pair = pair,
                    messageId = messageId,
                    measure = true
                )

            require(
                elapsedNs > 0L
            ) {
                "System.nanoTime() produced a non-positive " +
                        "elapsed interval for $runId."
            }

            latencies[
                measuredIndex
            ] =
                elapsedNs

            pathHops[
                measuredIndex
            ] =
                outcome.pathHops

            if (
                outcome.pathFound
            ) {
                pathFoundCount++
            }
        }

        val successfulPathHops =
            pathHops
                .filter {
                    it >= 0
                }

        val meanPathHops =
            if (
                successfulPathHops.isEmpty()
            ) {
                0.0
            } else {
                successfulPathHops
                    .average()
            }

        val sorted =
            latencies
                .sorted()

        val mean =
            latencies
                .map {
                    it.toDouble()
                }
                .average()

        val median =
            median(
                sorted
            )

        val p95 =
            percentileNearestRank(
                sorted =
                    sorted,
                probability =
                    0.95
            )

        val sampleSd =
            sampleStandardDeviation(
                values =
                    latencies,
                mean =
                    mean
            )

        return BenchmarkResult(
            protocol = protocol,
            topology = topology,
            nodeCount = nodeCount,
            seed = seed,
            undirectedEdgeCount =
                fixture.undirectedEdgeCount,
            warmupDecisions =
                warmupDecisions,
            measuredDecisions =
                measuredDecisions,
            pathFoundCount =
                pathFoundCount,
            meanPathHops =
                meanPathHops,
            meanLatencyNs =
                mean,
            medianLatencyNs =
                median,
            p95LatencyNs =
                p95,
            sampleSdLatencyNs =
                sampleSd,
            minLatencyNs =
                sorted.first(),
            maxLatencyNs =
                sorted.last()
        )
    }

    /**
     * Returns:
     *
     * Pair(elapsedNanoseconds, decisionOutcome)
     *
     * Provider construction occurs BEFORE startNs.
     */
    private fun executeFreshDecision(
        protocol: Protocol,
        graph: Graph,
        stateStore: MultiMetricStateStore,
        instrumentation: RecorderInstrumentation,
        runId: String,
        pair: NodePair,
        messageId: String,
        measure: Boolean
    ): Pair<Long, DecisionOutcome> {

        return when (protocol) {

            // =================================================
            // B0 — FRESH CACHE MISS + DIJKSTRA
            // =================================================

            Protocol.B0 -> {

                /*
                 * A fresh provider is intentional.
                 *
                 * B0 normally caches routes by topology
                 * version. Reusing one provider would mostly
                 * benchmark cache hits while MM intentionally
                 * recalculates on every request.
                 *
                 * Creating the provider before startNs keeps
                 * construction outside the timed interval while
                 * guaranteeing the timed request is a fresh B0
                 * routing calculation.
                 */
                val provider =
                    B0DynamicRouteProvider(
                        graph = graph,
                        routingEngine =
                            DijkstraEngine(),
                        runId =
                            runId,
                        instrumentation =
                            instrumentation,
                        timeProvider = {
                            0L
                        }
                    )

                val startNs =
                    if (measure) {
                        System.nanoTime()
                    } else {
                        0L
                    }

                val path =
                    provider.findPath(
                        currentNodeId =
                            pair.sourceId,
                        destinationId =
                            pair.destinationId,
                        messageId =
                            messageId
                    )

                val elapsedNs =
                    if (measure) {
                        System.nanoTime() -
                                startNs
                    } else {
                        0L
                    }

                elapsedNs to
                        pathOutcome(
                            path
                        )
            }

            // =================================================
            // MM — FRESH MULTI-METRIC ROUTE
            // =================================================

            Protocol.MM -> {

                val provider =
                    createFreshMMProvider(
                        graph =
                            graph,
                        stateStore =
                            stateStore,
                        instrumentation =
                            instrumentation,
                        runId =
                            runId
                    )

                val startNs =
                    if (measure) {
                        System.nanoTime()
                    } else {
                        0L
                    }

                val path =
                    provider.findPath(
                        currentNodeId =
                            pair.sourceId,
                        destinationId =
                            pair.destinationId,
                        messageId =
                            messageId
                    )

                val elapsedNs =
                    if (measure) {
                        System.nanoTime() -
                                startNs
                    } else {
                        0L
                    }

                elapsedNs to
                        pathOutcome(
                            path
                        )
            }

            // =================================================
            // 2RH — MM + BINARY CONFIDENCE CLASSIFICATION
            // =================================================

            Protocol.TWO_RH -> {

                val mm =
                    createFreshMMProvider(
                        graph =
                            graph,
                        stateStore =
                            stateStore,
                        instrumentation =
                            instrumentation,
                        runId =
                            runId
                    )

                val provider =
                    TwoRegimeRouteProvider(
                        mmRouteProvider =
                            mm,
                        routeEvaluator =
                            TwoRegimeRouteEvaluator(
                                stateStore =
                                    stateStore
                            ),
                        fallbackPolicy =
                            TwoRegimeFallbackPolicy(
                                maxReevaluations =
                                    3,
                                reevaluationDelay =
                                    5L
                            )
                    )

                val startNs =
                    if (measure) {
                        System.nanoTime()
                    } else {
                        0L
                    }

                val decision =
                    provider.decide(
                        currentNodeId =
                            pair.sourceId,
                        destinationId =
                            pair.destinationId,
                        messageId =
                            messageId
                    )

                val elapsedNs =
                    if (measure) {
                        System.nanoTime() -
                                startNs
                    } else {
                        0L
                    }

                elapsedNs to
                        twoRegimeOutcome(
                            decision
                        )
            }

            // =================================================
            // CARBLE — MM + Q + REGIME/ACTION DECISION
            // =================================================

            Protocol.CARBLE -> {

                val mm =
                    createFreshMMProvider(
                        graph =
                            graph,
                        stateStore =
                            stateStore,
                        instrumentation =
                            instrumentation,
                        runId =
                            runId
                    )

                val provider =
                    CarbleRouteProvider(
                        mmRouteProvider =
                            mm,
                        routeEvaluator =
                            CarbleRouteEvaluator(
                                stateStore
                            ),
                        candidateFactory =
                            CarbleBackupCandidateFactory(
                                graph,
                                stateStore
                            ),
                        backupSelector =
                            CarbleBackupSelector(),
                        fallbackPolicy =
                            TwoRegimeFallbackPolicy(
                                maxReevaluations =
                                    3,
                                reevaluationDelay =
                                    5L
                            ),
                        retryDelay =
                            1L,
                        runId =
                            runId,
                        timeProvider = {
                            0L
                        }
                    )

                val startNs =
                    if (measure) {
                        System.nanoTime()
                    } else {
                        0L
                    }

                val decision =
                    provider.decide(
                        currentNodeId =
                            pair.sourceId,
                        destinationId =
                            pair.destinationId,
                        messageId =
                            messageId
                    )

                val elapsedNs =
                    if (measure) {
                        System.nanoTime() -
                                startNs
                    } else {
                        0L
                    }

                elapsedNs to
                        carbleOutcome(
                            decision
                        )
            }
        }
    }

    private fun createFreshMMProvider(
        graph: Graph,
        stateStore: MultiMetricStateStore,
        instrumentation: RecorderInstrumentation,
        runId: String
    ): MMRouteProvider {

        return MMRouteProvider(
            graph = graph,
            stateStore = stateStore,
            runId =
                runId,
            instrumentation =
                instrumentation,
            timeProvider = {
                0L
            },
            hysteresisFraction =
                hysteresisFraction
        )
    }

    private fun pathOutcome(
        path: List<String>?
    ): DecisionOutcome {

        if (
            path == null
        ) {
            return DecisionOutcome(
                pathFound = false,
                pathHops = -1
            )
        }

        return DecisionOutcome(
            pathFound = true,
            pathHops =
                (path.size - 1)
                    .coerceAtLeast(
                        0
                    )
        )
    }

    private fun twoRegimeOutcome(
        decision:
        TwoRegimeRouteDecision
    ): DecisionOutcome {

        return when (decision) {

            is TwoRegimeRouteDecision
                .Forward ->
                pathOutcome(
                    decision.path
                )

            is TwoRegimeRouteDecision
                .Probe ->
                pathOutcome(
                    decision.path
                )

            is TwoRegimeRouteDecision
                .Carry ->
                DecisionOutcome(
                    pathFound = false,
                    pathHops = -1
                )

            is TwoRegimeRouteDecision
                .Drop ->
                DecisionOutcome(
                    pathFound = false,
                    pathHops = -1
                )
        }
    }

    private fun carbleOutcome(
        decision:
        CarbleRouteDecision
    ): DecisionOutcome {

        return when (decision) {

            is CarbleRouteDecision
                .Forward ->
                pathOutcome(
                    decision.path
                )

            is CarbleRouteDecision
                .ForwardWithFailover ->
                pathOutcome(
                    decision.primaryPath
                )

            is CarbleRouteDecision
                .ForwardWithDelayedBackup ->
                pathOutcome(
                    decision.primaryPath
                )

            is CarbleRouteDecision
                .Probe ->
                pathOutcome(
                    decision.path
                )

            is CarbleRouteDecision
                .Carry ->
                DecisionOutcome(
                    pathFound = false,
                    pathHops = -1
                )

            is CarbleRouteDecision
                .Drop ->
                DecisionOutcome(
                    pathFound = false,
                    pathHops = -1
                )
        }
    }

    // =====================================================
    // GRAPH FIXTURE
    // =====================================================

    private fun createFixture(
        topology: Topology,
        nodeCount: Int,
        seed: Long
    ): GraphFixture {

        val graph =
            Graph()

        repeat(nodeCount) { index ->

            val id =
                nodeId(
                    index
                )

            graph.addNode(
                Node(
                    nodeId =
                        id,
                    displayName =
                        id
                )
            )
        }

        /*
         * Undirected edge pairs are tracked explicitly so
         * target density does not depend on Graph.getEdges()
         * implementation details.
         */
        val edgePairs =
            linkedSetOf<Pair<Int, Int>>()

        val random =
            Random(
                graphSeed(
                    nodeCount =
                        nodeCount,
                    seed =
                        seed
                )
            )

        /*
         * Random recursive spanning tree.
         *
         * This guarantees every generated graph is connected
         * before density-specific edges are added.
         */
        for (
        child in 1 until
                nodeCount
        ) {

            val parent =
                random.nextInt(
                    child
                )

            addUndirectedEdge(
                graph =
                    graph,
                edgePairs =
                    edgePairs,
                a =
                    parent,
                b =
                    child
            )
        }

        val maximumEdges =
            nodeCount *
                    (nodeCount - 1) /
                    2

        val targetEdges =
            when (topology) {

                Topology.SPARSE ->
                    ceil(
                        nodeCount *
                                1.5
                    )
                        .toInt()

                Topology.MODERATE ->
                    nodeCount *
                            4
            }
                .coerceAtLeast(
                    nodeCount - 1
                )
                .coerceAtMost(
                    maximumEdges
                )

        while (
            edgePairs.size <
            targetEdges
        ) {

            val a =
                random.nextInt(
                    nodeCount
                )

            var b =
                random.nextInt(
                    nodeCount
                )

            while (
                b ==
                a
            ) {

                b =
                    random.nextInt(
                        nodeCount
                    )
            }

            addUndirectedEdge(
                graph =
                    graph,
                edgePairs =
                    edgePairs,
                a =
                    a,
                b =
                    b
            )
        }

        /*
         * Healthy bootstrap state:
         *
         * successRate=1,
         * no instability,
         * empty queues,
         * no energy penalty.
         *
         * This keeps the computational benchmark focused on
         * graph size/density and controller overhead rather
         * than mixing in a degradation experiment.
         */
        val stateStore =
            MultiMetricStateStore()

        val tracker =
            MultiMetricObservationTracker(
                stateStore =
                    stateStore,
                reliabilityWindowSize =
                    20,
                delayWindowSize =
                    20,
                delayReference =
                    10.0,
                instabilityReference =
                    5
            )

        edgePairs.forEach {
                (a, b) ->

            tracker.registerEdge(
                fromNodeId =
                    nodeId(a),
                toNodeId =
                    nodeId(b),
                queueCapacity =
                    queueCapacity
            )

            tracker.registerEdge(
                fromNodeId =
                    nodeId(b),
                toNodeId =
                    nodeId(a),
                queueCapacity =
                    queueCapacity
            )
        }

        return GraphFixture(
            graph = graph,
            stateStore =
                stateStore,
            undirectedEdgeCount =
                edgePairs.size
        )
    }

    private fun addUndirectedEdge(
        graph: Graph,
        edgePairs:
        MutableSet<Pair<Int, Int>>,
        a: Int,
        b: Int
    ) {

        require(
            a != b
        )

        val low =
            minOf(
                a,
                b
            )

        val high =
            maxOf(
                a,
                b
            )

        val key =
            low to
                    high

        if (
            !edgePairs.add(
                key
            )
        ) {
            return
        }

        /*
         * Unit weights intentionally align B0 shortest-hop
         * structure with the healthy bootstrap MM state as
         * closely as possible.
         */
        graph.addEdge(
            from =
                nodeId(low),
            to =
                nodeId(high),
            weight =
                1
        )
    }

    // =====================================================
    // SOURCE / DESTINATION PAIRS
    // =====================================================

    private fun createNodePairs(
        nodeCount: Int,
        topology: Topology,
        seed: Long,
        count: Int
    ): List<NodePair> {

        val random =
            Random(
                pairSeed(
                    topology =
                        topology,
                    nodeCount =
                        nodeCount,
                    seed =
                        seed
                )
            )

        return List(
            count
        ) {

            val source =
                random.nextInt(
                    nodeCount
                )

            var destination =
                random.nextInt(
                    nodeCount
                )

            while (
                destination ==
                source
            ) {

                destination =
                    random.nextInt(
                        nodeCount
                    )
            }

            NodePair(
                sourceId =
                    nodeId(source),
                destinationId =
                    nodeId(destination)
            )
        }
    }

    // =====================================================
    // DESCRIPTIVE HELPERS
    // =====================================================

    private fun median(
        sorted: List<Long>
    ): Double {

        require(
            sorted.isNotEmpty()
        )

        val size =
            sorted.size

        return if (
            size % 2 ==
            1
        ) {

            sorted[
                size / 2
            ]
                .toDouble()

        } else {

            (
                    sorted[
                        size / 2 - 1
                    ] +
                            sorted[
                                size / 2
                            ]
                    ) /
                    2.0
        }
    }

    private fun percentileNearestRank(
        sorted: List<Long>,
        probability: Double
    ): Double {

        require(
            sorted.isNotEmpty()
        )

        require(
            probability in
                    0.0..1.0
        )

        val rank =
            ceil(
                probability *
                        sorted.size
            )
                .toInt()
                .coerceIn(
                    1,
                    sorted.size
                )

        return sorted[
            rank - 1
        ]
            .toDouble()
    }

    private fun sampleStandardDeviation(
        values: LongArray,
        mean: Double
    ): Double {

        if (
            values.size <
            2
        ) {
            return 0.0
        }

        val sumSquares =
            values.sumOf { value ->

                val difference =
                    value.toDouble() -
                            mean

                difference *
                        difference
            }

        return sqrt(
            sumSquares /
                    (
                            values.size -
                                    1
                            )
        )
    }

    // =====================================================
    // CSV EXPORT
    // =====================================================

    fun exportCsv(
        results: List<BenchmarkResult>,
        outputDirectory: File
    ): File {

        require(
            results.isNotEmpty()
        )

        if (
            !outputDirectory.exists()
        ) {
            outputDirectory.mkdirs()
        }

        val outputFile =
            File(
                outputDirectory,
                "routing_scalability_runs.csv"
            )

        outputFile
            .bufferedWriter()
            .use { writer ->

                writer.appendLine(
                    "protocol,topology,nodeCount,seed," +
                            "undirectedEdgeCount," +
                            "warmupDecisions,measuredDecisions," +
                            "pathFoundCount,meanPathHops," +
                            "meanLatencyNs,medianLatencyNs," +
                            "p95LatencyNs,sampleSdLatencyNs," +
                            "minLatencyNs,maxLatencyNs," +
                            "meanLatencyUs,medianLatencyUs," +
                            "p95LatencyUs"
                )

                results.forEach { r ->

                    writer.appendLine(
                        listOf(
                            r.protocol,
                            r.topology,
                            r.nodeCount,
                            r.seed,
                            r.undirectedEdgeCount,
                            r.warmupDecisions,
                            r.measuredDecisions,
                            r.pathFoundCount,
                            r.meanPathHops,
                            r.meanLatencyNs,
                            r.medianLatencyNs,
                            r.p95LatencyNs,
                            r.sampleSdLatencyNs,
                            r.minLatencyNs,
                            r.maxLatencyNs,
                            r.meanLatencyUs,
                            r.medianLatencyUs,
                            r.p95LatencyUs
                        )
                            .joinToString(
                                ","
                            )
                    )
                }
            }

        return outputFile
    }

    // =====================================================
    // DETERMINISTIC SEEDS
    // =====================================================

    private fun graphSeed(
        nodeCount: Int,
        seed: Long
    ): Long {

        /*
         * Same graph RNG base for SPARSE and MODERATE.
         *
         * Therefore the sparse graph is a deterministic
         * subset of the moderate-density graph for the same
         * nodeCount/seed.
         */
        return 40_000_000L +
                seed *
                10_000L +
                nodeCount
    }

    private fun pairSeed(
        topology: Topology,
        nodeCount: Int,
        seed: Long
    ): Long {

        return 41_000_000L +
                topology.ordinal *
                1_000_000L +
                seed *
                10_000L +
                nodeCount
    }

    private fun protocolOrderSeed(
        topology: Topology,
        nodeCount: Int,
        seed: Long
    ): Long {

        return 42_000_000L +
                topology.ordinal *
                1_000_000L +
                seed *
                10_000L +
                nodeCount
    }

    private fun nodeId(
        index: Int
    ): String {

        return "N$index"
    }
}
