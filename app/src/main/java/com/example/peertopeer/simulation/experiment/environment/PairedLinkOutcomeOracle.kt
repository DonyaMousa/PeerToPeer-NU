package com.example.peertopeer.simulation.experiment.environment

/**
 * Protocol-independent deterministic Bernoulli oracle used by paired
 * simulation experiments.
 *
 * The important property is that an outcome does NOT depend on how many
 * random numbers a protocol consumed earlier in the run. For the same:
 *
 *   experiment salt + seed + physical link + packet index + attempt number
 *   + attempt time
 *
 * every protocol receives the same underlying U(0,1) sample.
 *
 * The actual success probability is still supplied by the scenario at the
 * time of the attempt, so protocols that reach the same link at different
 * times may face different probabilities, as they should.
 */
class PairedLinkOutcomeOracle(
    private val seed: Long,
    private val experimentSalt: String
) {

    init {
        require(experimentSalt.isNotBlank()) {
            "experimentSalt must not be blank"
        }
    }

    fun shouldSucceed(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        attemptNumber: Int,
        attemptTime: Long,
        successProbability: Double
    ): Boolean {

        require(fromNodeId.isNotBlank()) {
            "fromNodeId must not be blank"
        }
        require(toNodeId.isNotBlank()) {
            "toNodeId must not be blank"
        }
        require(messageId.isNotBlank()) {
            "messageId must not be blank"
        }
        require(attemptNumber > 0) {
            "attemptNumber must be greater than 0"
        }
        require(attemptTime >= 0L) {
            "attemptTime must be non-negative"
        }
        require(successProbability in 0.0..1.0) {
            "successProbability must be in [0, 1]"
        }

        if (successProbability <= 0.0) {
            return false
        }
        if (successProbability >= 1.0) {
            return true
        }

        val linkKey = canonicalUndirectedLink(
            fromNodeId,
            toNodeId
        )

        /*
         * Current experiment message IDs are protocol-specific, for example:
         *
         *   PFB2-CARBLE-SEED-2001-MSG-17
         *   PFB2-B0-SEED-2001-MSG-17
         *
         * Only the packet index is part of the physical traffic identity.
         */
        val packetKey = canonicalPacketKey(messageId)

        val key = buildString {
            append(experimentSalt)
            append('|')
            append(seed)
            append('|')
            append(linkKey)
            append('|')
            append(packetKey)
            append('|')
            append(attemptNumber)
            append('|')
            append(attemptTime)
        }

        val uniform = unitIntervalSample(key)

        return uniform < successProbability
    }

    internal fun sampleForTesting(
        fromNodeId: String,
        toNodeId: String,
        messageId: String,
        attemptNumber: Int,
        attemptTime: Long
    ): Double {
        val linkKey = canonicalUndirectedLink(
            fromNodeId,
            toNodeId
        )
        val packetKey = canonicalPacketKey(messageId)

        val key = buildString {
            append(experimentSalt)
            append('|')
            append(seed)
            append('|')
            append(linkKey)
            append('|')
            append(packetKey)
            append('|')
            append(attemptNumber)
            append('|')
            append(attemptTime)
        }

        return unitIntervalSample(key)
    }

    private fun canonicalUndirectedLink(
        a: String,
        b: String
    ): String {
        return if (a <= b) {
            "$a<->$b"
        } else {
            "$b<->$a"
        }
    }

    private fun canonicalPacketKey(
        messageId: String
    ): String {
        val marker = "-MSG-"
        val index = messageId.lastIndexOf(marker)

        return if (index >= 0) {
            messageId.substring(
                index + marker.length
            )
        } else {
            /*
             * Fallback for callers that do not use the experiment MSG marker.
             * Such callers still receive deterministic behavior, but cross-
             * protocol pairing then requires them to use the same message ID.
             */
            messageId
        }
    }

    private fun unitIntervalSample(
        key: String
    ): Double {
        val hashed = fnv1a64(key)
        val mixed = splitMix64(hashed)

        // Keep the top 53 bits so conversion to Double is exact.
        val mantissa = mixed ushr 11

        return mantissa.toDouble() /
                9_007_199_254_740_992.0 // 2^53
    }

    private fun fnv1a64(
        value: String
    ): Long {
        var hash = -3_750_763_034_362_895_579L
        val prime = 1_099_511_628_211L

        value.forEach { ch ->
            hash = hash xor ch.code.toLong()
            hash *= prime
        }

        return hash
    }

    private fun splitMix64(
        input: Long
    ): Long {
        var z = input - 7_046_029_254_386_353_131L
        z = (z xor (z ushr 30)) *
                -4_658_895_280_553_007_687L
        z = (z xor (z ushr 27)) *
                -7_723_592_293_110_705_685L
        return z xor (z ushr 31)
    }
}
