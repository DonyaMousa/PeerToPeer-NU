package com.example.peertopeer.network.forwarding

import android.os.SystemClock
import com.example.peertopeer.routing.carble.CarbleController
import com.example.peertopeer.routing.carble.CarbleSignalAdapter
import com.example.peertopeer.routing.mm.MultiMetricLinkState
import com.example.peertopeer.routing.mm.MultiMetricStateStore
import com.example.peertopeer.routing.mm.QueuePressure
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Converts live BLE observations to the unchanged CARBLE / 2BRH Q inputs.
 *
 * D is based on application HOP_ACKs, never on a successful Android GATT
 * write. B remains 1.0 because the simulation used a zero energy penalty and
 * this MVP is not a battery-aware routing experiment.
 */
class LinkMetricsStore {
    companion object {
        private const val FRESHNESS_FULL_MS = 5_000L
        private const val FRESHNESS_ZERO_MS = 30_000L
        private const val STABILITY_WINDOW_MS = 60_000L
        private const val INSTABILITY_EVENTS_FOR_ZERO = 5
        private const val RSSI_GOOD_DBM = -55
        private const val RSSI_BAD_DBM = -95
        private const val REAL_DELAY_REFERENCE_MS = 1_000.0
        private const val DELIVERY_WINDOW_SIZE = 20
        private const val RSSI_WINDOW_SIZE = 5
        private const val RTT_WINDOW_SIZE = 20
        private const val QUEUE_SERVICE_WINDOW_MS = 30_000L
        private const val QUEUE_DRAIN_HORIZON_MS = 5_000.0
        private const val MIN_REAL_DELIVERY_SAMPLES = 5
        private const val BOOTSTRAP_DELIVERY_PRIOR = 0.90
        /** Do not count one physical connect/reconnect more than once. */
        private const val LINK_CHANGE_DEBOUNCE_MS = 1_500L
        /** RSSI is noisy; one swing event per short observation period is enough. */
        private const val RSSI_SWING_DEBOUNCE_MS = 5_000L
    }

    data class ConfidenceSnapshot(
        val fromNodeId: String,
        val toNodeId: String,
        val deliverySuccess: Double,
        val freshness: Double,
        val stability: Double,
        val timeliness: Double,
        val signalReliability: Double,
        val resourceSuitability: Double,
        val confidence: Double,
        /** Filtered median RSSI used for S. */
        val rssiDbm: Int?,
        val rawRssiDbm: Int?,
        val rssiSampleCount: Int,
        val delayMs: Double,
        val medianAckRttMs: Double?,
        val medianGattWriteMs: Double?,
        val deliveryAttempts: Int,
        val hopAckSuccesses: Int,
        val freshnessAgeMs: Long?,
        val instabilityEvents: Int,
        val gattFailureCount: Int,
        val ackTimeoutCount: Int,
        val disconnectCount: Int,
        val queueOccupancy: Int,
        val queueCapacity: Int,
        val queueScaleK: Double,
        val queuePressure: Double,
        val measurementStatus: String
    )

    private enum class InstabilityType { GATT_FAILURE, ACK_TIMEOUT, LINK_CHANGE, RSSI_SWING }
    private data class InstabilityEvent(val elapsedMs: Long, val type: InstabilityType)
    private data class PendingHopAttempt(val from: String, val to: String, val startedElapsedMs: Long)

    private data class MutableMetrics(
        val hopReceiptSamples: ArrayDeque<Boolean> = ArrayDeque(),
        val ackRttSamples: ArrayDeque<Long> = ArrayDeque(),
        val gattWriteSamples: ArrayDeque<Long> = ArrayDeque(),
        val rssiSamples: ArrayDeque<Int> = ArrayDeque(),
        val instabilityEvents: ArrayDeque<InstabilityEvent> = ArrayDeque(),
        val successfulHopCompletionTimes: ArrayDeque<Long> = ArrayDeque(),
        var rawRssi: Int? = null,
        var queueOccupancy: Int = 0,
        var queueCapacity: Int = 10,
        var lastValidCommunicationElapsedMs: Long? = null,
        var lastLinkChangeElapsedMs: Long = 0L,
        var lastRssiSwingElapsedMs: Long = 0L
    )

    val stateStore = MultiMetricStateStore()

    private val metrics = mutableMapOf<Pair<String, String>, MutableMetrics>()
    private val pendingHopAttempts = mutableMapOf<String, PendingHopAttempt>()
    private val remoteSeen = mutableMapOf<Pair<String, String>, Long>()
    private val remoteOriginal = mutableMapOf<Pair<String, String>, MultiMetricLinkState>()
    private val remoteDetails = mutableMapOf<Pair<String, String>, RemoteDetails>()
    private val signalAdapter = CarbleSignalAdapter()
    private val controller = CarbleController()

