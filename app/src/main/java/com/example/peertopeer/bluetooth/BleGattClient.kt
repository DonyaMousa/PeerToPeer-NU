package com.example.peertopeer.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.peertopeer.diagnostics.DiagnosticLogger
import java.util.ArrayDeque

/** All state and callbacks are owned by the main looper. One GATT operation per session. */
@SuppressLint("MissingPermission")
class BleGattClient(
    private val context: Context,
    private val onBytes: (BluetoothDevice, ByteArray) -> Unit,
    private val onClosed: (String) -> Unit
) {
    companion object { const val MAX_QUEUE_CAPACITY = 10 }
    private enum class State { DISCONNECTED, CONNECTING, DISCOVERY, MTU, SUBSCRIBING, READY }
    private class Pending(val bytes: ByteArray, val done: (Boolean, Long) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        var retries = 0
    }
    private class Session(val device: BluetoothDevice) {
        var state = State.DISCONNECTED
        var gatt: BluetoothGatt? = null
        val queue = ArrayDeque<Pending>()
        var current: Pending? = null
        var chunks = emptyList<ByteArray>()
        var index = 0
        var payload = 10
        var safeMtu = false
        var nextTry = 0L
        var failures = 0
        var timer: Runnable? = null
        var retry: Runnable? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = linkedMapOf<String, Session>()
    private val reassembler = BleFragmentCodec.Reassembler()
    private var connecting: Session? = null
    private var closed = false
    fun reopen() { closed = false }
    fun hasLiveSession(address: String): Boolean = sessions[address]?.state?.let { it != State.DISCONNECTED } == true
    fun isReady(address: String): Boolean = sessions[address]?.state == State.READY
    fun queueDepth(address: String): Int = sessions[address]?.let { it.queue.size + if (it.current != null) 1 else 0 } ?: 0
    fun send(device: BluetoothDevice, bytes: ByteArray, onComplete: (Boolean, Long) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return false
        val s = sessions.getOrPut(device.address) { Session(device) }
        if (queueDepth(device.address) >= MAX_QUEUE_CAPACITY || sessions.keys.sumOf(::queueDepth) >= 40) return false
        s.queue.addLast(Pending(bytes.copyOf(), onComplete))
        if (s.state == State.READY) pump(s) else schedule()
        return true
    }
    private fun schedule() {
        if (closed || connecting != null) return
        val now = SystemClock.elapsedRealtime()
        val s = sessions.values.firstOrNull { it.state == State.DISCONNECTED && it.queue.isNotEmpty() && it.nextTry <= now }
        if (s == null) {
            sessions.values.filter { it.state == State.DISCONNECTED && it.queue.isNotEmpty() }.forEach { waiting ->
                waiting.retry?.let(handler::removeCallbacks)
                val r = Runnable { waiting.retry = null; schedule() }
                waiting.retry = r
                handler.postDelayed(r, (waiting.nextTry - now).coerceAtLeast(100L))
            }
            return
        }
        connecting = s
        BleTransportTelemetry.record("BLE_CONNECT_ATTEMPT", s.device.address)
        s.state = State.CONNECTING
        DiagnosticLogger.info("GATT", "Connecting ${s.device.address.takeLast(5)}")
        s.gatt = runCatching { s.device.connectGatt(context, false, callbacks(s), BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (s.gatt == null) fail(s, "connect rejected") else deadline(s, 15_000L, "connect timeout")
    }
    private fun callbacks(s: Session) = object : BluetoothGattCallback() {
        private fun event(g: BluetoothGatt, action: () -> Unit) { handler.post { if (!closed && s.gatt === g) action() } }
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = event(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(s, "connection status=$status state=$newState")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && s.state == State.CONNECTING) {
                s.state = State.DISCOVERY
                deadline(s, 10_000L, "discovery timeout")
                if (!runCatching { g.discoverServices() }.getOrDefault(false)) fail(s, "discovery rejected")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = event(g) {
            if (s.state != State.DISCOVERY) return@event
            val service = g.getService(BleConstants.SERVICE_UUID)
            if (status != 0 || service == null || service.getCharacteristic(BleConstants.DATA_UUID) == null || service.getCharacteristic(BleConstants.NOTIFY_UUID) == null) {
                fail(s, "v11 service missing/status=$status"); return@event
            }
            s.state = State.MTU
            if (s.safeMtu || !runCatching { g.requestMtu(185) }.getOrDefault(false)) subscribe(s)
            else deadline(s, 5_000L, "MTU timeout")
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = event(g) {
            if (s.state != State.MTU) return@event
            s.payload = if (status == 0) (mtu - 13).coerceIn(10, BleConstants.MAX_FRAGMENT_PAYLOAD) else 10
            subscribe(s)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = event(g) {
            if (s.state != State.SUBSCRIBING || descriptor.uuid != BleConstants.CCCD_UUID) return@event
            if (status != 0) { fail(s, "subscription status=$status"); return@event }
            cancelTimer(s)
            s.state = State.READY
            s.failures = 0
            if (connecting === s) connecting = null
            DiagnosticLogger.success("GATT", "Duplex transport ready ${s.device.address.takeLast(5)}")
            pump(s); schedule()
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = event(g) {
            if (s.state != State.READY || s.current == null || c.uuid != BleConstants.DATA_UUID) return@event
            cancelTimer(s)
            BleTransportTelemetry.record("BLE_FRAGMENT_WRITE_RESULT", s.device.address, status = status)
            if (status != 0) fail(s, "write status=$status") else { s.index++; write(s) }
        }
        @Deprecated("Legacy callback for API 26–32")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val bytes = c.value?.copyOf() ?: return
            receive(g, c.uuid, bytes)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = receive(g, c.uuid, value.copyOf())
        private fun receive(g: BluetoothGatt, uuid: java.util.UUID, bytes: ByteArray) = event(g) {
            if (uuid == BleConstants.NOTIFY_UUID) reassembler.accept(s.device.address, bytes)?.let { onBytes(s.device, it) }
        }
    }
    private fun subscribe(s: Session) {
        val g = s.gatt ?: return
        s.state = State.SUBSCRIBING
        val c = g.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.NOTIFY_UUID)
        val d = c?.getDescriptor(BleConstants.CCCD_UUID)
        if (c == null || d == null) { fail(s, "subscription missing"); return }
        val accepted = runCatching {
            if (!g.setCharacteristicNotification(c, true)) false
            else if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
        }.getOrDefault(false)
        if (accepted) deadline(s, 8_000L, "subscription timeout") else fail(s, "subscription rejected")
    }
    private fun pump(s: Session) {
        if (closed || s.state != State.READY || s.current != null) return
        val p = s.queue.pollFirst() ?: return
        s.current = p
        s.index = 0
        s.chunks = runCatching { BleFragmentCodec.fragment(p.bytes, s.payload) }.getOrElse {
            s.current = null; finish(p, false); pump(s); return
        }
        write(s)
    }
    private fun write(s: Session) {
        val p = s.current ?: return
        if (s.index >= s.chunks.size) { s.current = null; finish(p, true); pump(s); return }
        val g = s.gatt ?: return fail(s, "missing GATT")
        val c = g.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.DATA_UUID) ?: return fail(s, "missing write characteristic")
        val bytes = s.chunks[s.index]
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") c.value = bytes
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
        }.getOrDefault(false)
        if (ok) BleTransportTelemetry.record("BLE_FRAGMENT_WRITE", s.device.address, bytes.size)
        if (ok) deadline(s, 8_000L, "write timeout") else fail(s, "write rejected")
    }
    private fun deadline(s: Session, ms: Long, reason: String) {
        cancelTimer(s)
        val r = Runnable { if (s.state == State.MTU) s.safeMtu = true; fail(s, reason) }
        s.timer = r; handler.postDelayed(r, ms)
    }
    private fun cancelTimer(s: Session) { s.timer?.let(handler::removeCallbacks); s.timer = null }
    private fun finish(p: Pending, ok: Boolean) { p.done(ok, SystemClock.elapsedRealtime() - p.started) }
    private fun dispose(s: Session) {
        cancelTimer(s)
        s.retry?.let(handler::removeCallbacks); s.retry = null
        val g = s.gatt; s.gatt = null
        runCatching { g?.disconnect() }; runCatching { g?.close() }
        s.state = State.DISCONNECTED; s.payload = 10
        reassembler.clear(s.device.address)
        if (connecting === s) connecting = null
        onClosed(s.device.address)
    }
    private fun fail(s: Session, reason: String) {
        BleTransportTelemetry.record("BLE_SESSION_FAILURE", s.device.address)
        val oldState = s.state.name
        val p = s.current ?: s.queue.pollFirst()
        s.current = null
        dispose(s)
        if (p != null) {
            p.retries++
            if (p.retries <= 2 && !closed) s.queue.addFirst(p) else finish(p, false)
        }
        s.failures++
        val retryDelay = (1500L * (1L shl s.failures.coerceAtMost(4))) + kotlin.random.Random.nextLong(800)
        s.nextTry = SystemClock.elapsedRealtime() + retryDelay
        DiagnosticLogger.warning(
            "GATT",
            "$reason peer=${s.device.address.takeLast(5)} state=$oldState fragment=${s.index + 1}/${s.chunks.size} packetRetries=${p?.retries ?: 0} queue=${queueDepth(s.device.address)}/$MAX_QUEUE_CAPACITY failures=${s.failures} retryIn=${retryDelay}ms"
        )
        schedule()
    }
    fun retireAddress(address: String) {
        val s = sessions.remove(address) ?: return
        val pending = listOfNotNull(s.current) + s.queue.toList()
        s.current = null; s.queue.clear(); dispose(s)
        pending.forEach { finish(it, false) }; schedule()
    }
    fun close() {
        closed = true
        sessions.keys.toList().forEach(::retireAddress)
        reassembler.clear()
    }
}
