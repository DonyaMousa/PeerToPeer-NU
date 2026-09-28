package com.example.peertopeer.app

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.peertopeer.bluetooth.BleCapabilityChecker
import com.example.peertopeer.bluetooth.BleManager
import com.example.peertopeer.bluetooth.BleScanner
import com.example.peertopeer.data.PacketStore
import com.example.peertopeer.data.PersistentPacketMap
import com.example.peertopeer.data.ChatRepository
import com.example.peertopeer.data.GroupRepository
import com.example.peertopeer.data.IdentityDataStore
import com.example.peertopeer.data.PinnedPeersRepository
import com.example.peertopeer.data.SavedPeerRepository
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.diagnostics.DiagnosticLogger
import com.example.peertopeer.network.codec.PacketCodec
import com.example.peertopeer.network.forwarding.ForwardingDecision
import com.example.peertopeer.network.forwarding.LinkMetricsStore
import com.example.peertopeer.network.forwarding.RealProtocolRouter
import com.example.peertopeer.network.model.*
import com.example.peertopeer.network.reliability.DuplicateCache
import com.example.peertopeer.network.topology.TopologyRepository
import com.example.peertopeer.research.ResearchEvent
import com.example.peertopeer.research.ResearchLogger
import com.example.peertopeer.service.NotificationHelper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PeerToPeerController(private val context: Context) {
    companion object {
        const val FORMAL_PACKET_COUNT = 50
        const val FORMAL_INTERVAL_MS = 1_000L
        const val FORMAL_WARMUP_MS = 20_000L
        const val FORMAL_DRAIN_TIMEOUT_MS = 55_000L
        const val FORMAL_PACKET_TTL_MS = 45_000L
        private const val DIRECT_PEER_TIMEOUT_MS = 12_000L
        private const val TOPOLOGY_INTERVAL_MS = 5_000L
        private const val SOURCE_ACK_RETRY_MS = 8_000L
        private const val HOP_ACK_RETRY_MS = 10_000L
        private const val END_TO_END_ACK_GRACE_MS = 30_000L
        /** M2 waits for a real receipt before trying its selected backup. */
        private const val M2_HOP_ACK_WAIT_MS = 2_500L
        private const val RELAY_DUPLICATE_WINDOW_MS = 1_500L
        private const val DESTINATION_DEDUP_WINDOW_MS = 10 * 60_000L
    }

    private data class FormalRunState(
        val sessionId: String,
        val destinationId: String,
        val protocol: ProtocolType,
        val runLabel: String,
        val conditionLabel: String,
        val candidateRelays: List<String>,
        val createdMessageIds: MutableSet<String> = linkedSetOf(),
        val ackedMessageIds: MutableSet<String> = linkedSetOf(),
        val ackLatenciesMs: MutableList<Long> = mutableListOf(),
        val firstHopByMessage: MutableMap<String, String> = linkedMapOf(),
        val stageCounts: MutableMap<String, Int> = linkedMapOf(),
        val stageRecordedMessageIds: MutableSet<String> = linkedSetOf(),
        var txComplete: Boolean = false,
        var finalized: Boolean = false
    )

    /**
     * A CARBLE recovery is resolved only by a HOP_ACK, never by the Android
     * GATT write callback. M2 uses a bounded primary wait; M3 uses its
     * controlled delayed backup. Both paths remain auditable in the CSV.
     */
    private data class StageRecoveryState(
        val packet: NetworkPacket,
        val primaryHopId: String,
        val backupHopId: String,
        val stage: String,
        val confidence: Double?,
        val rawStage: String?,
        val sessionId: String?,
        var backupActivated: Boolean = false,
        var resolvedByHopId: String? = null,
        var backupActivationCount: Int = 0
    )

    private data class PacketAuditState(
        val packet: NetworkPacket,
        val createdElapsedMs: Long,
        var rawStage: String? = null,
        var decisionStage: String? = null,
        var primaryHopId: String? = null,
        var backupHopId: String? = null,
        var attempts: Int = 0,
        var backupActivations: Int = 0,
        var resultRecorded: Boolean = false
    )

    private val identityStore = IdentityDataStore(context)
    private val groupRepository = GroupRepository(context)
    private val pinnedRepository = PinnedPeersRepository(context)
    private val savedPeerRepository = SavedPeerRepository(context)
    private val chatRepository = ChatRepository(context)

    private var identity = identityStore.ensureIdentity()
    private var topology = TopologyRepository(Node(identity.nodeId, identity.displayName.ifBlank { identity.nodeId }))
    // Relays suppress only near-simultaneous duplicates. A later source retry
    // (after an ACK loss) must be allowed to traverse the mesh again.
    private val relayDuplicates = DuplicateCache(
        maxEntries = 4_000,
        suppressionWindowMs = RELAY_DUPLICATE_WINDOW_MS
    )
    // Destinations deliver a chat/experiment packet once, but duplicate
    // arrivals are still ACKed again so a lost ACK cannot strand the source.
    private val deliveredPackets = DuplicateCache(
        maxEntries = 4_000,
        suppressionWindowMs = DESTINATION_DEDUP_WINDOW_MS
    )
    private val metrics = LinkMetricsStore()
    private val router = RealProtocolRouter(metrics)
    private val research = ResearchLogger(context)
    private val handler = Handler(Looper.getMainLooper())
    private val carryCounts = mutableMapOf<String, Int>()
    private val packetStore = PacketStore(context)
    private val queuedPackets = PersistentPacketMap(packetStore)
    private val activeAttempts = mutableMapOf<String, Int>()
    private val nextAttemptAt = mutableMapOf<String, Long>()
    private val lowWaiting = mutableSetOf<String>()
    private var networkEpoch = 0
    private var networkRunning = false
    private val retryTask = object : Runnable {
        override fun run() {
            if (!networkRunning) return
            retryQueuedMessages()
            handler.postDelayed(this, 1_000L)
        }
    }
    private val inFlightSourcePackets = ConcurrentHashMap.newKeySet<String>()
    private val ackRetryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val hopRetryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val lastStageByRoute = mutableMapOf<String, String>()
    private val stageRecoveries = mutableMapOf<String, StageRecoveryState>()
    private val packetAudits = mutableMapOf<String, PacketAuditState>()
    private var remoteFormalTrafficReservedUntilMs: Long = 0L
    private var experimentToken: String? = null
    private var directMeasurementToken: String? = null
    private var formalRunState: FormalRunState? = null

    private val capabilities = BleCapabilityChecker(context).check()

    private val _uiState = mutableStateOf(
        AppUiState(
            identity = identity,
            groups = groupRepository.load(),
            pinnedPeerIds = pinnedRepository.load(),
            savedPeers = savedPeerRepository.load(),
            messages = chatRepository.load(),
            bleSupported = capabilities.bleSupported,
            advertisingSupported = capabilities.advertisingSupported
        )
    )
    val uiState: State<AppUiState> get() = _uiState

    init {
        research.localNodeId = identity.nodeId
        com.example.peertopeer.bluetooth.BleTransportTelemetry.observer = { event, address, bytes, status ->
            research.record(ResearchEvent(SystemClock.elapsedRealtime(), event, "ble-${UUID.randomUUID()}", research.protocolLabel,
                identity.nodeId, success = status?.let { it == 0 }, note = "address=$address; bytes=$bytes; status=${status ?: "accepted"}"))
        }
        val restored = _uiState.value.messages.map { message ->
            if (message.outgoing && message.status in setOf(MessageStatus.SENDING, MessageStatus.IN_TRANSIT, MessageStatus.QUEUED))
                message.copy(status = if (queuedPackets.containsKey(message.messageId)) MessageStatus.QUEUED else MessageStatus.FAILED)
            else message
        }
        if (restored != _uiState.value.messages) { chatRepository.save(restored); _uiState.value = _uiState.value.copy(messages = restored) }
    }

    private val ble = BleManager(
        context = context,
        onPeer = ::onPeerDiscovered,
        onBytes = ::onBleBytes,
        onTransportState = { nodeId, ready ->
            val changed = topology.isTransportReady(nodeId) != ready
            topology.setTransportReady(nodeId, ready)
            if (changed) {
                metrics.markLinkChange(identity.nodeId, nodeId)
                research.record(
                    ResearchEvent(
                        elapsedMs = SystemClock.elapsedRealtime(),
                        event = if (ready) "BLE_PEER_READY" else "BLE_PEER_LOST",
                        messageId = "link-${identity.nodeId}-$nodeId-${System.currentTimeMillis()}",
                        protocol = _uiState.value.selectedProtocol.name,
                        currentNode = identity.nodeId,
                        nextHop = nodeId,
                        success = ready,
                        note = "GATT duplex state changed; route graph recalculated"
                    )
                )
                DiagnosticLogger.info("TOPOLOGY", "$nodeId transport ${if (ready) "READY" else "LOST"}; recalculating direct and relay routes")
                updatePeers()
                if (networkRunning) {
                    // Publish withdrawals and new Ready edges promptly so a
                    // third device does not keep routing through an old hop.
                    broadcastTopology()
                    retryQueuedMessages(readyNodeId = nodeId.takeIf { ready })
                }
            }
        },
        onStatus = { status ->
            if (status == "Stopped") {
                networkRunning = false
                handler.removeCallbacks(retryTask)
                update { copy(backgroundServiceEnabled = false) }
            }
            update { copy(bleStatus = status) }
            DiagnosticLogger.info("BLE", status)
        }
    )

    private val topologyTask = object : Runnable {
        override fun run() {
            if (!networkRunning) return
            val expired = topology.expireDirectPeers(DIRECT_PEER_TIMEOUT_MS)
            expired.forEach { nodeId ->
                if (!topology.isTransportReady(nodeId)) {
                    metrics.markLinkChange(identity.nodeId, nodeId)
                }
                DiagnosticLogger.info("TOPOLOGY", "Peer $nodeId advertisement aged out; active GATT route is ${if (topology.isTransportReady(nodeId)) "still ready" else "unavailable"}.")
            }
            metrics.refreshAges()
            updatePeers()
            broadcastTopology()
            retryQueuedMessages()
            handler.postDelayed(this, TOPOLOGY_INTERVAL_MS)
        }
    }

    fun setDisplayName(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        identity = identityStore.updateDisplayName(clean)
        topology = TopologyRepository(Node(identity.nodeId, identity.displayName))
        update { copy(identity = identity) }
        DiagnosticLogger.success("PROFILE", "Local profile saved for ${identity.displayName} (${identity.nodeId})")
    }

    /** Called by the Activity on every foreground return, including Settings. */
    fun onAppForegrounded() {
        identity = identityStore.ensureIdentity()
        update { copy(identity = identity, onboardingRevision = onboardingRevision + 1) }
    }

    fun startBle() {
        if (networkRunning) return
        if (!com.example.peertopeer.bluetooth.BlePermissions.hasRequiredBlePermissions(context) ||
            !com.example.peertopeer.bluetooth.BluetoothReadiness.isBluetoothEnabled(context)) return
        networkRunning = true
        networkEpoch++
        if (!capabilities.bleSupported) {
            update { copy(bleStatus = "BLE unsupported") }
            return
        }
        DiagnosticLogger.info("BLE", "Starting BLE stack")
        try { ble.start(identity) } catch (e: Exception) {
            networkRunning = false
            DiagnosticLogger.error("STARTUP", "BLE startup failed: ${e.javaClass.simpleName}")
            update { copy(backgroundServiceEnabled = false, bleStatus = "Startup failed") }
            return
        }
        handler.removeCallbacks(retryTask)
        handler.postDelayed(retryTask, 1_000L)
        handler.removeCallbacks(topologyTask)
        handler.postDelayed(topologyTask, 3_000L)
        update { copy(backgroundServiceEnabled = true) }
    }

    fun stopBle() {
        networkRunning = false
        networkEpoch++
        handler.removeCallbacks(retryTask)
        if (_uiState.value.experimentRunning) stopPhysicalExperiment()
        handler.removeCallbacks(topologyTask)
        activeAttempts.clear()
        inFlightSourcePackets.clear()
        ackRetryScheduled.clear()
        directMeasurementToken = null
        ble.stop()
        DiagnosticLogger.info("BLE", "Peer2Peer BLE service stopped")
        update { copy(bleStatus = "Stopped", backgroundServiceEnabled = false, directMeasurementRunning = false) }
    }

    fun selectProtocol(protocol: ProtocolType) {
        if (_uiState.value.experimentRunning || protocol != ProtocolType.CARBLE) return
        update { copy(selectedProtocol = ProtocolType.CARBLE) }
    }
    /** Relays and destinations also reserve the mesh while A is running a formal test. */
    private fun formalTrafficReserved(): Boolean =
        _uiState.value.experimentRunning || SystemClock.elapsedRealtime() < remoteFormalTrafficReservedUntilMs

    fun peerTransportState(nodeId: String): String = ble.connectionState(nodeId)
    fun pendingCount(): Int = queuedPackets.size
    fun routePathTo(nodeId: String): List<String> {
        val graph = topology.graphSnapshot()
        val destination = graph.getNode(nodeId) ?: return emptyList()
        return com.example.peertopeer.routing.DijkstraEngine()
            .findRoute(graph, Node(identity.nodeId, identity.displayName), destination)
            ?.path?.map { it.nodeId }
            .orEmpty()
    }
    fun exportDiagnosticsTo(uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.write("Peer2Peer CARBLE v17 diagnostics\n")
            out.write(com.example.peertopeer.diagnostics.CrashReporter.consumeLastCrash(context).orEmpty())
            com.example.peertopeer.diagnostics.DiagnosticLogger.events.toList().forEach { out.write("\n${it.component}: ${it.message}") }
        } ?: error("Cannot open file")
        true
    }.getOrDefault(false)
    fun selectPeer(nodeId: String?) = update { copy(selectedPeerId = nodeId) }

    /** Open a conversation directly from the live network peer list. */
    fun openPeerChat(nodeId: String): Boolean {
        val normalized = nodeId.trim().uppercase().replace("-", "")
        if (normalized == identity.nodeId) return false
        val known = topology.allPeers().firstOrNull { it.nodeId == normalized } ?: return false
        val saved = savedPeerRepository.add(normalized, known.displayName)
        update { copy(savedPeers = saved, selectedPeerId = normalized) }
        return true
    }

    fun addSavedPeer(userId: String): Boolean {
        val normalized = userId.trim().uppercase().replace("-", "")
        if (!normalized.matches(Regex("[0-9A-F]{8}")) || normalized == identity.nodeId) return false
        val known = topology.allPeers().firstOrNull { it.nodeId == normalized }
        val list = savedPeerRepository.add(normalized, known?.displayName)
        update { copy(savedPeers = list, selectedPeerId = normalized) }
        DiagnosticLogger.success("PEERS", "Saved peer $normalized")
        return true
    }

    fun removeSavedPeer(userId: String) {
        val list = savedPeerRepository.remove(userId)
        update { copy(savedPeers = list, selectedPeerId = selectedPeerId?.takeUnless { it == userId }) }
    }

    fun togglePinned(nodeId: String) { update { copy(pinnedPeerIds = pinnedRepository.toggle(nodeId)) } }

    fun createGroup(name: String, members: List<String>) {
        if (name.isBlank() || members.isEmpty()) return
        val group = groupRepository.create(name, identity.nodeId, members)
        update { copy(groups = groupRepository.load(), selectedGroupId = group.groupId) }
        broadcastGroupDefinition(group)
    }

    fun deleteGroup(groupId: String) {
        groupRepository.delete(groupId)
        update { copy(groups = groupRepository.load(), selectedGroupId = selectedGroupId?.takeUnless { it == groupId }) }
    }

    fun selectGroup(groupId: String?) = update { copy(selectedGroupId = groupId) }

    fun addGroupMembers(groupId: String, memberIds: Collection<String>): Boolean {
        val group = groupRepository.addMembers(groupId, memberIds, identity.nodeId) ?: return false
        update { copy(groups = groupRepository.load()) }
        broadcastGroupDefinition(group)
        return true
    }

    fun sendGroup(groupId: String, text: String) {
        if (formalTrafficReserved()) return
        val group = _uiState.value.groups.firstOrNull { it.groupId == groupId } ?: return
        val messageText = text.trim()
        if (messageText.isBlank() || queuedPackets.size + group.memberNodeIds.size >= 220) return
        val recipients = group.memberNodeIds.filter { it != identity.nodeId }.distinct()
        if (recipients.isEmpty()) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val groupMessageId = UUID.randomUUID().toString()
        appendMessage(
            ChatMessage(
                messageId = groupMessageId,
                peerId = group.groupId,
                text = messageText,
                outgoing = true,
                createdAtElapsedMs = nowElapsed,
                createdAtEpochMs = nowEpoch,
                status = MessageStatus.QUEUED,
                groupId = group.groupId,
                senderId = identity.nodeId,
                recipientStatuses = recipients.associateWith { MessageStatus.QUEUED }
            )
        )

        recipients.forEach { recipientId ->
            val packet = NetworkPacket(
                messageId = "group-$groupMessageId-$recipientId",
                sourceId = identity.nodeId,
                destinationId = recipientId,
                createdAtElapsedMs = nowElapsed,
                createdAtEpochMs = nowEpoch,
                expiryAfterMs = 24 * 60 * 60_000L,
                protocol = _uiState.value.selectedProtocol,
                type = PacketType.CHAT,
                payload = groupChatPayload(group, groupMessageId, messageText).toString(),
                routeTrace = listOf(identity.nodeId)
            )
            queuedPackets[packet.messageId] = packet
            research.record(
                ResearchEvent(
                    elapsedMs = nowElapsed,
                    event = "GROUP_MESSAGE_CREATED",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = identity.nodeId,
                    destination = recipientId,
                    packetType = packet.type.name,
                    trafficType = "GROUP_CHAT",
                    note = "groupId=${group.groupId}; groupMessageId=$groupMessageId"
                )
            )
            tryQueuedPacket(packet.messageId)
        }
    }

    private data class GroupChatEnvelope(
        val groupId: String,
        val groupMessageId: String,
        val groupName: String,
        val ownerId: String,
        val version: Long,
        val memberIds: List<String>,
        val text: String
    )

    private fun groupChatPayload(group: PeerGroup, groupMessageId: String, text: String): JSONObject =
        JSONObject()
            .put("kind", "GROUP_CHAT")
            .put("groupId", group.groupId)
            .put("groupMessageId", groupMessageId)
            .put("groupName", group.name)
            .put("ownerId", group.ownerNodeId)
            .put("version", group.version)
            .put("members", org.json.JSONArray(group.memberNodeIds))
            .put("text", text)

    private fun groupEnvelope(packet: NetworkPacket): GroupChatEnvelope? {
        if (packet.type != PacketType.CHAT) return null
        return runCatching {
            val payload = JSONObject(packet.payload)
            if (payload.optString("kind") != "GROUP_CHAT") return@runCatching null
            val groupId = payload.optString("groupId")
            val messageId = payload.optString("groupMessageId")
            if (groupId.isBlank() || messageId.isBlank()) return@runCatching null
            val members = payload.optJSONArray("members") ?: org.json.JSONArray()
            GroupChatEnvelope(
                groupId = groupId,
                groupMessageId = messageId,
                groupName = payload.optString("groupName", "Group").take(32),
                ownerId = payload.optString("ownerId"),
                version = payload.optLong("version", 1L).coerceAtLeast(1L),
                memberIds = buildList { for (index in 0 until members.length()) add(members.getString(index)) }.distinct(),
                text = payload.optString("text").take(500)
            )
        }.getOrNull()
    }

    private fun broadcastGroupDefinition(group: PeerGroup) {
        val payload = JSONObject()
            .put("action", "UPSERT")
            .put("groupId", group.groupId)
            .put("name", group.name)
            .put("owner", group.ownerNodeId)
            .put("version", group.version)
            .put("updatedAt", group.updatedAtEpochMs)
            .put("members", org.json.JSONArray(group.memberNodeIds))
            .toString()
        group.memberNodeIds.filter { it != identity.nodeId }.forEach { recipientId ->
            val packet = NetworkPacket(
                messageId = "group-control-${group.groupId}-${group.version}-$recipientId",
                sourceId = identity.nodeId,
                destinationId = recipientId,
                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                protocol = _uiState.value.selectedProtocol,
                type = PacketType.GROUP_CONTROL,
                payload = payload,
                expiryAfterMs = 24 * 60 * 60_000L,
                routeTrace = listOf(identity.nodeId)
            )
            if (queuedPackets.size < 220 && !queuedPackets.containsKey(packet.messageId)) {
                queuedPackets[packet.messageId] = packet
                research.record(ResearchEvent(SystemClock.elapsedRealtime(), "GROUP_METADATA_QUEUED", packet.messageId,
                    packet.protocol.name, identity.nodeId, sourceNode = identity.nodeId, destination = recipientId,
                    packetType = packet.type.name, trafficType = "CONTROL", note = "groupId=${group.groupId}; version=${group.version}"))
                tryQueuedPacket(packet.messageId)
            }
        }
    }

    private fun receiveGroupControl(packet: NetworkPacket) {
        val payload = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
        if (payload.optString("action") != "UPSERT") return
        val groupId = payload.optString("groupId")
        val ownerId = payload.optString("owner")
        val name = payload.optString("name")
        val members = payload.optJSONArray("members") ?: return
        if (groupId.isBlank() || name.isBlank() || ownerId.isBlank() || ownerId != packet.sourceId) return
        val group = PeerGroup(
            groupId = groupId,
            name = name.take(32),
            ownerNodeId = ownerId,
            version = payload.optLong("version", 1L).coerceAtLeast(1L),
            updatedAtEpochMs = payload.optLong("updatedAt", System.currentTimeMillis()),
            memberNodeIds = buildList { for (index in 0 until members.length()) add(members.getString(index)) }.distinct()
        )
        if (identity.nodeId !in group.memberNodeIds) return
        groupRepository.upsertRemote(group)
        update { copy(groups = groupRepository.load()) }
        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "GROUP_METADATA_RECEIVED", packet.messageId,
            packet.protocol.name, identity.nodeId, sourceNode = packet.sourceId, destination = identity.nodeId,
            packetType = packet.type.name, trafficType = "CONTROL", note = "groupId=${group.groupId}; version=${group.version}"))
    }

    private fun trafficTypeFor(packet: NetworkPacket): String = when (packet.type) {
        PacketType.EXPERIMENT -> "EXPERIMENT"
        PacketType.CHAT -> if (groupEnvelope(packet) != null) "GROUP_CHAT" else "DIRECT_CHAT"
        PacketType.DIRECT_PROBE -> "DIRECT_MEASUREMENT"
        PacketType.DELIVERY_ACK, PacketType.HOP_ACK -> runCatching { JSONObject(packet.payload).optString("trafficType") }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: "CONTROL"
        else -> "CONTROL"
    }

    fun sendChat(destinationId: String, text: String) {
        if (formalTrafficReserved()) return
        if (text.isBlank()) return
        if (queuedPackets.size >= 200) { DiagnosticLogger.error("QUEUE", "Pending queue is full; message was not accepted"); return }
        val elapsed = SystemClock.elapsedRealtime()
        val packet = NetworkPacket(
            messageId = UUID.randomUUID().toString(),
            sourceId = identity.nodeId,
            destinationId = destinationId,
            createdAtElapsedMs = elapsed,
            createdAtEpochMs = System.currentTimeMillis(),
            expiryAfterMs = 24 * 60 * 60_000L,
            protocol = _uiState.value.selectedProtocol,
            type = PacketType.CHAT,
            payload = text.trim(),
            routeTrace = listOf(identity.nodeId)
        )
        appendMessage(ChatMessage(packet.messageId, destinationId, text.trim(), true, elapsed, MessageStatus.QUEUED))
        queuedPackets[packet.messageId] = packet
        research.record(ResearchEvent(elapsed, "MESSAGE_CREATED", packet.messageId, packet.protocol.name, identity.nodeId,
            destination = destinationId, packetType = packet.type.name, trafficType = "DIRECT_CHAT"))
        tryQueuedPacket(packet.messageId)
    }


    /**
     * Clears only formal-run evidence. It deliberately does NOT reset routing,
     * BLE, topology, confidence, or link metrics. The physical network must
     * determine the route and CARBLE/2BRH state naturally.
     */
    fun preparePhysicalExperiment() {
        if (_uiState.value.experimentRunning) return
        // Never delete a completed CARBLE/2BRH file from the device while
        // preparing the next run. Formal start creates its own isolated file.
        research.beginManualSession()
        research.localNodeId = identity.nodeId
        lastStageByRoute.clear()
        router.clearStageHysteresis()
        carryCounts.clear()
        stageRecoveries.clear()
        packetAudits.clear()
        formalRunState = null
        experimentToken = null
        update {
            copy(
                researchEvents = 0,
                lastExportPath = null,
                experimentProgress = 0,
                experimentTotal = 0,
                experimentLabel = null,
                experimentCondition = null,
                experimentPhase = "IDLE",
                experimentAcked = 0,
                experimentStatus = "New log prepared. Routing metrics were preserved.",
                experimentCandidateRelays = emptyList(),
                lastExperimentRawStage = null,
                lastExperimentStage = null,
                lastExperimentHysteresisStatus = null,
                lastExperimentConfidence = null,
                lastExperimentComponents = null,
                lastExperimentPrimaryHop = null,
                lastExperimentBackupHop = null,
                lastExperimentRoute = null,
                experimentPdrPercent = null,
                experimentMedianLatencyMs = null,
                experimentFirstHopSummary = null,
                experimentStageSummary = null
            )
        }
        DiagnosticLogger.success(
            "EXPERIMENT",
            "New formal-run log prepared. Topology and live BLE metric evidence were not modified."
        )
    }

    /**
     * Returns the two-hop relay candidates visible in the current physical
     * topology. This is a readiness/telemetry helper only; it never influences
     * route selection.
     */
    fun formalCandidateRelays(destinationId: String): List<String> {
        val destination = destinationId.trim().uppercase().replace("-", "")
        if (!destination.matches(Regex("[0-9A-F]{8}")) || destination == identity.nodeId) return emptyList()
        val graph = topology.graphSnapshot()
        return _uiState.value.peers
            .asSequence()
            .filter { it.isDirect && it.nodeId != destination }
            .map { it.nodeId }
            .filter { relay -> graph.containsEdge(relay, destination) }
            .distinct()
            .sorted()
            .toList()
    }

    fun formalExperimentReadiness(destinationId: String): String? {
        val destination = destinationId.trim().uppercase().replace("-", "")
        if (!_uiState.value.backgroundServiceEnabled) return "Start the Peer2Peer service first."
        if (!destination.matches(Regex("[0-9A-F]{8}")) || destination == identity.nodeId) return "Choose a destination from the reachable peer list."
        val destinationPeer = _uiState.value.peers.firstOrNull { it.nodeId == destination }
            ?: return "Destination is not known yet. Wait for topology discovery."
        if (destinationPeer.hopEstimate == null) return "Destination is currently unreachable. Keep relay phones between the two endpoints."
        if (destinationPeer.isDirect && ble.connectionState(destination) != "Ready") return "Peer is discovered; wait for the messaging session to become Ready."
        if (!destinationPeer.isDirect && _uiState.value.peers.none { it.isDirect && ble.connectionState(it.nodeId) == "Ready" }) return "No ready first-hop session yet."
        return null
    }

    /**
     * Sends 20 one-hop app-receipt probes. They never relay, never force a Q
     * stage and are exported as DIRECT_MEASUREMENT rather than formal evidence.
     */
    fun startDirectMeasurement(destinationId: String, probeCount: Int = 20): Boolean {
        if (_uiState.value.experimentRunning || _uiState.value.directMeasurementRunning) return false
        val destination = destinationId.trim().uppercase().replace("-", "")
        val peer = _uiState.value.peers.firstOrNull { it.nodeId == destination }
        if (peer?.isDirect != true || ble.connectionState(destination) != "Ready") {
            update { copy(directMeasurementStatus = "Choose a direct Ready peer. Direct measurement never uses a relay.") }
            return false
        }
        val token = UUID.randomUUID().toString()
        directMeasurementToken = token
        update {
            copy(
                directMeasurementRunning = true,
                directMeasurementProgress = 0,
                directMeasurementTotal = probeCount,
                directMeasurementStatus = "Sending $probeCount one-hop probes at 1 packet/s. Move only the selected peer."
            )
        }
        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "DIRECT_MEASUREMENT_START", token,
            _uiState.value.selectedProtocol.name, identity.nodeId, sourceNode = identity.nodeId, destination = destination,
            packetType = PacketType.DIRECT_PROBE.name, trafficType = "DIRECT_MEASUREMENT", totalPackets = probeCount))

        fun schedule(sequence: Int) {
            if (directMeasurementToken != token) return
            if (sequence > probeCount) {
                directMeasurementToken = null
                update { copy(directMeasurementRunning = false, directMeasurementStatus = "Direct probes complete. Inspect the live Q components, then start a formal paired run when ready.") }
                research.record(ResearchEvent(SystemClock.elapsedRealtime(), "DIRECT_MEASUREMENT_COMPLETE", token,
                    _uiState.value.selectedProtocol.name, identity.nodeId, sourceNode = identity.nodeId, destination = destination,
                    packetType = PacketType.DIRECT_PROBE.name, trafficType = "DIRECT_MEASUREMENT", totalPackets = probeCount))
                return
            }
            val packet = NetworkPacket(
                messageId = "direct-probe-$token-$sequence",
                sourceId = identity.nodeId,
                destinationId = destination,
                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                protocol = _uiState.value.selectedProtocol,
                type = PacketType.DIRECT_PROBE,
                expiryAfterMs = 8_000L,
                payload = JSONObject().put("sequence", sequence).put("total", probeCount).put("mode", "DIRECT_MEASUREMENT").toString(),
                routeTrace = listOf(identity.nodeId)
            )
            queuedPackets[packet.messageId] = packet
            research.record(ResearchEvent(SystemClock.elapsedRealtime(), "DIRECT_PROBE_CREATED", packet.messageId,
                packet.protocol.name, identity.nodeId, sourceNode = identity.nodeId, destination = destination,
                packetType = packet.type.name, sequence = sequence, trafficType = "DIRECT_MEASUREMENT", totalPackets = probeCount))
            tryQueuedPacket(packet.messageId)
            update { copy(directMeasurementProgress = sequence) }
            handler.postDelayed({ schedule(sequence + 1) }, FORMAL_INTERVAL_MS)
        }
        schedule(1)
        return true
    }

    /**
     * Formal physical run. Traffic is fixed at 50 packets / 1 packet per second.
     * The condition label is metadata only. No link, Q value, stage, next hop,
     * failure, delay, or route is injected by this function.
     */
    fun startPhysicalExperiment(
        destinationId: String,
        protocol: ProtocolType,
        runLabel: String,
        conditionLabel: String
    ): Boolean {
        if (protocol != ProtocolType.CARBLE) return false
        if (_uiState.value.experimentRunning || _uiState.value.directMeasurementRunning) return false
        if (queuedPackets.values.any { it.type == PacketType.CHAT }) {
            update { copy(experimentStatus = "Finish pending chat delivery before a research run.") }
            return false
        }

        val destination = destinationId.trim().uppercase().replace("-", "")
        val readinessError = formalExperimentReadiness(destination)
        if (readinessError != null) {
            update { copy(experimentStatus = readinessError) }
            DiagnosticLogger.warning("EXPERIMENT", readinessError)
            return false
        }

        val cleanRun = runLabel.trim().ifBlank { "R-${System.currentTimeMillis()}" }
        val cleanCondition = conditionLabel.trim().ifBlank { "STABLE" }
        val token = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val relays = formalCandidateRelays(destination)

        lastStageByRoute.clear()
        router.clearStageHysteresis()
        carryCounts.clear()
        experimentToken = token
        formalRunState = FormalRunState(
            sessionId = session,
            destinationId = destination,
            protocol = protocol,
            runLabel = cleanRun,
            conditionLabel = cleanCondition,
            candidateRelays = relays
        )
        stageRecoveries.clear()
        packetAudits.clear()
        research.beginFormalRun(cleanRun, protocol.name, session, cleanCondition)
        research.localNodeId = identity.nodeId
        selectProtocol(protocol)

        update {
            copy(
                experimentRunning = true,
                experimentProgress = 0,
                experimentTotal = FORMAL_PACKET_COUNT,
                experimentLabel = cleanRun,
                experimentCondition = cleanCondition,
                experimentPhase = "WARMUP",
                experimentAcked = 0,
                experimentStatus = "20 s warm-up. Keep the phones in their test positions.",
                experimentCandidateRelays = relays,
                lastExperimentRawStage = null,
                lastExperimentStage = null,
                lastExperimentHysteresisStatus = null,
                lastExperimentConfidence = null,
                lastExperimentComponents = null,
                lastExperimentPrimaryHop = null,
                lastExperimentBackupHop = null,
                lastExperimentRoute = null,
                experimentPdrPercent = null,
                experimentMedianLatencyMs = null,
                experimentFirstHopSummary = null,
                experimentStageSummary = null
            )
        }

        research.record(
            ResearchEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                event = "FORMAL_RUN_ARMED",
                messageId = token,
                protocol = protocol.name,
                currentNode = identity.nodeId,
                sourceNode = identity.nodeId,
                destination = destination,
                sessionId = session,
                runLabel = cleanRun,
                conditionLabel = cleanCondition,
                totalPackets = FORMAL_PACKET_COUNT,
                note = "candidateRelays=${relays.joinToString("|")}; warmupMs=$FORMAL_WARMUP_MS; intervalMs=$FORMAL_INTERVAL_MS; ttlMs=$FORMAL_PACKET_TTL_MS"
            )
        )
        DiagnosticLogger.info(
            "EXPERIMENT",
            "Armed $cleanRun / $cleanCondition / ${protocol.name}; 20 s warm-up; relays=${relays.joinToString()}"
        )

        announceRun("START")
        scheduleMeasurementWarmup(token)
        handler.postDelayed({ beginFormalTransmission(token) }, FORMAL_WARMUP_MS)
        return true
    }

    private fun announceRun(phase: String) {
        val run = formalRunState ?: return
        val payload = JSONObject().put("phase", phase).put("sessionId", run.sessionId).put("run", run.runLabel)
            .put("condition", run.conditionLabel).put("protocol", run.protocol.name).put("count", FORMAL_PACKET_COUNT)
            .put("intervalMs", FORMAL_INTERVAL_MS).put("ttlMs", FORMAL_PACKET_TTL_MS).put("queueCapacity", 200)
            .put("copyBudget", 1).put("lowReevaluations", 3).put("bleRetries", 2).toString()
        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "RUN_CONFIGURATION", "run-${run.sessionId}", run.protocol.name, identity.nodeId, sessionId = run.sessionId, note = payload))
        _uiState.value.peers.forEach { peer ->
            val p = NetworkPacket(messageId = "control-${phase}-${run.sessionId}-${peer.nodeId}", sourceId = identity.nodeId,
                destinationId = peer.nodeId, createdAtElapsedMs = SystemClock.elapsedRealtime(),
                protocol = run.protocol, type = PacketType.RUN_CONTROL, payload = payload, expiryAfterMs = 60_000L)
            if (queuedPackets.size < 220) { queuedPackets[p.messageId] = p; tryQueuedPacket(p.messageId) }
        }
    }

    /**
     * Five real RUN_CONTROL hops populate the rolling HOP_ACK / RTT windows
     * before the 50 formal packets begin. They are marked CONTROL and are not
     * included in PDR or CARBLE-vs-2BRH results.
     */
    private fun scheduleMeasurementWarmup(token: String) {
        val run = formalRunState ?: return
        repeat(5) { index ->
            handler.postDelayed({
                if (experimentToken != token || !networkRunning) return@postDelayed
                val probe = NetworkPacket(
                    messageId = "warmup-${run.sessionId}-${index + 1}-${UUID.randomUUID()}",
                    sourceId = identity.nodeId,
                    destinationId = run.destinationId,
                    createdAtElapsedMs = SystemClock.elapsedRealtime(),
                    protocol = run.protocol,
                    type = PacketType.RUN_CONTROL,
                    expiryAfterMs = 30_000L,
                    payload = JSONObject()
                        .put("phase", "WARMUP_PROBE")
                        .put("sessionId", run.sessionId)
                        .put("run", run.runLabel)
                        .put("condition", run.conditionLabel)
                        .put("sequence", index + 1)
                        .toString(),
                    routeTrace = listOf(identity.nodeId)
                )
                if (queuedPackets.size < 220) {
                    queuedPackets[probe.messageId] = probe
                    research.record(ResearchEvent(SystemClock.elapsedRealtime(), "WARMUP_PROBE_CREATED", probe.messageId,
                        run.protocol.name, identity.nodeId, sourceNode = identity.nodeId, destination = run.destinationId,
                        packetType = probe.type.name, sequence = index + 1, sessionId = run.sessionId,
                        runLabel = run.runLabel, conditionLabel = run.conditionLabel, trafficType = "CONTROL"))
                    tryQueuedPacket(probe.messageId)
                }
            }, 1_000L + index * 2_500L)
        }
    }

    private fun beginFormalTransmission(token: String) {
        if (experimentToken != token) return
        val run = formalRunState ?: return
        val readinessError = formalExperimentReadiness(run.destinationId)
        if (readinessError != null) {
            abortFormalRun(token, "Topology invalid after warm-up: $readinessError")
            return
        }
        val firstHop = routePathTo(run.destinationId).getOrNull(1)
        val firstHopMetrics = firstHop?.let { metrics.snapshot(identity.nodeId, it) }
        if (firstHopMetrics?.measurementStatus != "READY") {
            abortFormalRun(token, "Measurements are still warming up on ${firstHop ?: "the first hop"}. Keep all devices connected and start the paired run again.")
            return
        }

        val relays = formalCandidateRelays(run.destinationId)
        update {
            copy(
                experimentPhase = "RUNNING",
                experimentStatus = "Sending $FORMAL_PACKET_COUNT packets at 1 packet/s. Routing remains fully adaptive.",
                experimentCandidateRelays = relays
            )
        }
        research.record(
            ResearchEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                event = "FORMAL_RUN_START",
                messageId = token,
                protocol = run.protocol.name,
                currentNode = identity.nodeId,
                sourceNode = identity.nodeId,
                destination = run.destinationId,
                sessionId = run.sessionId,
                runLabel = run.runLabel,
                conditionLabel = run.conditionLabel,
                totalPackets = FORMAL_PACKET_COUNT,
                note = "candidateRelays=${relays.joinToString("|")}"
            )
        )

        fun schedule(index: Int) {
            if (experimentToken != token) return
            if (index >= FORMAL_PACKET_COUNT) {
                run.txComplete = true
                update {
                    copy(
                        experimentPhase = "DRAINING",
                        experimentStatus = "All packets sent. Waiting up to 30 s for final ACKs."
                    )
                }
                research.record(
                    ResearchEvent(
                        elapsedMs = SystemClock.elapsedRealtime(),
                        event = "FORMAL_TX_COMPLETE",
                        messageId = token,
                        protocol = run.protocol.name,
                        currentNode = identity.nodeId,
                        sourceNode = identity.nodeId,
                        destination = run.destinationId,
                        sessionId = run.sessionId,
                        runLabel = run.runLabel,
                        conditionLabel = run.conditionLabel,
                        totalPackets = FORMAL_PACKET_COUNT,
                        ackedPackets = run.ackedMessageIds.size
                    )
                )
                if (run.ackedMessageIds.size >= FORMAL_PACKET_COUNT) {
                    finalizeFormalRun(token, "all ACKs received")
                } else {
                    handler.postDelayed({ finalizeFormalRun(token, "drain timeout") }, FORMAL_DRAIN_TIMEOUT_MS)
                }
                return
            }

            sendExperimentPacket(
                destinationId = run.destinationId,
                protocol = run.protocol,
                runLabel = run.runLabel,
                conditionLabel = run.conditionLabel,
                sessionId = run.sessionId,
                sequence = index + 1
            )
            update { copy(experimentProgress = index + 1) }
            handler.postDelayed({ schedule(index + 1) }, FORMAL_INTERVAL_MS)
        }

        schedule(0)
    }

    fun stopPhysicalExperiment() {
        formalRunState?.createdMessageIds?.toList()?.forEach { id -> queuedPackets[id]?.let { dropPending(it, "run cancelled") } }
        val token = experimentToken
        experimentToken = null
        formalRunState?.let { run ->
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = "FORMAL_RUN_STOPPED",
                    messageId = token ?: run.sessionId,
                    protocol = run.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = identity.nodeId,
                    destination = run.destinationId,
                    sessionId = run.sessionId,
                    runLabel = run.runLabel,
                    conditionLabel = run.conditionLabel,
                    totalPackets = FORMAL_PACKET_COUNT,
                    ackedPackets = run.ackedMessageIds.size,
                    note = "stopped by user"
                )
            )
        }
        update {
            copy(
                experimentRunning = false,
                experimentPhase = "STOPPED",
                experimentStatus = "Run stopped. Do not use it as a formal replicate."
            )
        }
        DiagnosticLogger.warning("EXPERIMENT", "Formal experiment stopped by user")
    }

    private fun abortFormalRun(token: String, reason: String) {
        if (experimentToken != token) return
        val run = formalRunState
        experimentToken = null
        if (run != null) {
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = "FORMAL_RUN_ABORTED",
                    messageId = token,
                    protocol = run.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = identity.nodeId,
                    destination = run.destinationId,
                    sessionId = run.sessionId,
                    runLabel = run.runLabel,
                    conditionLabel = run.conditionLabel,
                    note = reason
                )
            )
        }
        update {
            copy(
                experimentRunning = false,
                experimentPhase = "ABORTED",
                experimentStatus = reason
            )
        }
        DiagnosticLogger.error("EXPERIMENT", reason)
    }

    private fun finalizeFormalRun(token: String, reason: String) {
        if (experimentToken != token) return
        val run = formalRunState ?: return
        if (run.finalized) return
        run.finalized = true
        run.createdMessageIds.forEach { messageId ->
            if (messageId !in run.ackedMessageIds) {
                recordFormalPacketResult(messageId, result = "NOT_DELIVERED", reason = reason)
            }
        }
        announceRun("END")
        experimentToken = null

        val acked = run.ackedMessageIds.size
        val medianLatency = if (run.ackLatenciesMs.isEmpty()) null else {
            val sorted = run.ackLatenciesMs.sorted()
            val middle = sorted.size / 2
            if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2L else sorted[middle]
        }
        val hopCounts = run.firstHopByMessage.values.groupingBy { it }.eachCount().toSortedMap()
        val stageCounts = run.stageCounts.toSortedMap()
        val note = buildString {
            append("reason=$reason")
            append("; acked=$acked/$FORMAL_PACKET_COUNT")
            append("; medianAckLatencyMs=${medianLatency ?: -1}")
            append("; firstHopCounts=${hopCounts.entries.joinToString("|") { "${it.key}:${it.value}" }}")
            append("; stageCounts=${stageCounts.entries.joinToString("|") { "${it.key}:${it.value}" }}")
        }

        research.record(
            ResearchEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                event = "FORMAL_RUN_COMPLETE",
                messageId = token,
                protocol = run.protocol.name,
                currentNode = identity.nodeId,
                sourceNode = identity.nodeId,
                destination = run.destinationId,
                sessionId = run.sessionId,
                runLabel = run.runLabel,
                conditionLabel = run.conditionLabel,
                totalPackets = FORMAL_PACKET_COUNT,
                ackedPackets = acked,
                latencyMs = medianLatency,
                success = acked == FORMAL_PACKET_COUNT,
                note = note
            )
        )
        val pdr = acked * 100.0 / FORMAL_PACKET_COUNT.toDouble()
        val hopSummary = hopCounts.entries.joinToString(" · ") { "${it.key}:${it.value}" }.ifBlank { "No first-hop decisions" }
        val stageSummary = stageCounts.entries.joinToString(" · ") { "${it.key}:${it.value}" }.ifBlank { "No stage data" }
        update {
            copy(
                experimentRunning = false,
                experimentPhase = "COMPLETE",
                experimentAcked = acked,
                experimentStatus = "Complete: $acked/$FORMAL_PACKET_COUNT delivered. Export the CSV from Files.",
                experimentPdrPercent = pdr,
                experimentMedianLatencyMs = medianLatency,
                experimentFirstHopSummary = hopSummary,
                experimentStageSummary = stageSummary
            )
        }
        DiagnosticLogger.success("EXPERIMENT", "Formal run complete: $acked/$FORMAL_PACKET_COUNT ACKs; $reason")
    }

    private fun sendExperimentPacket(
        destinationId: String,
        protocol: ProtocolType,
        runLabel: String,
        conditionLabel: String,
        sessionId: String,
        sequence: Int
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        val packet = NetworkPacket(
            messageId = "exp-${runLabel}-${sequence}-${UUID.randomUUID()}",
            sourceId = identity.nodeId,
            destinationId = destinationId,
            createdAtElapsedMs = elapsed,
            createdAtEpochMs = System.currentTimeMillis(),
            expiryAfterMs = FORMAL_PACKET_TTL_MS,
            protocol = protocol,
            type = PacketType.EXPERIMENT,
            payload = JSONObject()
                .put("sessionId", sessionId)
                .put("run", runLabel)
                .put("condition", conditionLabel)
                .put("sequence", sequence)
                .put("total", FORMAL_PACKET_COUNT)
                .toString(),
            routeTrace = listOf(identity.nodeId)
        )
        queuedPackets[packet.messageId] = packet
        packetAudits[packet.messageId] = PacketAuditState(packet = packet, createdElapsedMs = elapsed)
        formalRunState?.takeIf { it.sessionId == sessionId }?.createdMessageIds?.add(packet.messageId)
        research.record(
            ResearchEvent(
                elapsedMs = elapsed,
                event = "EXPERIMENT_PACKET_CREATED",
                messageId = packet.messageId,
                protocol = protocol.name,
                currentNode = identity.nodeId,
                sourceNode = identity.nodeId,
                destination = destinationId,
                packetType = packet.type.name,
                trafficType = "EXPERIMENT",
                sequence = sequence,
                hopCount = 0,
                sessionId = sessionId,
                runLabel = runLabel,
                conditionLabel = conditionLabel,
                totalPackets = FORMAL_PACKET_COUNT
            )
        )
        tryQueuedPacket(packet.messageId)
    }

    private data class PacketRunMeta(
        val sessionId: String?,
        val runLabel: String?,
        val conditionLabel: String?,
        val sequence: Int?,
        val totalPackets: Int?
    )

    private fun packetRunMeta(packet: NetworkPacket): PacketRunMeta? {
        val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return null
        return when (packet.type) {
            PacketType.EXPERIMENT -> PacketRunMeta(
                sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() },
                runLabel = obj.optString("run").takeIf { it.isNotBlank() },
                conditionLabel = obj.optString("condition").takeIf { it.isNotBlank() },
                sequence = obj.optInt("sequence", -1).takeIf { it > 0 },
                totalPackets = obj.optInt("total", FORMAL_PACKET_COUNT).takeIf { it > 0 }
            )
            PacketType.DELIVERY_ACK -> if (obj.optBoolean("experiment", false)) {
                PacketRunMeta(
                    sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() },
                    runLabel = obj.optString("run").takeIf { it.isNotBlank() },
                    conditionLabel = obj.optString("condition").takeIf { it.isNotBlank() },
                    sequence = obj.optInt("sequence", -1).takeIf { it > 0 },
                    totalPackets = obj.optInt("total", FORMAL_PACKET_COUNT).takeIf { it > 0 }
                )
            } else null
            PacketType.DIRECT_PROBE -> PacketRunMeta(
                sessionId = null,
                runLabel = "DIRECT_MEASUREMENT",
                conditionLabel = "DIRECT",
                sequence = obj.optInt("sequence", -1).takeIf { it > 0 },
                totalPackets = obj.optInt("total", -1).takeIf { it > 0 }
            )
            else -> null
        }
    }

    private fun onPeerDiscovered(d: BleScanner.Discovery) {
        if (d.nodeId == identity.nodeId) return
        val wasDiscovered = topology.isDirectPeer(d.nodeId)
        topology.upsertDirectPeer(d.nodeId, d.displayName, d.device.address, d.rssi)
        metrics.observeRssi(identity.nodeId, d.nodeId, d.rssi)
        if (!wasDiscovered) {
            DiagnosticLogger.info("DISCOVERY", "${d.displayName} (${d.nodeId}) advertisement discovered; verifying GATT messaging session")
        }
        if (savedPeerRepository.contains(d.nodeId)) {
            update { copy(savedPeers = savedPeerRepository.updateNameAndSeen(d.nodeId, d.displayName)) }
        }
        updatePeers()
    }

    private fun broadcastTopology() {
        val directPeers = _uiState.value.peers.filter { it.isDirect }
        val root = JSONObject(topology.snapshotJson())
            .put("linkMetrics", metrics.exportDirectMetrics(identity.nodeId, directPeers.map { it.nodeId }))
        val payload = root.toString()
        directPeers.forEach { peer ->
            val packet = NetworkPacket(
                messageId = "topology-${identity.nodeId}-${UUID.randomUUID()}",
                sourceId = identity.nodeId,
                destinationId = peer.nodeId,
                createdAtElapsedMs = SystemClock.elapsedRealtime(),
                protocol = ProtocolType.B0,
                type = PacketType.TOPOLOGY,
                payload = payload
            )
            sendDirect(peer.nodeId, packet)
        }
    }

    private fun onBleBytes(device: BluetoothDevice, bytes: ByteArray) {
        val packet = PacketCodec.decode(bytes) ?: return
        if (packet.sourceId == identity.nodeId) return

        if (packet.type == PacketType.EXPERIMENT || packet.type == PacketType.RUN_CONTROL) {
            runCatching { JSONObject(packet.payload) }.getOrNull()?.let { meta ->
                meta.optString("run").takeIf { it.isNotBlank() }?.let { research.experimentId = it }
                meta.optString("condition").takeIf { it.isNotBlank() }?.let { research.conditionLabel = it }
                meta.optString("sessionId").takeIf { it.isNotBlank() }?.let { research.sessionId = it }
                research.protocolLabel = packet.protocol.name
                research.localNodeId = identity.nodeId
            }
        }

        // Critical bidirectional mapping: the physical sender is the previous hop,
        // not necessarily the original source in a multi-hop packet.
        val previousHopNodeId = packet.previousHopId ?: packet.sourceId
        ble.rememberNodeAddress(previousHopNodeId, device)
        if (topology.isDirectPeer(previousHopNodeId)) {
            topology.touchDirectPeer(previousHopNodeId, device.address)
        } else {
            topology.upsertDirectPeer(previousHopNodeId, previousHopNodeId, device.address, -70)
            DiagnosticLogger.success("DISCOVERY", "${previousHopNodeId} confirmed by GATT contact")
        }
        // A decoded control/data packet is valid communication evidence for
        // this node's knowledge of its direct previous hop. RSSI scans and
        // successful GATT writes never refresh freshness on their own.
        metrics.observeValidCommunication(identity.nodeId, previousHopNodeId)

        if (packet.type == PacketType.HELLO) {
            val name = runCatching { JSONObject(packet.payload).optString("name", packet.sourceId) }.getOrDefault(packet.sourceId)
            topology.markPeerName(packet.sourceId, name)
            if (savedPeerRepository.contains(packet.sourceId)) {
                update { copy(savedPeers = savedPeerRepository.updateNameAndSeen(packet.sourceId, name)) }
            }
            updatePeers()
            return
        }

        if (packet.type == PacketType.TOPOLOGY) {
            topology.mergeTopology(packet.payload, packet.sourceId)
            metrics.mergeRemoteMetrics(packet.payload, packet.sourceId)
            updatePeers()
            retryQueuedMessages()
            return
        }

        DiagnosticLogger.info(
            "RX",
            "${packet.type} id=${packet.messageId.take(8)} src=${packet.sourceId} prev=${previousHopNodeId} dst=${packet.destinationId} hops=${packet.hopCount}/${packet.maxHops} trace=${packet.routeTrace.joinToString("→")} queue=${queuedPackets.size}"
        )

        if (packet.isExpired()) {
            DiagnosticLogger.warning("RX", "Expired ${packet.type} id=${packet.messageId.take(8)} at ${identity.nodeId}")
            return
        }
        if (packet.hopCount >= packet.maxHops) {
            DiagnosticLogger.warning("RX", "Hop limit ${packet.type} id=${packet.messageId.take(8)} at ${identity.nodeId}")
            return
        }

        val duplicateKey = packet.messageId + ":" + packet.type.name

        if (packet.destinationId == identity.nodeId) {
            // A direct destination can create an end-to-end ACK very quickly.
            // Send the immediate app receipt first so the sender always records
            // a real HOP_ACK before it removes the retained source packet.
            val receiptBeforeDelivery = packet.type == PacketType.CHAT || packet.type == PacketType.EXPERIMENT
            if (receiptBeforeDelivery) sendHopAck(packet, previousHopNodeId)
            when (packet.type) {
                PacketType.RUN_CONTROL -> {
                    val meta = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                    val phase = meta.optString("phase")
                    if (phase == "START") {
                        val interval = meta.optLong("intervalMs", FORMAL_INTERVAL_MS)
                        val total = meta.optInt("count", FORMAL_PACKET_COUNT)
                        val ttl = meta.optLong("ttlMs", FORMAL_PACKET_TTL_MS)
                        remoteFormalTrafficReservedUntilMs = SystemClock.elapsedRealtime() + FORMAL_WARMUP_MS + total * interval + ttl + 30_000L
                    } else if (phase == "END") {
                        remoteFormalTrafficReservedUntilMs = 0L
                    }
                    research.record(ResearchEvent(SystemClock.elapsedRealtime(),
                        if (phase == "END") "RUN_NODE_COMPLETE" else "RUN_NODE_PRESENT",
                        packet.messageId, packet.protocol.name, identity.nodeId, sourceNode = packet.sourceId,
                        sessionId = meta.optString("sessionId"), runLabel = meta.optString("run"), conditionLabel = meta.optString("condition"), note = packet.payload))
                }
                PacketType.CHAT -> {
                    val firstDelivery = !packetStore.received(duplicateKey)
                    receiveChat(packet, deliverContent = firstDelivery)
                    packetStore.remember(duplicateKey, packet.createdAtEpochMs + packet.expiryAfterMs + 60_000L)
                }
                PacketType.GROUP_CONTROL -> receiveGroupControl(packet)
                PacketType.DIRECT_PROBE -> {
                    val probe = runCatching { JSONObject(packet.payload) }.getOrNull()
                    research.record(ResearchEvent(SystemClock.elapsedRealtime(), "DIRECT_PROBE_RECEIVED", packet.messageId,
                        packet.protocol.name, identity.nodeId, sourceNode = packet.sourceId, destination = identity.nodeId,
                        packetType = packet.type.name, sequence = probe?.optInt("sequence", -1)?.takeIf { it > 0 },
                        totalPackets = probe?.optInt("total", -1)?.takeIf { it > 0 }, trafficType = "DIRECT_MEASUREMENT"))
                }
                PacketType.EXPERIMENT -> {
                    val firstDelivery = !packetStore.received(duplicateKey)
                    receiveExperiment(packet, countDelivery = firstDelivery)
                    packetStore.remember(duplicateKey, packet.createdAtEpochMs + packet.expiryAfterMs + 60_000L)
                }
                // ACK handling is already idempotent at the source. Never drop
                // a duplicate ACK here: the first ACK may have arrived while
                // source state was still being updated.
                PacketType.DELIVERY_ACK -> {
                    receiveAck(packet)
                }
                PacketType.HOP_ACK -> receiveHopAck(packet)
                else -> Unit
            }
            if (!receiptBeforeDelivery && packet.type != PacketType.HOP_ACK && packet.type != PacketType.TOPOLOGY && packet.type != PacketType.HELLO) {
                sendHopAck(packet, previousHopNodeId)
            }
        } else {
            // If this node already appears in the trace, forwarding again would
            // create a routing loop. Drop the loop regardless of duplicate age.
            if (identity.nodeId in packet.routeTrace) {
                DiagnosticLogger.warning(
                    "ROUTING",
                    "Loop prevented for ${packet.messageId.take(8)} at ${identity.nodeId}"
                )
                return
            }

            // Suppress only near-simultaneous copies (e.g. controlled backup
            // convergence). Retries after the source ACK timeout are allowed.
            if (queuedPackets.containsKey(packet.messageId)) {
                DiagnosticLogger.info("RELAY", "Already holding id=${packet.messageId.take(8)} type=${packet.type}${if (packet.type == PacketType.HOP_ACK) "" else "; receipt resent"}")
                if (packet.type != PacketType.HOP_ACK) sendHopAck(packet, previousHopNodeId)
            } else if (!relayDuplicates.firstSeen(duplicateKey)) {
                DiagnosticLogger.info("RELAY", "Duplicate suppressed id=${packet.messageId.take(8)} type=${packet.type} at ${identity.nodeId}")
            } else if (queuedPackets.size < 200) {
                queuedPackets[packet.messageId] = packet.copy(previousHopId = previousHopNodeId, custodyNextHopId = null)
                DiagnosticLogger.success("RELAY", "${if (packet.type == PacketType.HOP_ACK) "Receipt queued" else "Custody stored"} id=${packet.messageId.take(8)} from=$previousHopNodeId dst=${packet.destinationId} trace=${packet.routeTrace.joinToString("→")} queue=${queuedPackets.size}")
                if (packet.type != PacketType.HOP_ACK) sendHopAck(packet, previousHopNodeId)
                tryQueuedPacket(packet.messageId)
            } else {
                DiagnosticLogger.error("RELAY", "Queue full; not accepting id=${packet.messageId.take(8)} from=$previousHopNodeId size=${queuedPackets.size}")
            }
        }
    }

    private fun receiveChat(packet: NetworkPacket, deliverContent: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val trace = (packet.routeTrace + identity.nodeId).distinctAdjacent()
        val known = topology.allPeers().firstOrNull { it.nodeId == packet.sourceId }
        val groupMessage = groupEnvelope(packet)

        if (deliverContent) {
            if (groupMessage != null) {
                groupRepository.upsertRemote(
                    PeerGroup(
                        groupId = groupMessage.groupId,
                        name = groupMessage.groupName,
                        memberNodeIds = (groupMessage.memberIds + identity.nodeId).distinct(),
                        ownerNodeId = groupMessage.ownerId,
                        version = groupMessage.version
                    )
                )
                update { copy(groups = groupRepository.load()) }
                appendMessage(
                    ChatMessage(
                        messageId = groupMessage.groupMessageId,
                        peerId = groupMessage.groupId,
                        text = groupMessage.text,
                        outgoing = false,
                        createdAtElapsedMs = now,
                        status = MessageStatus.RECEIVED,
                        routeTrace = trace,
                        groupId = groupMessage.groupId,
                        senderId = packet.sourceId
                    )
                )
                NotificationHelper.showMessage(context, "${groupMessage.groupName} · ${known?.displayName ?: packet.sourceId}", groupMessage.text)
            } else {
                // A successfully delivered message establishes a conversation.
                if (!savedPeerRepository.contains(packet.sourceId)) {
                    savedPeerRepository.add(packet.sourceId, known?.displayName ?: packet.sourceId)
                }
                update {
                    copy(
                        savedPeers = savedPeerRepository.updateNameAndSeen(
                            packet.sourceId,
                            known?.displayName ?: packet.sourceId
                        )
                    )
                }
                appendMessage(
                    ChatMessage(
                        packet.messageId,
                        packet.sourceId,
                        packet.payload,
                        false,
                        now,
                        MessageStatus.RECEIVED,
                        routeTrace = trace
                    )
                )
                val peerName = _uiState.value.savedPeers
                    .firstOrNull { it.userId == packet.sourceId }
                    ?.displayName
                    ?: known?.displayName
                    ?: packet.sourceId
                NotificationHelper.showMessage(context, peerName, packet.payload)
            }

            research.record(
                ResearchEvent(
                    now,
                    "DELIVERED_AT_DESTINATION",
                    packet.messageId,
                    packet.protocol.name,
                    identity.nodeId,
                    destination = identity.nodeId,
                    route = trace.joinToString("->"),
                    trafficType = trafficTypeFor(packet),
                    note = groupMessage?.let { "groupId=${it.groupId}; groupMessageId=${it.groupMessageId}" }
                )
            )
        } else {
            // Idempotent destination behavior: do not show the same message
            // twice, but ACK it again because the previous ACK may have been
            // lost on the reverse path.
            DiagnosticLogger.info(
                "MESSAGE",
                "Duplicate ${packet.messageId.take(8)} received; re-sending ACK"
            )
            research.record(
                ResearchEvent(
                    elapsedMs = now,
                    event = "DUPLICATE_AT_DESTINATION",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    previousHop = packet.previousHopId,
                    destination = identity.nodeId,
                    packetType = packet.type.name,
                    hopCount = packet.hopCount,
                    route = trace.joinToString("->")
                )
            )
        }

        sendDeliveryAck(
            originalPacket = packet,
            trace = trace,
            experimentMeta = null
        )
    }


    private fun receiveExperiment(packet: NetworkPacket, countDelivery: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val trace = (packet.routeTrace + identity.nodeId).distinctAdjacent()
        val meta = runCatching { JSONObject(packet.payload) }.getOrNull()
        val run = meta?.optString("run")?.takeIf { it.isNotBlank() }
        val condition = meta?.optString("condition")?.takeIf { it.isNotBlank() }
        val session = meta?.optString("sessionId")?.takeIf { it.isNotBlank() }
        val sequence = meta?.optInt("sequence", -1)?.takeIf { it > 0 }
        val total = meta?.optInt("total", FORMAL_PACKET_COUNT)?.takeIf { it > 0 }

        if (countDelivery) {
            research.record(
                ResearchEvent(
                    elapsedMs = now,
                    event = "EXPERIMENT_DELIVERED_AT_DESTINATION",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    previousHop = packet.previousHopId,
                    destination = identity.nodeId,
                    packetType = packet.type.name,
                    sequence = sequence,
                    hopCount = packet.hopCount,
                    route = trace.joinToString("->"),
                    sessionId = session,
                    runLabel = run,
                    conditionLabel = condition,
                    totalPackets = total
                )
            )
        } else {
            research.record(
                ResearchEvent(
                    elapsedMs = now,
                    event = "EXPERIMENT_DUPLICATE_AT_DESTINATION",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    previousHop = packet.previousHopId,
                    destination = identity.nodeId,
                    packetType = packet.type.name,
                    sequence = sequence,
                    hopCount = packet.hopCount,
                    route = trace.joinToString("->"),
                    sessionId = session,
                    runLabel = run,
                    conditionLabel = condition,
                    totalPackets = total,
                    note = "Duplicate payload suppressed; ACK re-sent"
                )
            )
        }

        sendDeliveryAck(
            originalPacket = packet,
            trace = trace,
            experimentMeta = ExperimentAckMeta(
                sessionId = session,
                runLabel = run,
                conditionLabel = condition,
                sequence = sequence,
                totalPackets = total ?: FORMAL_PACKET_COUNT
            )
        )
    }

    private data class ExperimentAckMeta(
        val sessionId: String?,
        val runLabel: String?,
        val conditionLabel: String?,
        val sequence: Int?,
        val totalPackets: Int
    )

    private fun sendDeliveryAck(
        originalPacket: NetworkPacket,
        trace: List<String>,
        experimentMeta: ExperimentAckMeta?
    ) {
        val now = SystemClock.elapsedRealtime()
        val ackPayload = JSONObject()
            .put("originalId", originalPacket.messageId)
            .put("originalCreated", originalPacket.createdAtElapsedMs)
            .put("originalEpoch", originalPacket.createdAtEpochMs)
            .put("route", trace.joinToString("->"))
            .put("trafficType", trafficTypeFor(originalPacket))

        if (experimentMeta != null) {
            ackPayload
                .put("experiment", true)
                .put("sessionId", experimentMeta.sessionId ?: "")
                .put("run", experimentMeta.runLabel ?: "")
                .put("condition", experimentMeta.conditionLabel ?: "")
                .put("sequence", experimentMeta.sequence ?: -1)
                .put("total", experimentMeta.totalPackets)
        }

        val ack = NetworkPacket(
            messageId = "ack-${originalPacket.messageId}",
            sourceId = identity.nodeId,
            destinationId = originalPacket.sourceId,
            createdAtElapsedMs = now,
            protocol = originalPacket.protocol,
            type = PacketType.DELIVERY_ACK,
            payload = ackPayload.toString(),
            routeTrace = listOf(identity.nodeId)
        )
        if (queuedPackets.size < 220 && !queuedPackets.containsKey(ack.messageId)) queuedPackets[ack.messageId] = ack
        tryQueuedPacket(ack.messageId)
    }

    private fun receiveAck(packet: NetworkPacket) {
        val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
        val originalId = obj.optString("originalId")
        val created = obj.optLong("originalCreated", 0L)
        val route = obj.optString("route")
        val isExperiment = obj.optBoolean("experiment", false) || originalId.startsWith("exp-")
        val session = obj.optString("sessionId").takeIf { it.isNotBlank() }
        val runLabel = obj.optString("run").takeIf { it.isNotBlank() }
        val condition = obj.optString("condition").takeIf { it.isNotBlank() }
        val sequence = obj.optInt("sequence", -1).takeIf { it > 0 }
        val total = obj.optInt("total", FORMAL_PACKET_COUNT).takeIf { it > 0 }
        val now = SystemClock.elapsedRealtime()
        val originalEpoch = obj.optLong("originalEpoch", 0L)
        val elapsedDelta = now - created
        val wallDelta = System.currentTimeMillis() - originalEpoch
        val latency = if (created > 0 && originalEpoch > 0 && elapsedDelta >= 0 && kotlin.math.abs(elapsedDelta - wallDelta) < 5_000L) elapsedDelta else null
        val originalPacket = queuedPackets[originalId]
        val groupMessage = originalPacket?.let(::groupEnvelope)

        stageRecoveries.remove(originalId)?.let { recovery ->
            metrics.cancelHopAttempt(originalId, identity.nodeId, recovery.primaryHopId)
            metrics.cancelHopAttempt(originalId, identity.nodeId, recovery.backupHopId)
        }
        queuedPackets.remove(originalId)
        nextAttemptAt.remove(originalId)
        lowWaiting.remove(originalId)
        carryCounts.remove(originalId)
        inFlightSourcePackets.remove(originalId)
        ackRetryScheduled.remove(originalId)

        if (!isExperiment) {
            if (groupMessage != null) {
                updateGroupRecipientStatus(groupMessage.groupId, groupMessage.groupMessageId, packet.sourceId, MessageStatus.DELIVERED)
            } else {
                updateMessages { list -> list.map { m ->
                    if (m.messageId == originalId) {
                        m.copy(
                            status = MessageStatus.DELIVERED,
                            latencyMs = latency,
                            routeTrace = route.split("->").filter { it.isNotBlank() }
                        )
                    } else m
                } }
            }
        } else {
            val formal = formalRunState
            if (
                formal != null && !formal.finalized &&
                session == formal.sessionId &&
                originalId in formal.createdMessageIds &&
                formal.ackedMessageIds.add(originalId)
            ) {
                latency?.let { formal.ackLatenciesMs.add(it) }
                update { copy(experimentAcked = formal.ackedMessageIds.size) }
                if (formal.txComplete && formal.ackedMessageIds.size >= FORMAL_PACKET_COUNT) {
                    experimentToken?.let { token -> finalizeFormalRun(token, "all ACKs received") }
                }
            }
        }

        if (isExperiment) {
            recordFormalPacketResult(
                messageId = originalId,
                result = "DELIVERED",
                latencyMs = latency,
                route = route,
                reason = "end-to-end delivery ACK"
            )
        }
        research.record(
            ResearchEvent(
                elapsedMs = now,
                event = if (isExperiment) "EXPERIMENT_END_TO_END_ACK" else "END_TO_END_ACK",
                messageId = originalId,
                protocol = packet.protocol.name,
                currentNode = identity.nodeId,
                sourceNode = if (isExperiment) identity.nodeId else null,
                previousHop = packet.previousHopId,
                destination = identity.nodeId,
                packetType = packet.type.name,
                trafficType = obj.optString("trafficType").takeIf { it.isNotBlank() } ?: trafficTypeFor(packet),
                sequence = sequence,
                hopCount = packet.hopCount,
                latencyMs = latency,
                route = route,
                sessionId = session,
                runLabel = runLabel,
                conditionLabel = condition,
                totalPackets = total,
                ackedPackets = formalRunState?.takeIf { it.sessionId == session }?.ackedMessageIds?.size
            )
        )
        DiagnosticLogger.success("MESSAGE", "Delivered $originalId${latency?.let { " in ${it} ms" } ?: ""}")
    }

    /** Receipt means the immediate next app decoded and persisted the packet, not end-to-end delivery. */
    private fun sendHopAck(received: NetworkPacket, previousHopId: String) {
        if (previousHopId.isBlank() || previousHopId == identity.nodeId) return
        val now = SystemClock.elapsedRealtime()
        val ack = NetworkPacket(
            messageId = "hop-${received.messageId}-${identity.nodeId}",
            sourceId = identity.nodeId,
            destinationId = previousHopId,
            createdAtElapsedMs = now,
            protocol = received.protocol,
            type = PacketType.HOP_ACK,
            payload = JSONObject()
                .put("originalId", received.messageId)
                .put("storedAt", identity.nodeId)
                .put("trafficType", trafficTypeFor(received))
                .toString(),
            routeTrace = listOf(identity.nodeId)
        )
        if (!queuedPackets.containsKey(ack.messageId) && queuedPackets.size < 220) queuedPackets[ack.messageId] = ack
        if (!queuedPackets.containsKey(ack.messageId)) {
            DiagnosticLogger.error("HOP-ACK", "Could not queue receipt for ${received.messageId.take(8)}; queue full")
            return
        }
        if (ack.messageId in inFlightSourcePackets) return
        DiagnosticLogger.info("HOP-ACK", "Created receipt for ${received.messageId.take(8)} toward=$previousHopId type=${received.type}")
        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "HOP_ACK_CREATED", received.messageId, received.protocol.name, identity.nodeId,
            sourceNode = received.sourceId, previousHop = received.previousHopId, nextHop = previousHopId, destination = received.destinationId,
            packetType = received.type.name, hopCount = received.hopCount, route = received.routeTrace.joinToString("->"), note = "receiptId=${ack.messageId}"))
        if (ble.connectionState(previousHopId) == "Ready") {
            sendForward(ack, previousHopId, "HOP-ACK", null)
        } else {
            DiagnosticLogger.warning("HOP-ACK", "Previous hop $previousHopId unavailable; receipt queued for ${received.messageId.take(8)}")
            tryQueuedPacket(ack.messageId)
        }
    }

    private fun receiveHopAck(packet: NetworkPacket) {
        val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
        val originalId = obj.optString("originalId")
        val held = queuedPackets[originalId]
        if (held == null) {
            DiagnosticLogger.info("HOP-ACK", "Late/duplicate receipt id=${originalId.take(8)} from=${packet.sourceId}; no custody copy")
            return
        }
        val recovery = stageRecoveries[originalId]
        val recoveryHopAccepted = recovery != null &&
            recovery.resolvedByHopId == null &&
            packet.sourceId in setOf(recovery.primaryHopId, recovery.backupHopId)

        if (recovery != null && !recoveryHopAccepted && packet.sourceId !in setOf(recovery.primaryHopId, recovery.backupHopId)) {
            DiagnosticLogger.warning("HOP-ACK", "Unexpected recovery receipt id=${originalId.take(8)} from=${packet.sourceId}")
            return
        }
        if (recoveryHopAccepted) {
            recovery.resolvedByHopId = packet.sourceId
            stageRecoveries.remove(originalId)
            metrics.completeHopAttempt(originalId, identity.nodeId, packet.sourceId, receiptReceived = true)
            setOf(recovery.primaryHopId, recovery.backupHopId)
                .filter { it != packet.sourceId }
                .forEach { metrics.cancelHopAttempt(originalId, identity.nodeId, it) }
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = "RECOVERY_HOP_ACK_RESOLVED",
                    messageId = originalId,
                    protocol = held.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = held.sourceId,
                    nextHop = packet.sourceId,
                    destination = held.destinationId,
                    packetType = held.type.name,
                    stage = recovery.stage,
                    rawStage = recovery.rawStage,
                    decisionStage = recovery.stage,
                    success = true,
                    backupHop = recovery.backupHopId,
                    trafficType = trafficTypeFor(held),
                    sessionId = recovery.sessionId,
                    note = "Resolved by application HOP_ACK; backupActivations=${recovery.backupActivationCount}"
                )
            )
        }
        if (held.sourceId == identity.nodeId && held.type in setOf(PacketType.CHAT, PacketType.EXPERIMENT)) {
            if (!recoveryHopAccepted && held.custodyNextHopId != packet.sourceId) {
                DiagnosticLogger.warning("HOP-ACK", "Unexpected source receipt id=${originalId.take(8)} from=${packet.sourceId}; expected=${held.custodyNextHopId ?: "unknown"}")
                return
            }
            // A hop receipt proves the first relay stored the packet. Give its
            // onward transfer and the end-to-end ACK time to complete before
            // retransmitting the source copy.
            if (!recoveryHopAccepted) metrics.completeHopAttempt(originalId, identity.nodeId, packet.sourceId, receiptReceived = true)
            inFlightSourcePackets.remove(originalId)
            nextAttemptAt[originalId] = SystemClock.elapsedRealtime() + END_TO_END_ACK_GRACE_MS
            DiagnosticLogger.info("HOP-ACK", "First-hop receipt id=${originalId.take(8)} from=${packet.sourceId}; end-to-end copy retained")
            research.record(ResearchEvent(SystemClock.elapsedRealtime(), "HOP_ACK_RECEIVED", originalId, held.protocol.name, identity.nodeId,
                sourceNode = held.sourceId, previousHop = packet.previousHopId, nextHop = packet.sourceId, destination = held.destinationId,
                packetType = held.type.name, hopCount = held.hopCount, route = held.routeTrace.joinToString("->"), success = true,
                note = "first-hop app receipt; source retains end-to-end copy"))
            return
        }
        if (held.type == PacketType.HOP_ACK) return
        if (!recoveryHopAccepted && held.custodyNextHopId != packet.sourceId) {
            DiagnosticLogger.warning("HOP-ACK", "Unexpected receipt id=${originalId.take(8)} from=${packet.sourceId}; expected=${held.custodyNextHopId ?: "unknown"}")
            return
        }
        if (!recoveryHopAccepted) metrics.completeHopAttempt(originalId, identity.nodeId, packet.sourceId, receiptReceived = true)
        queuedPackets.remove(originalId)
        inFlightSourcePackets.remove(originalId)
        nextAttemptAt.remove(originalId)
        hopRetryScheduled.remove(originalId)
        DiagnosticLogger.success("HOP-ACK", "Custody handed off id=${originalId.take(8)} to=${packet.sourceId}; destination=${held.destinationId}")
        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "HOP_ACK_RECEIVED", originalId, held.protocol.name, identity.nodeId,
            sourceNode = held.sourceId, previousHop = held.previousHopId, nextHop = packet.sourceId, destination = held.destinationId,
            packetType = held.type.name, hopCount = held.hopCount, route = held.routeTrace.joinToString("->"), success = true,
            note = "durable custody released after next-app receipt"))
    }

    private fun retryQueuedMessages(readyNodeId: String? = null) {
        val ids = queuedPackets.values.sortedWith(compareBy<NetworkPacket> { if (it.type == PacketType.DELIVERY_ACK || it.type == PacketType.HOP_ACK) 0 else 1 }.thenBy { it.createdAtEpochMs })
            .map { it.messageId }
        if (readyNodeId != null) ids.forEach { id ->
            val packet = queuedPackets[id] ?: return@forEach
            val active = (activeAttempts[id] ?: 0) > 0
            val readyLinkRelevant = readyNodeId == packet.destinationId || readyNodeId == packet.custodyNextHopId
            if (!active && readyLinkRelevant && inFlightSourcePackets.remove(id)) {
                nextAttemptAt.remove(id)
                DiagnosticLogger.info("QUEUE", "Ready peer=$readyNodeId wakes pending id=${id.take(8)} dst=${packet.destinationId}; route reevaluated now")
            } else if (id !in inFlightSourcePackets) {
                // A previously carried/no-route packet must not wait for its old LOW cooldown.
                nextAttemptAt.remove(id)
            }
        }
        ids.forEach(::tryQueuedPacket)
    }

    private fun tryQueuedPacket(messageId: String) {
        if (!networkRunning) return
        val packet = queuedPackets[messageId] ?: return
        if (packet.isExpired()) {
            dropPending(packet, "expired")
            return
        }
        // Do not enqueue the same source packet again while its previous send
        // is still travelling through BLE / waiting for the end-to-end ACK.
        if (messageId in inFlightSourcePackets || SystemClock.elapsedRealtime() < (nextAttemptAt[messageId] ?: 0L)) return
        nextAttemptAt[messageId] = SystemClock.elapsedRealtime() + 2_000L
        if (packet.type == PacketType.CHAT) markPacketChatStatus(packet, MessageStatus.SENDING)
        processOutgoing(packet)
    }

    private fun processOutgoing(packet: NetworkPacket) {
        if (packet.isExpired() || packet.hopCount >= packet.maxHops) return queueOrFailIfMine(packet)
        if (packet.type == PacketType.DIRECT_PROBE) {
            if (ble.connectionState(packet.destinationId) == "Ready") {
                val q = metrics.snapshot(identity.nodeId, packet.destinationId)?.confidence
                sendForward(packet, packet.destinationId, "MEASUREMENT_PROBE", q)
            } else {
                research.record(ResearchEvent(SystemClock.elapsedRealtime(), "DIRECT_PROBE_NO_READY_LINK", packet.messageId,
                    packet.protocol.name, identity.nodeId, sourceNode = packet.sourceId, destination = packet.destinationId,
                    packetType = packet.type.name, success = false, trafficType = "DIRECT_MEASUREMENT",
                    note = "Probe was not relayed."))
                dropPending(packet, "direct link unavailable")
            }
            return
        }
        val graph = topology.graphSnapshot()
        // A discovered but unusable local link cannot beat a ready relay route.
        graph.getNeighbors(identity.nodeId).toList().forEach { edge ->
            if (ble.connectionState(edge.to) != "Ready") graph.removeEdge(identity.nodeId, edge.to)
        }

        if (packet.type == PacketType.EXPERIMENT && packet.sourceId == identity.nodeId) {
            val meta = packetRunMeta(packet)
            router.twoHopCandidates(graph, identity.nodeId, packet.destinationId).forEach { candidate ->
                research.record(
                    ResearchEvent(
                        elapsedMs = SystemClock.elapsedRealtime(),
                        event = "ROUTE_CANDIDATE",
                        messageId = packet.messageId,
                        protocol = packet.protocol.name,
                        currentNode = identity.nodeId,
                        sourceNode = packet.sourceId,
                        previousHop = packet.previousHopId,
                        nextHop = candidate.relayId,
                        destination = packet.destinationId,
                        packetType = packet.type.name,
                        sequence = meta?.sequence,
                        hopCount = packet.hopCount,
                        confidence = candidate.firstHopConfidence,
                        routeConfidence = candidate.routeConfidence,
                        routeCost = candidate.totalCost,
                        route = candidate.path.joinToString("->"),
                        sessionId = meta?.sessionId,
                        runLabel = meta?.runLabel,
                        conditionLabel = meta?.conditionLabel,
                        totalPackets = meta?.totalPackets,
                        note = "firstHopCost=${candidate.firstHopCost}; secondHopCost=${candidate.secondHopCost}; secondHopQ=${candidate.secondHopConfidence ?: -1.0}"
                    )
                )
            }
        }

        val control = packet.type == PacketType.RUN_CONTROL
        val decision = router.decide(if (control) ProtocolType.B0 else packet.protocol, graph, identity.nodeId, packet.destinationId, packet.previousHopId)
        recordRoutingDecision(packet, decision)
        if (!networkRunning || !queuedPackets.containsKey(packet.messageId)) return
        when (decision) {
            is ForwardingDecision.Forward -> {
                lowWaiting.remove(packet.messageId)
                carryCounts.remove(packet.messageId)
                queuedPackets[packet.messageId] = packet.copy(lowReevaluations = 0)
                sendForward(packet.copy(lowReevaluations = 0), decision.nextHopId, decision.stage, decision.confidence)
            }
            is ForwardingDecision.ForwardWithBackup -> {
                lowWaiting.remove(packet.messageId)
                carryCounts.remove(packet.messageId)
                val backup = decision.backupHopId?.takeIf { packet.copyBudgetRemaining > 0 }
                if (backup == null) {
                    sendForward(packet, decision.primaryHopId, decision.stage, decision.confidence)
                } else {
                    // Reserve the single copy before either branch leaves this node.
                    val budgeted = packet.copy(copyBudgetRemaining = packet.copyBudgetRemaining - 1, lowReevaluations = 0)
                    queuedPackets[packet.messageId] = budgeted
                    startStageRecovery(budgeted, decision, backup)
                }
            }
            is ForwardingDecision.Carry -> {
                if (lowWaiting.remove(packet.messageId)) {
                    val probeHop = router.probeNextHop(graph, identity.nodeId, packet.destinationId)
                    if (probeHop != null) {
                        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "LOW_PROBE", packet.messageId, packet.protocol.name, identity.nodeId, nextHop = probeHop, confidence = decision.confidence))
                        sendForward(packet, probeHop, "LOW-PROBE", decision.confidence)
                    } else queueOrFailIfMine(packet)
                } else {
                    val count = packet.lowReevaluations + 1
                    queuedPackets[packet.messageId] = packet.copy(lowReevaluations = count)
                    carryCounts[packet.messageId] = count
                    if (count <= 3) {
                        lowWaiting.add(packet.messageId)
                        nextAttemptAt[packet.messageId] = SystemClock.elapsedRealtime() + decision.delayMs
                        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "CARRY", packet.messageId, packet.protocol.name, identity.nodeId, destination = packet.destinationId, stage = "LOW", confidence = decision.confidence))
                    } else if (packet.type == PacketType.EXPERIMENT) {
                        dropPending(packet, "LOW budget exhausted")
                    } else {
                        // A new chat recovery episode is paced; research packets never get this extension.
                        carryCounts.remove(packet.messageId)
                        queuedPackets[packet.messageId] = packet.copy(lowReevaluations = 0)
                        nextAttemptAt[packet.messageId] = SystemClock.elapsedRealtime() + 30_000L
                        queueOrFailIfMine(packet)
                    }
                }
            }
            is ForwardingDecision.Drop -> queueOrFailIfMine(packet)
        }
    }

    /**
     * Executes CARBLE M2/M3 recovery from receipt evidence. A Bluetooth write
     * completion only says Android accepted bytes; it cannot resolve this
     * state. The receiver's HOP_ACK, handled in receiveHopAck(), resolves it.
     */
    private fun startStageRecovery(
        packet: NetworkPacket,
        decision: ForwardingDecision.ForwardWithBackup,
        backupHopId: String
    ) {
        val meta = packetRunMeta(packet)
        val epoch = networkEpoch
        val state = StageRecoveryState(
            packet = packet,
            primaryHopId = decision.primaryHopId,
            backupHopId = backupHopId,
            stage = decision.stage,
            confidence = decision.confidence,
            rawStage = decision.rawStage,
            sessionId = meta?.sessionId
        )
        stageRecoveries[packet.messageId] = state

        fun activateBackup(reason: String) {
            val active = stageRecoveries[packet.messageId] ?: return
            if (active.backupActivated || active.resolvedByHopId != null || epoch != networkEpoch || !queuedPackets.containsKey(packet.messageId)) return
            active.backupActivated = true
            active.backupActivationCount++
            packetAudits[packet.messageId]?.let { it.backupActivations++ }
            // The primary missed the M2/M3 receipt window. Record that real
            // timeout before launching the bounded alternative; a late receipt
            // is not allowed to overwrite this deadline evidence.
            metrics.completeHopAttempt(packet.messageId, identity.nodeId, active.primaryHopId, receiptReceived = false)
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = "BACKUP_ACTIVATED",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    nextHop = backupHopId,
                    destination = packet.destinationId,
                    packetType = packet.type.name,
                    stage = decision.stage,
                    rawStage = decision.rawStage,
                    decisionStage = decision.stage,
                    backupHop = backupHopId,
                    trafficType = trafficTypeFor(packet),
                    sessionId = meta?.sessionId,
                    runLabel = meta?.runLabel,
                    conditionLabel = meta?.conditionLabel,
                    note = "reason=$reason; primary=${active.primaryHopId}; receipt-based recovery"
                )
            )
            sendForward(packet, backupHopId, decision.stage, decision.confidence) { success ->
                if (!success && stageRecoveries[packet.messageId]?.resolvedByHopId == null) {
                    stageRecoveries.remove(packet.messageId)
                    queueOrFailIfMine(packet)
                }
            }
        }

        sendForward(packet, decision.primaryHopId, decision.stage, decision.confidence) { success ->
            if (!success) activateBackup("PRIMARY_GATT_FAILURE")
        }
        val receiptWaitMs = if (decision.stage == "M3") decision.backupDelayMs else M2_HOP_ACK_WAIT_MS
        handler.postDelayed({ activateBackup(if (decision.stage == "M3") "M3_DELAY_ELAPSED" else "M2_PRIMARY_HOP_ACK_TIMEOUT") }, receiptWaitMs)
    }

    /** One canonical, source-side result row per formal packet. */
    private fun recordFormalPacketResult(
        messageId: String,
        result: String,
        latencyMs: Long? = null,
        route: String? = null,
        reason: String? = null
    ) {
        val audit = packetAudits[messageId] ?: return
        if (audit.resultRecorded) return
        val meta = packetRunMeta(audit.packet) ?: return
        audit.resultRecorded = true
        val now = SystemClock.elapsedRealtime()
        research.record(
            ResearchEvent(
                elapsedMs = now,
                event = "EXPERIMENT_PACKET_RESULT",
                messageId = messageId,
                protocol = audit.packet.protocol.name,
                currentNode = identity.nodeId,
                sourceNode = identity.nodeId,
                destination = audit.packet.destinationId,
                packetType = PacketType.EXPERIMENT.name,
                sequence = meta.sequence,
                rawStage = audit.rawStage,
                decisionStage = audit.decisionStage,
                stage = audit.decisionStage,
                nextHop = audit.primaryHopId,
                backupHop = audit.backupHopId,
                route = route,
                trafficType = "EXPERIMENT",
                latencyMs = latencyMs,
                totalPackets = meta.totalPackets,
                finalResult = result,
                attemptCount = audit.attempts,
                retransmissionCount = (audit.attempts - 1).coerceAtLeast(0),
                backupActivationCount = audit.backupActivations,
                queueResidenceMs = (now - audit.createdElapsedMs).coerceAtLeast(0L),
                deliveredBeforeTtl = result == "DELIVERED" && now - audit.createdElapsedMs <= audit.packet.expiryAfterMs,
                sessionId = meta.sessionId,
                runLabel = meta.runLabel,
                conditionLabel = meta.conditionLabel,
                note = reason
            )
        )
    }



    private fun recordRoutingDecision(packet: NetworkPacket, decision: ForwardingDecision) {
        val nextHop: String?
        val backupHop: String?
        val stage: String
        val confidence: Double?
        val route: List<String>
        val routeConfidence: Double?
        val backupRoute: List<String>
        val rawStage: String?
        val hysteresisStatus: String?
        val routeCost: Double?
        val routeReason: String?

        when (decision) {
            is ForwardingDecision.Forward -> {
                nextHop = decision.nextHopId
                backupHop = null
                stage = decision.stage
                confidence = decision.confidence
                route = decision.route
                routeConfidence = decision.routeConfidence
                backupRoute = emptyList()
                rawStage = decision.rawStage
                hysteresisStatus = decision.hysteresisStatus
                routeCost = decision.routeCost
                routeReason = decision.routeReason
            }
            is ForwardingDecision.ForwardWithBackup -> {
                nextHop = decision.primaryHopId
                backupHop = decision.backupHopId
                stage = decision.stage
                confidence = decision.confidence
                route = decision.route
                routeConfidence = decision.routeConfidence
                backupRoute = decision.backupRoute
                rawStage = decision.rawStage
                hysteresisStatus = decision.hysteresisStatus
                routeCost = decision.routeCost
                routeReason = decision.routeReason
            }
            is ForwardingDecision.Carry -> {
                nextHop = null
                backupHop = null
                stage = decision.stage
                confidence = decision.confidence
                route = decision.route
                routeConfidence = decision.routeConfidence
                backupRoute = emptyList()
                rawStage = decision.rawStage
                hysteresisStatus = decision.hysteresisStatus
                routeCost = decision.routeCost
                routeReason = decision.routeReason
            }
            is ForwardingDecision.Drop -> {
                nextHop = null
                backupHop = null
                stage = decision.stage
                confidence = null
                route = emptyList()
                routeConfidence = null
                backupRoute = emptyList()
                rawStage = null
                hysteresisStatus = null
                routeCost = null
                routeReason = null
            }
        }

        val snap = nextHop?.let { metrics.snapshot(identity.nodeId, it) }
        val meta = packetRunMeta(packet)
        val trace = router.latestRouteTrace()
        val stageReason = carbleStageReason(stage, confidence, routeConfidence)
        val forwardingAction = when (stage) {
            "HIGH" -> "FORWARD_SELECTED_MM_ROUTE"
            "M1" -> "FORWARD_PRIMARY_AND_MONITOR"
            "M2" -> if (backupHop != null) "PRIMARY_THEN_BACKUP_ON_HOP_ACK_TIMEOUT" else "PRIMARY_ONLY_BACKUP_UNAVAILABLE"
            "M3" -> if (backupHop != null) "PRIMARY_WITH_DELAYED_BACKUP" else "PRIMARY_ONLY_BACKUP_UNAVAILABLE"
            "LOW" -> "STORE_CARRY_AND_REEVALUATE"
            else -> if (nextHop != null) "FORWARD" else "QUEUE_OR_DROP"
        }
        val candidateSummaries = trace?.candidates.orEmpty().map { candidate ->
            val linkEvidence = candidate.links.joinToString(";") { link ->
                "${link.from}->${link.to}[cost=${"%.4f".format(java.util.Locale.US, link.cost)},Q=${link.q?.let { "%.4f".format(java.util.Locale.US, it) } ?: "NA"},rel=${"%.3f".format(java.util.Locale.US, link.reliabilityPenalty)},delay=${"%.3f".format(java.util.Locale.US, link.delayPenalty)},queue=${"%.3f".format(java.util.Locale.US, link.queuePenalty)},instability=${"%.3f".format(java.util.Locale.US, link.instabilityPenalty)},resource=${"%.3f".format(java.util.Locale.US, link.resourcePenalty)}]"
            }
            "${if (candidate.selected) "SELECTED " else ""}${candidate.path.joinToString("->")} cost=${"%.4f".format(java.util.Locale.US, candidate.totalCost)} routeQ=${candidate.routeQ?.let { "%.4f".format(java.util.Locale.US, it) } ?: "NA"} {$linkEvidence}"
        }
        val qComponents = snap?.let {
            "D=${"%.3f".format(java.util.Locale.US, it.deliverySuccess)}, F=${"%.3f".format(java.util.Locale.US, it.freshness)}, R=${"%.3f".format(java.util.Locale.US, it.stability)}, T=${"%.3f".format(java.util.Locale.US, it.timeliness)}, S=${"%.3f".format(java.util.Locale.US, it.signalReliability)}, B=${"%.3f".format(java.util.Locale.US, it.resourceSuitability)}"
        }
        if (packet.type == PacketType.CHAT || packet.type == PacketType.EXPERIMENT) {
            update {
                copy(
                    lastDecision = "$stage · $forwardingAction",
                    liveDecisionMessageId = packet.messageId,
                    liveDecisionDestination = packet.destinationId,
                    liveMmSelectedRoute = route.joinToString(" → ").takeIf { it.isNotBlank() }
                        ?: trace?.selectedPath?.joinToString(" → "),
                    liveMmTotalCost = routeCost ?: trace?.totalCost,
                    liveMmReason = routeReason ?: trace?.reason,
                    liveMmCandidates = candidateSummaries,
                    liveCarbleStage = stage,
                    liveCarbleStageReason = stageReason,
                    liveCurrentHopQ = confidence,
                    liveRouteQ = routeConfidence ?: trace?.routeQ,
                    liveQComponents = qComponents,
                    liveForwardingAction = forwardingAction,
                    livePrimaryHop = nextHop,
                    liveBackupHop = backupHop,
                    liveQueueEvidence = snap?.let { "depth=${it.queueOccupancy}, k=${"%.3f".format(java.util.Locale.US, it.queueScaleK)}, pressure=${"%.3f".format(java.util.Locale.US, it.queuePressure)}" }
                )
            }
        }

        research.record(
            ResearchEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                event = "ROUTE_DECISION",
                messageId = packet.messageId,
                protocol = packet.protocol.name,
                currentNode = identity.nodeId,
                sourceNode = packet.sourceId,
                previousHop = packet.previousHopId,
                nextHop = nextHop,
                destination = packet.destinationId,
                packetType = packet.type.name,
                sequence = meta?.sequence,
                hopCount = packet.hopCount,
                rawStage = rawStage ?: stage,
                decisionStage = stage,
                hysteresisStatus = hysteresisStatus,
                stage = stage,
                qRaw = confidence,
                qDecision = confidence,
                confidence = confidence,
                route = route.joinToString("->"),
                backupHop = backupHop,
                backupRoute = backupRoute.joinToString("->"),
                routeConfidence = routeConfidence,
                routeCost = routeCost ?: trace?.totalCost,
                d = snap?.deliverySuccess,
                f = snap?.freshness,
                r = snap?.stability,
                t = snap?.timeliness,
                s = snap?.signalReliability,
                b = snap?.resourceSuitability,
                rssiDbm = snap?.rssiDbm,
                rawRssiDbm = snap?.rawRssiDbm,
                rssiSampleCount = snap?.rssiSampleCount,
                deliveryAttempts = snap?.deliveryAttempts,
                hopAckSuccesses = snap?.hopAckSuccesses,
                medianHopAckRttMs = snap?.medianAckRttMs,
                medianGattWriteMs = snap?.medianGattWriteMs,
                freshnessAgeMs = snap?.freshnessAgeMs,
                instabilityEvents = snap?.instabilityEvents,
                gattFailureCount = snap?.gattFailureCount,
                ackTimeoutCount = snap?.ackTimeoutCount,
                disconnectCount = snap?.disconnectCount,
                measurementStatus = snap?.measurementStatus,
                bSource = "CONTROLLED_CONSTANT",
                trafficType = trafficTypeFor(packet),
                queueDepth = snap?.queueOccupancy,
                queueCapacity = snap?.queueCapacity,
                queueScaleK = snap?.queueScaleK,
                queuePressure = snap?.queuePressure,
                mmReason = routeReason ?: trace?.reason,
                carbleReason = stageReason,
                forwardingAction = forwardingAction,
                candidateRoutes = candidateSummaries.joinToString(" || "),
                sessionId = meta?.sessionId,
                runLabel = meta?.runLabel,
                conditionLabel = meta?.conditionLabel,
                totalPackets = meta?.totalPackets
            )
        )

        if (packet.type == PacketType.EXPERIMENT && packet.sourceId == identity.nodeId) {
            packetAudits[packet.messageId]?.let { audit ->
                audit.rawStage = audit.rawStage ?: rawStage ?: stage
                audit.decisionStage = audit.decisionStage ?: stage
                audit.primaryHopId = audit.primaryHopId ?: nextHop
                audit.backupHopId = audit.backupHopId ?: backupHop
            }
            formalRunState?.takeIf { meta?.sessionId == it.sessionId }?.let { run ->
                if (nextHop != null) run.firstHopByMessage.putIfAbsent(packet.messageId, nextHop)
                if (run.stageRecordedMessageIds.add(packet.messageId)) {
                    run.stageCounts[stage] = (run.stageCounts[stage] ?: 0) + 1
                }
            }
            update {
                copy(
                    lastExperimentRawStage = rawStage ?: stage,
                    lastExperimentStage = stage,
                    lastExperimentHysteresisStatus = hysteresisStatus,
                    lastExperimentConfidence = confidence,
                    lastExperimentComponents = snap?.let {
                        "D=${"%.2f".format(it.deliverySuccess)}  F=${"%.2f".format(it.freshness)}  R=${"%.2f".format(it.stability)}  T=${"%.2f".format(it.timeliness)}  S=${"%.2f".format(it.signalReliability)}  B=${"%.2f".format(it.resourceSuitability)}"
                    },
                    lastExperimentPrimaryHop = nextHop,
                    lastExperimentBackupHop = backupHop,
                    lastExperimentRoute = route.joinToString(" → ")
                )
            }
        }

        update { copy(lastDecision = "${packet.protocol.name} $stage · Q=${confidence?.let { "%.3f".format(it) } ?: "unknown"} · next=${nextHop ?: "waiting"}") }
        if (nextHop != null) {
            DiagnosticLogger.info(
                "ROUTING",
                "${packet.protocol.name} $stage Q=${confidence?.let { "%.3f".format(it) } ?: "-"} primary=$nextHop backup=${backupHop ?: "-"} route=${route.joinToString("→")}"
            )
        }

        val key = "${packet.protocol}:${identity.nodeId}:${packet.destinationId}:${packet.type}:${meta?.sessionId ?: "manual"}"
        val previous = lastStageByRoute.put(key, stage)
        if (previous != null && previous != stage) {
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = "STAGE_TRANSITION",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    previousHop = packet.previousHopId,
                    nextHop = nextHop,
                    destination = packet.destinationId,
                    packetType = packet.type.name,
                    sequence = meta?.sequence,
                    hopCount = packet.hopCount,
                    stage = "$previous->$stage",
                    confidence = confidence,
                    routeConfidence = routeConfidence,
                    backupHop = backupHop,
                    route = route.joinToString("->"),
                    sessionId = meta?.sessionId,
                    runLabel = meta?.runLabel,
                    conditionLabel = meta?.conditionLabel,
                    totalPackets = meta?.totalPackets
                )
            )
        }
    }

    private fun carbleStageReason(stage: String, hopQ: Double?, routeQ: Double?): String {
        val measured = hopQ?.let { "current Q=${"%.4f".format(java.util.Locale.US, it)}" } ?: "current Q unavailable"
        val routeMeasured = routeQ?.let { "route Q=${"%.4f".format(java.util.Locale.US, it)}" } ?: "route Q unavailable"
        return when (stage) {
            "HIGH" -> "$measured and $routeMeasured: healthy forwarding"
            "M1" -> if (hopQ != null && hopQ >= 0.75 && routeQ != null && routeQ < 0.75) {
                "$measured is HIGH but $routeMeasured warns of a weaker downstream hop: forward and monitor"
            } else "$measured in [0.65, 0.75): forward and monitor"
            "M2" -> "$measured in [0.55, 0.65): primary with ACK-triggered backup"
            "M3" -> "$measured in [0.45, 0.55): delayed backup recovery"
            "LOW" -> "$measured < 0.45 or no usable route: store-carry and re-evaluate"
            else -> "$measured; stage=$stage"
        }
    }

    private fun sendForward(
        packet: NetworkPacket,
        nextHopId: String,
        stage: String,
        confidence: Double?,
        completion: ((Boolean) -> Unit)? = null
    ) {
        if (nextHopId.isBlank() || nextHopId == identity.nodeId) {
            DiagnosticLogger.error("ROUTING", "Invalid next hop for ${packet.messageId.take(8)}")
            completion?.invoke(false) ?: queueOrFailIfMine(packet)
            return
        }

        if (ble.connectionState(nextHopId) != "Ready" || ble.queueDepthForNode(nextHopId) >= ble.queueCapacity - 1) {
            DiagnosticLogger.warning("FORWARD", "Not enqueued id=${packet.messageId.take(8)} to=$nextHopId state=${ble.connectionState(nextHopId)} gattQueue=${ble.queueDepthForNode(nextHopId)}/${ble.queueCapacity}")
            completion?.invoke(false) ?: queueOrFailIfMine(packet)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val meta = packetRunMeta(packet)
        if (packet.type == PacketType.EXPERIMENT && packet.sourceId == identity.nodeId) {
            packetAudits[packet.messageId]?.let { audit -> audit.attempts++ }
        }
        val sourceOwned = packet.sourceId == identity.nodeId &&
            packet.type in setOf(PacketType.CHAT, PacketType.EXPERIMENT)
        val requiresHopReceipt = packet.type in setOf(
            PacketType.CHAT, PacketType.GROUP_CONTROL, PacketType.DIRECT_PROBE, PacketType.EXPERIMENT, PacketType.DELIVERY_ACK, PacketType.RUN_CONTROL
        )
        val storedBeforeAttempt = queuedPackets[packet.messageId]
        if (requiresHopReceipt && storedBeforeAttempt != null) {
            // Persist the expected custodian before sending; a fast HOP_ACK can
            // arrive before Android's final GATT callback on some devices.
            queuedPackets[packet.messageId] = storedBeforeAttempt.copy(custodyNextHopId = nextHopId)
        }
        val forwarded = packet.copy(
            previousHopId = identity.nodeId,
            custodyNextHopId = null,
            hopCount = packet.hopCount + 1,
            routeTrace = if (packet.routeTrace.lastOrNull() == identity.nodeId) {
                packet.routeTrace
            } else {
                packet.routeTrace + identity.nodeId
            }
        )

        metrics.observeQueue(identity.nodeId, nextHopId, ble.queueDepthForNode(nextHopId), ble.queueCapacity)
        research.record(
            ResearchEvent(
                elapsedMs = now,
                note = "encodedBytes=${PacketCodec.encode(forwarded).size}; copyBudget=${forwarded.copyBudgetRemaining}",
                event = "FORWARD_ATTEMPT",
                messageId = packet.messageId,
                protocol = packet.protocol.name,
                currentNode = identity.nodeId,
                sourceNode = packet.sourceId,
                previousHop = packet.previousHopId,
                nextHop = nextHopId,
                destination = packet.destinationId,
                packetType = packet.type.name,
                sequence = meta?.sequence,
                hopCount = forwarded.hopCount,
                stage = stage,
                confidence = confidence,
                route = forwarded.routeTrace.joinToString("->"),
                trafficType = trafficTypeFor(packet),
                sessionId = meta?.sessionId,
                runLabel = meta?.runLabel,
                conditionLabel = meta?.conditionLabel,
                totalPackets = meta?.totalPackets,
                queueDepth = ble.queueDepthForNode(nextHopId),
                queueCapacity = ble.queueCapacity
            )
        )

        val epoch = networkEpoch
        val started = SystemClock.elapsedRealtime()
        if (requiresHopReceipt) metrics.beginHopAttempt(packet.messageId, identity.nodeId, nextHopId)
        activeAttempts[packet.messageId] = (activeAttempts[packet.messageId] ?: 0) + 1
        val accepted = ble.sendToNode(nextHopId, PacketCodec.encode(forwarded)) { success, delay ->
            activeAttempts[packet.messageId] = ((activeAttempts[packet.messageId] ?: 1) - 1).coerceAtLeast(0)
            if (epoch != networkEpoch || !networkRunning || !queuedPackets.containsKey(packet.messageId)) return@sendToNode
            if (!sourceOwned) {
                if (success && requiresHopReceipt) {
                    val held = queuedPackets[packet.messageId]
                    if (held != null) queuedPackets[packet.messageId] = held.copy(custodyNextHopId = nextHopId)
                    nextAttemptAt[packet.messageId] = SystemClock.elapsedRealtime() + HOP_ACK_RETRY_MS
                    scheduleHopReceiptRetry(packet.messageId, epoch)
                } else {
                    inFlightSourcePackets.remove(packet.messageId)
                    if (success) queuedPackets.remove(packet.messageId)
                }
            }
            // A GATT callback is transport evidence only. D and the ACK part
            // of T are updated later by HOP_ACK / HOP_ACK_TIMEOUT.
            metrics.observeGattWrite(identity.nodeId, nextHopId, success, delay)
            if (!success && requiresHopReceipt) {
                metrics.completeHopAttempt(packet.messageId, identity.nodeId, nextHopId, receiptReceived = false)
            }
            metrics.observeQueue(identity.nodeId, nextHopId, ble.queueDepthForNode(nextHopId), ble.queueCapacity)

            if (success) topology.touchDirectPeer(nextHopId, ble.addressForNode(nextHopId))

            val snap = metrics.snapshot(identity.nodeId, nextHopId)
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = if (success) "FORWARD_SUCCESS" else "FORWARD_FAILURE",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    previousHop = packet.previousHopId,
                    nextHop = nextHopId,
                    destination = packet.destinationId,
                    packetType = packet.type.name,
                    sequence = meta?.sequence,
                    hopCount = forwarded.hopCount,
                    stage = stage,
                    qRaw = snap?.confidence,
                    qDecision = snap?.confidence,
                    confidence = confidence,
                    success = success,
                    latencyMs = delay,
                    route = forwarded.routeTrace.joinToString("->"),
                    d = snap?.deliverySuccess,
                    f = snap?.freshness,
                    r = snap?.stability,
                    t = snap?.timeliness,
                    s = snap?.signalReliability,
                    b = snap?.resourceSuitability,
                    rssiDbm = snap?.rssiDbm,
                    rawRssiDbm = snap?.rawRssiDbm,
                    rssiSampleCount = snap?.rssiSampleCount,
                    deliveryAttempts = snap?.deliveryAttempts,
                    hopAckSuccesses = snap?.hopAckSuccesses,
                    medianHopAckRttMs = snap?.medianAckRttMs,
                    medianGattWriteMs = snap?.medianGattWriteMs,
                    freshnessAgeMs = snap?.freshnessAgeMs,
                    instabilityEvents = snap?.instabilityEvents,
                    gattFailureCount = snap?.gattFailureCount,
                    ackTimeoutCount = snap?.ackTimeoutCount,
                    disconnectCount = snap?.disconnectCount,
                    measurementStatus = snap?.measurementStatus,
                    bSource = "CONTROLLED_CONSTANT",
                    trafficType = trafficTypeFor(packet),
                    queueDepth = snap?.queueOccupancy,
                    queueCapacity = snap?.queueCapacity,
                    sessionId = meta?.sessionId,
                    runLabel = meta?.runLabel,
                    conditionLabel = meta?.conditionLabel,
                    totalPackets = meta?.totalPackets
                )
            )

            if (sourceOwned) {
                if (success) {
                    if (requiresHopReceipt) {
                        val held = queuedPackets[packet.messageId]
                        if (held != null) queuedPackets[packet.messageId] = held.copy(custodyNextHopId = nextHopId)
                    }
                    if (packet.type == PacketType.CHAT) markPacketChatStatus(packet, MessageStatus.IN_TRANSIT)
                    // Keep the packet in-flight until its end-to-end ACK. If
                    // that ACK never arrives, allow a controlled source retry.
                    if (ackRetryScheduled.add(packet.messageId)) {
                        handler.postDelayed({
                            if (epoch != networkEpoch || !networkRunning) return@postDelayed
                            ackRetryScheduled.remove(packet.messageId)
                            // A real HOP_ACK has already granted the relay an
                            // end-to-end ACK grace period. Do not turn that
                            // successful receipt into a synthetic timeout.
                            if ((nextAttemptAt[packet.messageId] ?: 0L) > SystemClock.elapsedRealtime()) return@postDelayed
                            if (queuedPackets.containsKey(packet.messageId)) {
                                // A source retry without an app receipt is a
                                // real failed delivery observation, even when
                                // Android reported the earlier GATT write as successful.
                                metrics.completeHopAttempt(packet.messageId, identity.nodeId, nextHopId, receiptReceived = false)
                                inFlightSourcePackets.remove(packet.messageId)
                                tryQueuedPacket(packet.messageId)
                            }
                        }, SOURCE_ACK_RETRY_MS)
                    }
                } else if (packet.messageId !in ackRetryScheduled && (activeAttempts[packet.messageId] ?: 0) == 0) {
                    inFlightSourcePackets.remove(packet.messageId)
                }
            } else if (!success) {
                inFlightSourcePackets.remove(packet.messageId)
                nextAttemptAt[packet.messageId] = SystemClock.elapsedRealtime() + 1_000L
            }

            if (success) {
                DiagnosticLogger.success("FORWARD", "${packet.messageId.take(8)} → $nextHopId [$stage]")
            } else {
                DiagnosticLogger.warning("FORWARD", "${packet.messageId.take(8)} failed → $nextHopId [$stage]")
            }

            completion?.invoke(success)
            if (!success && completion == null) queueOrFailIfMine(packet)
        }

        if (accepted) {
            inFlightSourcePackets.add(packet.messageId)
        } else if (requiresHopReceipt && storedBeforeAttempt != null) {
            queuedPackets[packet.messageId] = storedBeforeAttempt
        }

        metrics.observeQueue(identity.nodeId, nextHopId, ble.queueDepthForNode(nextHopId), ble.queueCapacity)
        if (!accepted) {
            activeAttempts[packet.messageId] = ((activeAttempts[packet.messageId] ?: 1) - 1).coerceAtLeast(0)
            if ((activeAttempts[packet.messageId] ?: 0) == 0) inFlightSourcePackets.remove(packet.messageId)
            val delay = SystemClock.elapsedRealtime() - started
            metrics.observeGattWrite(identity.nodeId, nextHopId, success = false, delayMs = delay)
            if (requiresHopReceipt) metrics.completeHopAttempt(packet.messageId, identity.nodeId, nextHopId, receiptReceived = false)
            research.record(
                ResearchEvent(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    event = "FORWARD_REJECTED",
                    messageId = packet.messageId,
                    protocol = packet.protocol.name,
                    currentNode = identity.nodeId,
                    sourceNode = packet.sourceId,
                    previousHop = packet.previousHopId,
                    nextHop = nextHopId,
                    destination = packet.destinationId,
                    packetType = packet.type.name,
                    sequence = meta?.sequence,
                    hopCount = forwarded.hopCount,
                    stage = stage,
                    confidence = confidence,
                    success = false,
                    latencyMs = delay,
                    route = forwarded.routeTrace.joinToString("->"),
                    trafficType = trafficTypeFor(packet),
                    sessionId = meta?.sessionId,
                    runLabel = meta?.runLabel,
                    conditionLabel = meta?.conditionLabel,
                    totalPackets = meta?.totalPackets
                )
            )
            completion?.invoke(false) ?: queueOrFailIfMine(packet)
        }
    }

    private fun scheduleHopReceiptRetry(messageId: String, epoch: Int) {
        if (!hopRetryScheduled.add(messageId)) return
        handler.postDelayed({
            hopRetryScheduled.remove(messageId)
            if (epoch != networkEpoch || !networkRunning || !queuedPackets.containsKey(messageId)) return@postDelayed
            if (!inFlightSourcePackets.remove(messageId)) return@postDelayed
            nextAttemptAt.remove(messageId)
            DiagnosticLogger.warning("HOP-ACK", "Receipt timeout id=${messageId.take(8)}; retrying from durable custody")
            val held = queuedPackets[messageId]
            if (held != null) {
                held.custodyNextHopId?.let { hop ->
                    metrics.completeHopAttempt(messageId, identity.nodeId, hop, receiptReceived = false)
                }
                research.record(ResearchEvent(SystemClock.elapsedRealtime(), "HOP_ACK_TIMEOUT", messageId, held.protocol.name, identity.nodeId,
                    sourceNode = held.sourceId, previousHop = held.previousHopId, nextHop = held.custodyNextHopId, destination = held.destinationId,
                    packetType = held.type.name, hopCount = held.hopCount, route = held.routeTrace.joinToString("->"), success = false,
                    note = "retrying after ${HOP_ACK_RETRY_MS}ms"))
            }
            tryQueuedPacket(messageId)
        }, HOP_ACK_RETRY_MS)
    }

    private fun sendDirect(nodeId: String, packet: NetworkPacket) {
        if (nodeId.isBlank() || nodeId == identity.nodeId) return
        metrics.observeQueue(identity.nodeId, nodeId, ble.queueDepthForNode(nodeId), ble.queueCapacity)
        val accepted = ble.sendTopology(nodeId, PacketCodec.encode(packet)) { success, delay ->
            metrics.observeGattWrite(identity.nodeId, nodeId, success, delay)
            metrics.observeQueue(identity.nodeId, nodeId, ble.queueDepthForNode(nodeId), ble.queueCapacity)
            if (success) topology.touchDirectPeer(nodeId, ble.addressForNode(nodeId))
        }

    }

    private fun queueOrFailIfMine(packet: NetworkPacket) {
        if ((activeAttempts[packet.messageId] ?: 0) > 0 || packet.messageId in ackRetryScheduled) return
        inFlightSourcePackets.remove(packet.messageId)
        if (packet.isExpired()) { dropPending(packet, "expired"); return }
        if (packet.hopCount >= packet.maxHops) { dropPending(packet, "hop limit"); return }
        if (_uiState.value.messages.any { it.messageId == packet.messageId && it.status == MessageStatus.DELIVERED }) return
        if (!queuedPackets.containsKey(packet.messageId) && queuedPackets.size < 220) queuedPackets[packet.messageId] = packet
        if (packet.sourceId == identity.nodeId && packet.type == PacketType.CHAT) markPacketChatStatus(packet, MessageStatus.QUEUED)
    }

    private fun dropPending(packet: NetworkPacket, reason: String) {
        if (packet.type == PacketType.EXPERIMENT && packet.sourceId == identity.nodeId) {
            recordFormalPacketResult(packet.messageId, result = "NOT_DELIVERED", reason = reason)
        }
        stageRecoveries.remove(packet.messageId)?.let { recovery ->
            metrics.cancelHopAttempt(packet.messageId, identity.nodeId, recovery.primaryHopId)
            metrics.cancelHopAttempt(packet.messageId, identity.nodeId, recovery.backupHopId)
        }
        queuedPackets.remove(packet.messageId)
        inFlightSourcePackets.remove(packet.messageId)
        ackRetryScheduled.remove(packet.messageId)
        carryCounts.remove(packet.messageId)
        lowWaiting.remove(packet.messageId)
        nextAttemptAt.remove(packet.messageId)
        research.record(ResearchEvent(SystemClock.elapsedRealtime(), "PACKET_DROPPED", packet.messageId, packet.protocol.name, identity.nodeId, note = reason))
        if (packet.sourceId == identity.nodeId && packet.type == PacketType.CHAT) markPacketChatStatus(packet, MessageStatus.FAILED)
    }

    private fun appendMessage(message: ChatMessage) = updateMessages { list ->
        if (message.groupId != null && list.any { it.messageId == message.messageId && it.groupId == message.groupId }) list else list + message
    }

    private fun markPacketChatStatus(packet: NetworkPacket, status: MessageStatus) {
        val group = groupEnvelope(packet)
        if (group == null) {
            markMessage(packet.messageId, status)
        } else {
            updateGroupRecipientStatus(group.groupId, group.groupMessageId, packet.destinationId, status)
        }
    }

    private fun updateGroupRecipientStatus(groupId: String, groupMessageId: String, recipientId: String, status: MessageStatus) {
        updateMessages { list -> list.map { message ->
            if (message.groupId != groupId || message.messageId != groupMessageId || !message.outgoing) message
            else {
                val perRecipient = message.recipientStatuses + (recipientId to status)
                val values = perRecipient.values
                val aggregate = when {
                    values.isNotEmpty() && values.all { it == MessageStatus.DELIVERED } -> MessageStatus.DELIVERED
                    values.any { it == MessageStatus.DELIVERED } -> MessageStatus.IN_TRANSIT
                    values.any { it == MessageStatus.IN_TRANSIT } -> MessageStatus.IN_TRANSIT
                    values.any { it == MessageStatus.SENDING } -> MessageStatus.SENDING
                    values.any { it == MessageStatus.QUEUED } -> MessageStatus.QUEUED
                    values.isNotEmpty() && values.all { it == MessageStatus.FAILED } -> MessageStatus.FAILED
                    else -> message.status
                }
                message.copy(status = aggregate, recipientStatuses = perRecipient)
            }
        } }
    }

    private fun markMessage(messageId: String, status: MessageStatus) = updateMessages { list ->
        list.map { if (it.messageId == messageId) it.copy(status = status) else it }
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val next = transform(_uiState.value.messages).distinctBy { it.messageId }.takeLast(5000)
        if (next == _uiState.value.messages) return
        chatRepository.save(next)
        update { copy(messages = next) }
    }

    fun suggestedResearchFileName(): String = research.suggestedFileName()

    fun exportResearchTo(uri: Uri): Boolean {
        val ok = research.writeToUri(uri)
        if (ok) {
            update { copy(lastExportPath = "Saved through Android Files", researchEvents = research.count()) }
            DiagnosticLogger.success("EXPERIMENT", "CSV exported through Android Files")
        } else {
            DiagnosticLogger.error("EXPERIMENT", "CSV export failed")
        }
        return ok
    }

    fun clearResearch() {
        // Completed formal CSVs are evidence and must not be erased by a UI
        // reset. Start a fresh manual log instead.
        research.beginManualSession()
        research.localNodeId = identity.nodeId
        update { copy(researchEvents = 0, lastExportPath = null) }
    }

    fun refreshUi() = updatePeers()

    private fun updatePeers() {
        val all = topology.allPeers().map { it.copy(transportState = ble.connectionState(it.nodeId)) }
        val saved = savedPeerRepository.load().map { sp ->
            val known = all.firstOrNull { it.nodeId == sp.userId }
            if (known != null && known.displayName != sp.userId) sp.copy(displayName = known.displayName, lastSeenEpochMs = if (known.isDirect) System.currentTimeMillis() else sp.lastSeenEpochMs) else sp
        }
        update { copy(peers = all, savedPeers = saved, researchEvents = research.count()) }
    }

    private fun update(block: AppUiState.() -> AppUiState) { _uiState.value = _uiState.value.block() }

    private fun <T> List<T>.distinctAdjacent(): List<T> = fold(emptyList()) { acc, item -> if (acc.lastOrNull() == item) acc else acc + item }
}