    private data class RemoteDetails(
        val rawRssi: Int?,
        val filteredRssi: Int?,
        val rssiSamples: Int,
        val attempts: Int,
        val hopAckSuccesses: Int,
        val medianAckRttMs: Double?,
        val freshnessAgeMs: Long?,
        val instabilityEvents: Int,
        val gattFailures: Int,
        val ackTimeouts: Int,
        val disconnects: Int,
        val measurementStatus: String
    )

    @Synchronized
    fun observeRssi(from: String, to: String, rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        val m = state(from, to)
        val prior = filteredRssi(m)
        m.rawRssi = rssi
        appendWindow(m.rssiSamples, rssi, RSSI_WINDOW_SIZE)
        if (prior != null && abs(prior - rssi) >= 10 && now - m.lastRssiSwingElapsedMs >= RSSI_SWING_DEBOUNCE_MS) {
            m.lastRssiSwingElapsedMs = now
            recordInstability(m, now, InstabilityType.RSSI_SWING)
        }
        publish(from, to, m, now)
    }

    /** Valid application/control traffic refreshes F; RSSI advertisements do not. */
    @Synchronized
    fun observeValidCommunication(from: String, to: String) {
        val now = SystemClock.elapsedRealtime()
        val m = state(from, to)
        m.lastValidCommunicationElapsedMs = now
        publish(from, to, m, now)
    }

    /** Android write timing is exported, but never used as delivery success. */
    @Synchronized
    fun observeGattWrite(from: String, to: String, success: Boolean, delayMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val m = state(from, to)
        appendWindow(m.gattWriteSamples, delayMs.coerceAtLeast(1L), RTT_WINDOW_SIZE)
        if (!success) recordInstability(m, now, InstabilityType.GATT_FAILURE)
        publish(from, to, m, now)
    }

    /** Start timing a data/control hop that requires a HOP_ACK. */
    @Synchronized
    fun beginHopAttempt(messageId: String, from: String, to: String) {
        pendingHopAttempts[attemptKey(messageId, from, to)] = PendingHopAttempt(from, to, SystemClock.elapsedRealtime())
    }

    /** Completes D and ACK-delay evidence from the app-level receipt outcome. */
    @Synchronized
    fun completeHopAttempt(messageId: String, from: String, to: String, receiptReceived: Boolean) {
        val pending = pendingHopAttempts.remove(attemptKey(messageId, from, to)) ?: return
        val now = SystemClock.elapsedRealtime()
        val m = state(pending.from, pending.to)
        appendWindow(m.hopReceiptSamples, receiptReceived, DELIVERY_WINDOW_SIZE)
        if (receiptReceived) {
            appendWindow(m.ackRttSamples, (now - pending.startedElapsedMs).coerceAtLeast(1L), RTT_WINDOW_SIZE)
            m.successfulHopCompletionTimes.addLast(now)
            pruneHopCompletions(m, now)
            m.lastValidCommunicationElapsedMs = now
        } else {
            recordInstability(m, now, InstabilityType.ACK_TIMEOUT)
        }
        publish(pending.from, pending.to, m, now)
    }

    /** Removes an intentional parallel/backup attempt without recording it as a loss. */
    @Synchronized
    fun cancelHopAttempt(messageId: String, from: String, to: String) {
        pendingHopAttempts.remove(attemptKey(messageId, from, to))
    }

    @Synchronized
    fun observeQueue(from: String, to: String, occupancy: Int, capacity: Int) {
        val now = SystemClock.elapsedRealtime()
        val m = state(from, to)
        m.queueCapacity = capacity.coerceAtLeast(1)
        m.queueOccupancy = occupancy.coerceIn(0, m.queueCapacity)
        publish(from, to, m, now)
    }

    @Synchronized
    fun markLinkChange(from: String, to: String) {
        val now = SystemClock.elapsedRealtime()
        val m = state(from, to)
        if (now - m.lastLinkChangeElapsedMs < LINK_CHANGE_DEBOUNCE_MS) return
        m.lastLinkChangeElapsedMs = now
        recordInstability(m, now, InstabilityType.LINK_CHANGE)
        publish(from, to, m, now)
    }

