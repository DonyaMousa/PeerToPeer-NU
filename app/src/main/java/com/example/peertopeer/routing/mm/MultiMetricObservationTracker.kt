package com.example.peertopeer.routing.mm

class MultiMetricObservationTracker(

    private val stateStore:
    MultiMetricStateStore,

    private val reliabilityWindowSize: Int = 20,

    private val delayWindowSize: Int = 20,

    private val delayReference: Double = 10.0,

    private val instabilityReference: Int = 5

) {

    init {

        require(
            reliabilityWindowSize > 0
        )

        require(
            delayWindowSize > 0
        )

        require(
            delayReference > 0.0
        )

        require(
            instabilityReference > 0
        )
    }

    private data class MutableObservation(

        val recentAttemptResults:
        ArrayDeque<Boolean> =
            ArrayDeque(),

        val recentDelays:
        ArrayDeque<Double> =
            ArrayDeque(),

        var queueOccupancy:
        Int = 0,

        var queueCapacity:
        Int = 1,

        /*
         * Bounded count of recent topology-change
         * evidence.
         *
         * It saturates at instabilityReference.
         */
        var recentLinkChanges:
        Int = 0,

        var energyPenaltyNormalized:
        Double = 0.0
    )

    private val observations =
        mutableMapOf<
                Pair<String, String>,
                MutableObservation
                >()

    // =====================================================
    // TRANSMISSION
    // =====================================================

    fun observeTransmission(
        fromNodeId: String,
        toNodeId: String,
        success: Boolean,
        observedDelay: Double? = null
    ) {

        val observation =
            getOrCreateObservation(
                fromNodeId,
                toNodeId
            )

        observation
            .recentAttemptResults
            .addLast(
                success
            )

        while (
            observation
                .recentAttemptResults
                .size >
            reliabilityWindowSize
        ) {

            observation
                .recentAttemptResults
                .removeFirst()
        }

        if (
            observedDelay != null &&
            observedDelay >= 0.0
        ) {

            observation
                .recentDelays
                .addLast(
                    observedDelay
                )

            while (
                observation
                    .recentDelays
                    .size >
                delayWindowSize
            ) {

                observation
                    .recentDelays
                    .removeFirst()
            }
        }

        publish(
            fromNodeId,
            toNodeId,
            observation
        )
    }

    // =====================================================
    // QUEUE
    // =====================================================

    fun observeQueue(
        nodeId: String,
        queueOccupancy: Int,
        queueCapacity: Int
    ) {

        require(
            queueCapacity > 0
        )

        val boundedOccupancy =
            queueOccupancy.coerceIn(
                0,
                queueCapacity
            )

        /*
         * Queue pressure belongs to the receiving /
         * forwarding node.
         *
         * Therefore every known incoming edge toward this
         * node sees the same queue pressure.
         */
        observations.forEach {
                (key, observation) ->

            if (
                key.second ==
                nodeId
            ) {

                observation.queueOccupancy =
                    boundedOccupancy

                observation.queueCapacity =
                    queueCapacity

                publish(
                    key.first,
                    key.second,
                    observation
                )
            }
        }
    }

    // =====================================================
    // TOPOLOGY
    // =====================================================

    fun observeTopologyChange(
        fromNodeId: String,
        toNodeId: String
    ) {

        incrementInstability(
            fromNodeId,
            toNodeId
        )

        incrementInstability(
            toNodeId,
            fromNodeId
        )
    }

    private fun incrementInstability(
        fromNodeId: String,
        toNodeId: String
    ) {

        val observation =
            getOrCreateObservation(
                fromNodeId,
                toNodeId
            )

        observation.recentLinkChanges =
            (
                    observation.recentLinkChanges +
                            1
                    )
                .coerceAtMost(
                    instabilityReference
                )

        publish(
            fromNodeId,
            toNodeId,
            observation
        )
    }

    /*
     * Explicit decay step.
     *
     * Later the runner can call this periodically if we
     * want instability evidence to fade over time.
     */
    fun decayInstability() {

        observations.forEach {
                (key, observation) ->

            if (
                observation.recentLinkChanges >
                0
            ) {

                observation.recentLinkChanges--

                publish(
                    key.first,
                    key.second,
                    observation
                )
            }
        }
    }

    // =====================================================
    // EDGE REGISTRATION
    // =====================================================

    fun registerEdge(
        fromNodeId: String,
        toNodeId: String,
        queueCapacity: Int
    ) {

        require(
            fromNodeId.isNotBlank()
        )

        require(
            toNodeId.isNotBlank()
        )

        require(
            queueCapacity > 0
        )

        val observation =
            getOrCreateObservation(
                fromNodeId,
                toNodeId
            )

        observation.queueCapacity =
            queueCapacity

        publish(
            fromNodeId,
            toNodeId,
            observation
        )
    }

    // =====================================================
    // RESOURCE PENALTY
    // =====================================================

    fun updateEnergyPenalty(
        fromNodeId: String,
        toNodeId: String,
        normalizedPenalty: Double
    ) {

        require(
            normalizedPenalty in 0.0..1.0
        )

        val observation =
            getOrCreateObservation(
                fromNodeId,
                toNodeId
            )

        observation.energyPenaltyNormalized =
            normalizedPenalty

        publish(
            fromNodeId,
            toNodeId,
            observation
        )
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private fun getOrCreateObservation(
        fromNodeId: String,
        toNodeId: String
    ): MutableObservation {

        val key =
            fromNodeId to
                    toNodeId

        return observations
            .getOrPut(
                key
            ) {
                MutableObservation()
            }
    }

    // =====================================================
    // PUBLISH
    // =====================================================

    private fun publish(
        fromNodeId: String,
        toNodeId: String,
        observation: MutableObservation
    ) {

        val attempts =
            observation
                .recentAttemptResults

        val successRate =
            if (
                attempts.isEmpty()
            ) {

                1.0

            } else {

                attempts.count {
                    it
                }
                    .toDouble() /
                        attempts.size
                            .toDouble()
            }

        val observedDelay =
            if (
                observation
                    .recentDelays
                    .isEmpty()
            ) {

                1.0

            } else {

                observation
                    .recentDelays
                    .average()
            }

        stateStore.update(
            MultiMetricLinkState(
                fromNodeId =
                    fromNodeId,

                toNodeId =
                    toNodeId,

                successRate =
                    successRate,

                observedDelay =
                    observedDelay,

                delayReference =
                    delayReference,

                queueOccupancy =
                    observation
                        .queueOccupancy,

                queueCapacity =
                    observation
                        .queueCapacity,

                recentLinkChanges =
                    observation
                        .recentLinkChanges,

                instabilityReference =
                    instabilityReference,

                energyPenaltyNormalized =
                    observation
                        .energyPenaltyNormalized
            )
        )
    }
}