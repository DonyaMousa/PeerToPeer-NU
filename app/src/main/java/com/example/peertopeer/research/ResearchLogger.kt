package com.example.peertopeer.research

import android.content.Context
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class ResearchLogger(private val context: Context) {
    // A formal protocol run owns one file. This prevents CARBLE and 2BRH rows
    // from being accidentally exported as one mixed experiment.
    @Volatile private var activeFile = File(context.filesDir, "research-v17-manual.csv")
    private val writer = Executors.newSingleThreadExecutor()
    private val eventCount = AtomicInteger(if (activeFile.exists()) activeFile.useLines { (it.count() - 1).coerceAtLeast(0) } else 0)
    private val protocolsSeen = linkedSetOf<String>()
    private val conditionsSeen = linkedSetOf<String>()
    private val runsSeen = linkedSetOf<String>()

    @Volatile var experimentId: String = "manual"
    @Volatile var conditionLabel: String = "manual"
    @Volatile var sessionId: String = "manual"
    @Volatile var protocolLabel: String = "manual"
    @Volatile var localNodeId: String = "unknown"
    @Volatile var enabled: Boolean = true

    private val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val androidApi: Int = Build.VERSION.SDK_INT
    private val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    fun record(event: ResearchEvent) {
        if (!enabled) return

        val enriched = event.copy(
                runLabel = event.runLabel ?: experimentId,
                conditionLabel = event.conditionLabel ?: conditionLabel,
                sessionId = event.sessionId ?: sessionId,
                deviceModel = event.deviceModel ?: deviceModel,
                androidApi = event.androidApi ?: androidApi,
                appVersion = event.appVersion ?: appVersion
            )
        val outputFile = synchronized(this) {
            protocolsSeen += enriched.protocol
            enriched.conditionLabel?.takeIf { it.isNotBlank() }?.let { conditionsSeen += it }
            enriched.runLabel?.takeIf { it.isNotBlank() }?.let { runsSeen += it }
            activeFile
        }
        eventCount.incrementAndGet()
        writer.execute {
            runCatching {
                if (!outputFile.exists()) outputFile.writeText(headerText() + "\n")
                outputFile.appendText(rowText(enriched) + "\n")
            }.onFailure { com.example.peertopeer.diagnostics.DiagnosticLogger.error("EXPORT", "Research write failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Starts a separate, immutable evidence file for one formal protocol run. */
    @Synchronized
    fun beginFormalRun(runLabel: String, protocol: String, newSessionId: String, newConditionLabel: String) {
        writer.submit {}.get()
        val safeRun = sanitize(runLabel)
        val safeProtocol = sanitize(displayProtocol(protocol))
        val shortSession = sanitize(newSessionId.take(8))
        activeFile = File(context.filesDir, "research-v17-${safeRun}-${safeProtocol}-${shortSession}.csv")
        if (activeFile.exists()) activeFile.delete()
        eventCount.set(0)
        protocolsSeen.clear()
        conditionsSeen.clear()
        runsSeen.clear()
        experimentId = runLabel
        conditionLabel = newConditionLabel
        sessionId = newSessionId
        protocolLabel = protocol
    }

    /** Starts a new non-formal log without deleting completed evidence files. */
    @Synchronized
    fun beginManualSession() {
        writer.submit {}.get()
        activeFile = File(context.filesDir, "research-v17-manual-${System.currentTimeMillis()}.csv")
        eventCount.set(0)
        protocolsSeen.clear()
        conditionsSeen.clear()
        runsSeen.clear()
        resetMetadata()
    }

    fun count(): Int = eventCount.get()

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val (conditionName, runName, protocolName) = synchronized(this) {
            Triple(
                if (conditionsSeen.size == 1) conditionsSeen.first() else if (conditionsSeen.isEmpty()) conditionLabel else "MIXED_CONDITIONS",
                if (runsSeen.size <= 1) (runsSeen.firstOrNull() ?: experimentId) else "BLOCK",
                if (protocolsSeen.size == 1) protocolsSeen.first() else if (protocolsSeen.isEmpty()) protocolLabel else "MIXED"
            )
        }
        val safeRun = sanitize(runName)
        val safeCondition = sanitize(conditionName)
        val safeProtocol = sanitize(displayProtocol(protocolName))
        val safeNode = sanitize(localNodeId)
        return "peer2peer-${safeCondition}-${safeRun}-${safeProtocol}-${safeNode}-${stamp}.csv"
    }

    fun writeToUri(uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(csvText())
        } ?: error("Unable to open export destination")
        true
    }.getOrElse { false }

    fun csvText(): String {
        writer.submit {}.get()
        val file = activeFile
        return if (file.exists()) file.readText() else headerText() + "\n"
    }
    private fun headerText(): String {
        val header = listOf(
            "epochMs","elapsedMs","sessionId","runLabel","conditionLabel","event","messageId","packetType",
            "protocol","sourceNode","currentNode","previousHop","nextHop","destination","sequence","hopCount",
            "rawStage","decisionStage","hysteresisStatus","stage","qRaw","qDecision","confidence","routeConfidence","routeCost","route","backupHop","backupRoute",
            "D_delivery","F_freshness","R_stability","T_timeliness","S_signal","B_resource",
            "rawRssiDbm","filteredRssiDbm","rssiSampleCount","deliveryAttempts","hopAckSuccesses","medianHopAckRttMs","medianGattWriteMs","freshnessAgeMs",
            "instabilityEvents","gattFailureCount","ackTimeoutCount","disconnectCount","measurementStatus","B_source","trafficType",
            "queueDepth","queueCapacity","queueScaleK","queuePressure","mmReason","carbleReason","forwardingAction","candidateRoutes","success","latencyMs","totalPackets","ackedPackets","finalResult","attemptCount","retransmissionCount","backupActivationCount","queueResidenceMs","deliveredBeforeTtl",
            "deviceModel","androidApi","appVersion","note"
        )
        return "eventId," + header.joinToString(",")
    }
    private fun rowText(e: ResearchEvent): String {
            val values = listOf(
                e.epochMs,e.elapsedMs,e.sessionId,e.runLabel,e.conditionLabel,e.event,e.messageId,e.packetType,
                displayProtocol(e.protocol),e.sourceNode,e.currentNode,e.previousHop,e.nextHop,e.destination,e.sequence,e.hopCount,
                e.rawStage,e.decisionStage,e.hysteresisStatus,e.stage,e.qRaw,e.qDecision,e.confidence,e.routeConfidence,e.routeCost,e.route,e.backupHop,e.backupRoute,
                e.d,e.f,e.r,e.t,e.s,e.b,
                e.rawRssiDbm,e.rssiDbm,e.rssiSampleCount,e.deliveryAttempts,e.hopAckSuccesses,e.medianHopAckRttMs,e.medianGattWriteMs,e.freshnessAgeMs,
                e.instabilityEvents,e.gattFailureCount,e.ackTimeoutCount,e.disconnectCount,e.measurementStatus,e.bSource,e.trafficType,
                e.queueDepth,e.queueCapacity,e.queueScaleK,e.queuePressure,e.mmReason,e.carbleReason,e.forwardingAction,e.candidateRoutes,e.success,e.latencyMs,
                e.totalPackets,e.ackedPackets,e.finalResult,e.attemptCount,e.retransmissionCount,e.backupActivationCount,e.queueResidenceMs,e.deliveredBeforeTtl,
                e.deviceModel,e.androidApi,e.appVersion,e.note
            )
        return quote(java.util.UUID.randomUUID().toString()) + "," + values.joinToString(",") { quote(it) }
    }

    @Synchronized
    fun clear() {
        val file = activeFile
        writer.submit { file.delete() }.get()
        eventCount.set(0)
        protocolsSeen.clear()
        conditionsSeen.clear()
        runsSeen.clear()
    }

    fun resetMetadata() {
        experimentId = "manual"
        conditionLabel = "manual"
        sessionId = "manual"
        protocolLabel = "manual"
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "unknown" }

    private fun displayProtocol(value: String): String =
        if (value == "TWO_RH" || value == "2RH") "2BRH" else value

    private fun quote(value: Any?): String =
        "\"${(value ?: "").toString().replace("\r", " ").replace("\n", " ").replace("\"", "\"\"")}\""
}