    @Synchronized
    fun refreshAges() {
        val now = SystemClock.elapsedRealtime()
        metrics.forEach { (key, value) -> if (key !in remoteOriginal) publish(key.first, key.second, value, now) }
        remoteOriginal.forEach { (key, original) ->
            val age = now - (remoteSeen[key] ?: now)
            stateStore.update(original.copy(freshnessNormalized = ((original.freshnessNormalized ?: 0.0) * (1.0 - age / FRESHNESS_ZERO_MS.toDouble())).coerceIn(0.0, 1.0)))
        }
    }

    @Synchronized
    fun ensure(from: String, to: String) {
        if (stateStore.get(from, to) != null) return
        publish(from, to, state(from, to), SystemClock.elapsedRealtime())
    }

    /** Sender-authoritative direct-link telemetry used by topology gossip. */
    @Synchronized
    fun exportDirectMetrics(fromNodeId: String, directPeerIds: Collection<String>): JSONArray {
        refreshAges()
        return JSONArray().also { array ->
            directPeerIds.forEach { peerId ->
                val snapshot = snapshot(fromNodeId, peerId) ?: return@forEach
                array.put(
                    JSONObject()
                        .put("from", fromNodeId)
                        .put("to", peerId)
                        .put("success", snapshot.deliverySuccess)
                        .put("delay", snapshot.delayMs)
                        .put("delayRef", REAL_DELAY_REFERENCE_MS)
                        .put("queue", snapshot.queueOccupancy)
                        .put("queueCap", snapshot.queueCapacity)
                        .put("queueScaleK", snapshot.queueScaleK)
                        .put("queuePressure", snapshot.queuePressure)
                        .put("changes", snapshot.instabilityEvents)
                        .put("instabilityRef", INSTABILITY_EVENTS_FOR_ZERO)
                        .put("energy", 0.0)
                        .put("freshness", snapshot.freshness)
                        .put("signal", snapshot.signalReliability)
                        .put("rawRssi", snapshot.rawRssiDbm ?: JSONObject.NULL)
                        .put("filteredRssi", snapshot.rssiDbm ?: JSONObject.NULL)
                        .put("rssiSamples", snapshot.rssiSampleCount)
                        .put("attempts", snapshot.deliveryAttempts)
                        .put("hopAcks", snapshot.hopAckSuccesses)
                        .put("ackRtt", snapshot.medianAckRttMs ?: JSONObject.NULL)
                        .put("freshnessAge", snapshot.freshnessAgeMs ?: JSONObject.NULL)
                        .put("gattFailures", snapshot.gattFailureCount)
                        .put("ackTimeouts", snapshot.ackTimeoutCount)
                        .put("disconnects", snapshot.disconnectCount)
                        .put("measurementStatus", snapshot.measurementStatus)
                )
            }
        }
    }

