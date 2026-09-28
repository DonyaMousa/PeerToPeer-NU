package com.example.peertopeer.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.peertopeer.domain.identity.NodeIdentity
import com.example.peertopeer.diagnostics.DiagnosticLogger

/** Both directions use one elected connection. Node identity is learned by a versioned HELLO. */
@SuppressLint("MissingPermission")
class BleManager(context: Context, private val onPeer: (BleScanner.Discovery) -> Unit,
    private val onBytes: (BluetoothDevice, ByteArray) -> Unit,
    private val onTransportState: (nodeId: String, ready: Boolean) -> Unit,
    private val onStatus: (String) -> Unit) {
    private val app = context.applicationContext
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner = adapter?.let(::BleScanner)
    private val advertiser = adapter?.let(::BleAdvertiser)
    private val handler = Handler(Looper.getMainLooper())
    private var identity: NodeIdentity? = null
    private var running = false
    private var generation = 0
    private val discoveries = linkedMapOf<String, BleScanner.Discovery>()
    private val seen = mutableMapOf<String, Long>()
    private val readyAddress = mutableMapOf<String, String>()
    private val inbound = mutableSetOf<String>()
    private val helloPending = mutableSetOf<String>()
    private val helloAt = mutableMapOf<String, Long>()
    private val topologyPending = mutableSetOf<String>()
    private val lastTraffic = mutableMapOf<String, Long>()
    private val client = BleGattClient(app, { d, bytes -> receive(d, bytes, false) }, ::closed)
    private val server = BleGattServer(app, { d, bytes -> receive(d, bytes, true) }, { d ->
        serverHello(d)
    }, ::closed)
    val isAvailable get() = adapter != null
    val isEnabled get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    val queueCapacity get() = BleGattClient.MAX_QUEUE_CAPACITY
    fun connectionState(nodeId: String): String {
        val a = readyAddress[nodeId]
        return when {
            a != null && (client.isReady(a) || server.isReady(a)) -> "Ready"
            discoveries[nodeId]?.let { client.hasLiveSession(it.device.address) } == true -> "Connecting"
            (scanAgeMs(nodeId) ?: Long.MAX_VALUE) < 15_000 -> "Discovered / recovering"
            else -> "Offline"
        }
    }
    fun start(local: NodeIdentity) {
        if (running) return
        if (!BlePermissions.hasRequiredBlePermissions(app) || !isEnabled) { onStatus("Bluetooth/permissions unavailable"); return }
        identity = local; running = true; generation++; val epoch = generation
        client.reopen()
        var serviceReported = false
        handler.postDelayed({
            if (running && generation == epoch && !serviceReported) {
                onStatus("GATT registration timed out")
                stop()
            }
        }, 10_000L)
        server.start ready@{ ok ->
            serviceReported = true
            if (!running || generation != epoch) return@ready
            if (!ok) { onStatus("GATT server failed — restart service"); stop(); return@ready }
            advertiser?.start(local, onStatus)
            scanner?.start(discovered@{ d ->
                if (!running || d.nodeId == local.nodeId) return@discovered
                val previous = discoveries.put(d.nodeId, d)
                seen[d.nodeId] = SystemClock.elapsedRealtime()
                if (previous != null && previous.device.address != d.device.address) client.retireAddress(previous.device.address)
                onPeer(d)
                ensure(d.nodeId)
            }, onStatus)
            onStatus("Advertising and discovery started")
        }
    }
    private fun hello(): ByteArray {
        val me = identity ?: error("No identity")
        return "P2P11|${me.nodeId}|${me.displayName}".toByteArray(Charsets.UTF_8)
    }
    private fun serverHello(d: BluetoothDevice) { server.send(d.address, hello()) { _, _ -> } }
    private fun ensure(nodeId: String) {
        val me = identity ?: return
        val d = discoveries[nodeId] ?: return
        if (!running || connectionState(nodeId) == "Ready" || me.nodeId >= nodeId || client.hasLiveSession(d.device.address)) return
        val now = SystemClock.elapsedRealtime()
        if ((scanAgeMs(nodeId) ?: Long.MAX_VALUE) > 15_000 || nodeId in helloPending || now - (helloAt[nodeId] ?: -30_000L) < 15_000) return
        helloPending.add(nodeId); helloAt[nodeId] = now
        val epoch = generation
        if (!client.send(d.device, hello()) { ok, _ ->
            if (epoch == generation) {
                helloPending.remove(nodeId)
                if (!ok) DiagnosticLogger.warning("SESSION", "Handshake failed for $nodeId")
            }
        }) helloPending.remove(nodeId)
        handler.postDelayed({
            if (running && generation == epoch && helloAt[nodeId] == now && readyAddress[nodeId] == null) {
                helloPending.remove(nodeId); client.retireAddress(d.device.address)
            }
        }, 45_000L)
    }
    private fun receive(d: BluetoothDevice, bytes: ByteArray, fromServer: Boolean) {
        if (!running) return
        val raw = bytes.toString(Charsets.UTF_8)
        if (raw.startsWith("P2P11|")) {
            val parts = raw.split('|', limit = 3)
            val id = parts.getOrNull(1) ?: return
            if (!id.matches(Regex("[0-9A-F]{8}")) || id == identity?.nodeId) return
            // Enforce the same election in both directions; reject crossed/obsolete sessions.
            val own = identity?.nodeId ?: return
            if ((fromServer && id >= own) || (!fromServer && id <= own)) return
            readyAddress[id] = d.address
            if (fromServer) inbound.add(d.address) else inbound.remove(d.address)
            lastTraffic[id] = SystemClock.elapsedRealtime()
            helloPending.remove(id)
            onTransportState(id, true)
            DiagnosticLogger.success("SESSION", "$id ready for bidirectional chat")
            onPeer(BleScanner.Discovery(id, parts.getOrNull(2).orEmpty().ifBlank { id }, d, discoveries[id]?.rssi ?: -70))
            return
        }
        val id = readyAddress.entries.firstOrNull { it.value == d.address }?.key ?: return
        lastTraffic[id] = SystemClock.elapsedRealtime()
        onBytes(d, bytes)
    }
    private fun closed(address: String) {
        readyAddress.entries.filter { it.value == address }.map { it.key }.forEach { nodeId ->
            readyAddress.remove(nodeId)
            lastTraffic.remove(nodeId)
            onTransportState(nodeId, false)
        }
        inbound.remove(address)
    }
    fun rememberNodeAddress(nodeId: String, device: BluetoothDevice) { /* Handshake owns address mappings. */ }
    fun addressForNode(nodeId: String): String? = readyAddress[nodeId] ?: discoveries[nodeId]?.device?.address
    fun hasScanAddress(nodeId: String) = discoveries.containsKey(nodeId)
    fun scanAgeMs(nodeId: String): Long? = seen[nodeId]?.let { SystemClock.elapsedRealtime() - it }
    fun contactAgeMs(nodeId: String): Long? = lastTraffic[nodeId]?.let { SystemClock.elapsedRealtime() - it }
    fun queueDepthForNode(nodeId: String): Int = readyAddress[nodeId]?.let { if (it in inbound) server.queueDepth(it) else client.queueDepth(it) } ?: 0
    fun sendToNode(nodeId: String, bytes: ByteArray, onComplete: (Boolean, Long) -> Unit): Boolean {
        if (!running) return false
        val address = readyAddress[nodeId] ?: run { ensure(nodeId); return false }
        val epoch = generation
        val complete: (Boolean, Long) -> Unit = { ok, delay ->
            handler.post {
                if (generation == epoch) {
                    if (ok) lastTraffic[nodeId] = SystemClock.elapsedRealtime()
                    onComplete(ok, delay)
                }
            }
        }
        return if (address in inbound) server.send(address, bytes, complete)
        else discoveries[nodeId]?.device?.takeIf { it.address == address }?.let { client.send(it, bytes, complete) } ?: false
    }
    fun sendTopology(nodeId: String, bytes: ByteArray, done: (Boolean, Long) -> Unit): Boolean {
        if (!topologyPending.add(nodeId)) return false
        val accepted = sendToNode(nodeId, bytes) { ok, delay -> topologyPending.remove(nodeId); done(ok, delay) }
        if (!accepted) topologyPending.remove(nodeId)
        return accepted
    }
    fun stop() {
        running = false; generation++
        scanner?.stop(); advertiser?.stop(); client.close(); server.stop()
        readyAddress.keys.toList().forEach { onTransportState(it, false) }
        discoveries.clear(); seen.clear(); readyAddress.clear(); inbound.clear(); helloPending.clear(); helloAt.clear(); topologyPending.clear(); lastTraffic.clear()
        onStatus("Stopped")
    }
}