    /** Accept only direct-link telemetry published by the measured edge owner. */
    @Synchronized
    fun mergeRemoteMetrics(payload: String, sourceNodeId: String) {
        runCatching {
            val root = JSONObject(payload)
            val array = root.optJSONArray("linkMetrics") ?: return
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val from = item.optString("from")
                val to = item.optString("to")
                if (from != sourceNodeId || to.isBlank()) continue
                val queueCapacity = item.optInt("queueCap", 10).coerceAtLeast(1)
                val queueOccupancy = item.optInt("queue", 0).coerceIn(0, queueCapacity)
                val remoteState = MultiMetricLinkState(
                    fromNodeId = from,
                    toNodeId = to,
                    successRate = item.optDouble("success", BOOTSTRAP_DELIVERY_PRIOR).coerceIn(0.0, 1.0),
                    observedDelay = item.optDouble("delay", REAL_DELAY_REFERENCE_MS).coerceAtLeast(1.0),
                    delayReference = item.optDouble("delayRef", REAL_DELAY_REFERENCE_MS).coerceAtLeast(1.0),
                    queueOccupancy = queueOccupancy,
                    queueCapacity = queueCapacity,
                    adaptiveQueueScaleK = item.optDouble("queueScaleK", 1.0).coerceAtLeast(0.001),
                    recentLinkChanges = item.optInt("changes", 0).coerceAtLeast(0),
                    instabilityReference = item.optInt("instabilityRef", INSTABILITY_EVENTS_FOR_ZERO).coerceAtLeast(1),
                    energyPenaltyNormalized = item.optDouble("energy", 0.0).coerceIn(0.0, 1.0),
                    freshnessNormalized = item.optDouble("freshness", 0.0).coerceIn(0.0, 1.0),
                    signalReliabilityNormalized = item.optDouble("signal", 0.0).coerceIn(0.0, 1.0)
                )
                val key = from to to
                remoteSeen[key] = SystemClock.elapsedRealtime()
                remoteOriginal[key] = remoteState
                remoteDetails[key] = RemoteDetails(
                    rawRssi = item.optIntOrNull("rawRssi"),
                    filteredRssi = item.optIntOrNull("filteredRssi"),
                    rssiSamples = item.optInt("rssiSamples", 0).coerceAtLeast(0),
                    attempts = item.optInt("attempts", 0).coerceAtLeast(0),
                    hopAckSuccesses = item.optInt("hopAcks", 0).coerceAtLeast(0),
                    medianAckRttMs = item.optDoubleOrNull("ackRtt"),
                    freshnessAgeMs = item.optLongOrNull("freshnessAge"),
                    instabilityEvents = item.optInt("changes", 0).coerceAtLeast(0),
                    gattFailures = item.optInt("gattFailures", 0).coerceAtLeast(0),
                    ackTimeouts = item.optInt("ackTimeouts", 0).coerceAtLeast(0),
                    disconnects = item.optInt("disconnects", 0).coerceAtLeast(0),
                    measurementStatus = item.optString("measurementStatus", "WARMING_UP")
                )
                stateStore.update(remoteState)
            }
        }
    }

    @Synchronized
    fun snapshot(from: String, to: String): ConfidenceSnapshot? {
        refreshAges()
        val state = stateStore.get(from, to) ?: return null
        val signals = signalAdapter.fromLinkState(state)
        val m = metrics[from to to]
        val remote = remoteDetails[from to to]
        val now = SystemClock.elapsedRealtime()
        val rssi = m?.let(::filteredRssi) ?: remote?.filteredRssi
        val rawRssi = m?.rawRssi ?: remote?.rawRssi
        val samples = m?.rssiSamples?.size ?: remote?.rssiSamples ?: 0
        val attempts = m?.hopReceiptSamples?.size ?: remote?.attempts ?: 0
        val successes = m?.hopReceiptSamples?.count { it } ?: remote?.hopAckSuccesses ?: 0
        val medianAck = m?.let { median(it.ackRttSamples) } ?: remote?.medianAckRttMs
        val medianGatt = m?.let { median(it.gattWriteSamples) }
        val age = m?.lastValidCommunicationElapsedMs?.let { (now - it).coerceAtLeast(0L) } ?: remote?.freshnessAgeMs
        val events = m?.let { eventCount(it, now) } ?: remote?.instabilityEvents ?: 0
        val gattFailures = m?.let { eventCount(it, now, InstabilityType.GATT_FAILURE) } ?: remote?.gattFailures ?: 0
        val ackTimeouts = m?.let { eventCount(it, now, InstabilityType.ACK_TIMEOUT) } ?: remote?.ackTimeouts ?: 0
        val disconnects = m?.let { eventCount(it, now, InstabilityType.LINK_CHANGE) } ?: remote?.disconnects ?: 0
        val status = if (attempts >= MIN_REAL_DELIVERY_SAMPLES && samples > 0 && age != null) "READY" else remote?.measurementStatus ?: "WARMING_UP"
        return ConfidenceSnapshot(
            fromNodeId = from,
            toNodeId = to,
            deliverySuccess = signals.deliverySuccess,
            freshness = signals.freshness,
            stability = signals.stability,
            timeliness = signals.timeliness,
            signalReliability = signals.signalReliability,
            resourceSuitability = signals.resourceSuitability,
            confidence = controller.calculateConfidence(signals),
            rssiDbm = rssi,
            rawRssiDbm = rawRssi,
            rssiSampleCount = samples,
            delayMs = state.observedDelay,
            medianAckRttMs = medianAck,
            medianGattWriteMs = medianGatt,
            deliveryAttempts = attempts,
            hopAckSuccesses = successes,
            freshnessAgeMs = age,
            instabilityEvents = events,
            gattFailureCount = gattFailures,
            ackTimeoutCount = ackTimeouts,
            disconnectCount = disconnects,
            queueOccupancy = state.queueOccupancy,
            queueCapacity = state.queueCapacity,
            queueScaleK = state.adaptiveQueueScaleK ?: 1.0,
            queuePressure = QueuePressure.normalized(state),
            measurementStatus = status
        )
    }

    private fun state(from: String, to: String): MutableMetrics = metrics.getOrPut(from to to) { MutableMetrics() }

    private fun publish(from: String, to: String, m: MutableMetrics, now: Long) {
        pruneInstability(m, now)
        pruneHopCompletions(m, now)
        val attempts = m.hopReceiptSamples.size
        val delivery = if (attempts == 0) BOOTSTRAP_DELIVERY_PRIOR else m.hopReceiptSamples.count { it }.toDouble() / attempts.toDouble()
        val ackDelay = median(m.ackRttSamples) ?: REAL_DELAY_REFERENCE_MS
        stateStore.update(
            MultiMetricLinkState(
                fromNodeId = from,
                toNodeId = to,
                successRate = delivery.coerceIn(0.0, 1.0),
                observedDelay = ackDelay.coerceAtLeast(1.0),
                delayReference = REAL_DELAY_REFERENCE_MS,
                queueOccupancy = m.queueOccupancy,
                queueCapacity = m.queueCapacity,
                adaptiveQueueScaleK = adaptiveQueueScale(m, now),
                recentLinkChanges = m.instabilityEvents.size.coerceAtMost(INSTABILITY_EVENTS_FOR_ZERO),
                instabilityReference = INSTABILITY_EVENTS_FOR_ZERO,
                energyPenaltyNormalized = 0.0,
                freshnessNormalized = freshness(m.lastValidCommunicationElapsedMs, now),
                signalReliabilityNormalized = rssiSuitability(filteredRssi(m))
            )
        )
    }

    private fun recordInstability(m: MutableMetrics, now: Long, type: InstabilityType) {
        m.instabilityEvents.addLast(InstabilityEvent(now, type))
        pruneInstability(m, now)
    }

    private fun pruneInstability(m: MutableMetrics, now: Long) {
        while (m.instabilityEvents.isNotEmpty() && now - m.instabilityEvents.peekFirst().elapsedMs > STABILITY_WINDOW_MS) m.instabilityEvents.removeFirst()
    }

    /**
     * k is the number of packets this link has recently proved it can drain
     * during the next five seconds. This keeps the routing signal tied to
     * measured traffic instead of an arbitrary buffer maximum.
     */
    private fun adaptiveQueueScale(m: MutableMetrics, now: Long): Double {
        pruneHopCompletions(m, now)
        if (m.successfulHopCompletionTimes.size < 2) return 1.0
        val first = m.successfulHopCompletionTimes.peekFirst()
        val last = m.successfulHopCompletionTimes.peekLast()
        val durationMs = (last - first).coerceAtLeast(1L)
        val completionsPerSecond = (m.successfulHopCompletionTimes.size - 1) * 1_000.0 / durationMs
        return (completionsPerSecond * QUEUE_DRAIN_HORIZON_MS / 1_000.0).coerceAtLeast(1.0)
    }

    private fun pruneHopCompletions(m: MutableMetrics, now: Long) {
        while (m.successfulHopCompletionTimes.isNotEmpty() &&
            now - m.successfulHopCompletionTimes.peekFirst() > QUEUE_SERVICE_WINDOW_MS
        ) {
            m.successfulHopCompletionTimes.removeFirst()
        }
    }

    private fun eventCount(m: MutableMetrics, now: Long, type: InstabilityType? = null): Int {
        pruneInstability(m, now)
        return m.instabilityEvents.count { type == null || it.type == type }
    }

    private fun freshness(lastValidCommunication: Long?, now: Long): Double {
        val last = lastValidCommunication ?: return 0.0
        val age = (now - last).coerceAtLeast(0L)
        if (age <= FRESHNESS_FULL_MS) return 1.0
        if (age >= FRESHNESS_ZERO_MS) return 0.0
        return 1.0 - (age - FRESHNESS_FULL_MS).toDouble() / (FRESHNESS_ZERO_MS - FRESHNESS_FULL_MS).toDouble()
    }

    private fun filteredRssi(m: MutableMetrics): Int? = median(m.rssiSamples)?.toInt()

    private fun rssiSuitability(rssi: Int?): Double {
        if (rssi == null) return 0.0
        if (rssi >= RSSI_GOOD_DBM) return 1.0
        if (rssi <= RSSI_BAD_DBM) return 0.0
        return (rssi - RSSI_BAD_DBM).toDouble() / (RSSI_GOOD_DBM - RSSI_BAD_DBM).toDouble()
    }

    private fun <T> appendWindow(window: ArrayDeque<T>, value: T, capacity: Int) {
        window.addLast(value)
        while (window.size > capacity) window.removeFirst()
    }

    private fun median(values: Collection<out Number>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.map { it.toDouble() }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun attemptKey(messageId: String, from: String, to: String) = "$from|$to|$messageId"

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
}
