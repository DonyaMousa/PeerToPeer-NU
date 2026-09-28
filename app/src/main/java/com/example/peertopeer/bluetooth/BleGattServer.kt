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

/** GATT server with serialized, acknowledged indications and bounded per-peer queues. */
@SuppressLint("MissingPermission")
class BleGattServer(
    context: Context,
    private val onBytes: (BluetoothDevice, ByteArray) -> Unit,
    private val onSubscribed: (BluetoothDevice) -> Unit,
    private val onClosed: (String) -> Unit
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var generation = 0
    private class Pending(val chunks: List<ByteArray>, val done: (Boolean, Long) -> Unit) {
        val start = SystemClock.elapsedRealtime(); var index = 0
    }
    private class Session(val device: BluetoothDevice) {
        var subscribed = false; var payload = 10
        val queue = ArrayDeque<Pending>(); var timer: Runnable? = null
    }
    private val sessions = linkedMapOf<String, Session>()
    private val reassembler = BleFragmentCodec.Reassembler()
    fun isReady(address: String) = sessions[address]?.subscribed == true
    fun queueDepth(address: String) = sessions[address]?.queue?.size ?: 0
    fun start(onReady: (Boolean) -> Unit) {
        stop(); val epoch = generation
        val write = BluetoothGattCharacteristic(BleConstants.DATA_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val notify = BluetoothGattCharacteristic(BleConstants.NOTIFY_UUID, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
        notify.addDescriptor(BluetoothGattDescriptor(BleConstants.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(write); service.addCharacteristic(notify)
        val cb = object : BluetoothGattServerCallback() {
            private fun event(action: () -> Unit) { handler.post { if (epoch == generation && server != null) action() } }
            override fun onServiceAdded(status: Int, service: BluetoothGattService) = event {
                DiagnosticLogger.info("GATT-SERVER", "Service registration status=$status")
                onReady(status == BluetoothGatt.GATT_SUCCESS)
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = event {
                if (status == 0 && newState == BluetoothProfile.STATE_CONNECTED) sessions.getOrPut(device.address) { Session(device) }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != 0) remove(device.address)
            }
            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = event {
                sessions.getOrPut(device.address) { Session(device) }.payload = (mtu - 13).coerceIn(10, BleConstants.MAX_FRAGMENT_PAYLOAD)
            }
            override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = event {
                respond(device, requestId, if (offset == 0) 0 else BluetoothGatt.GATT_INVALID_OFFSET,
                    if (isReady(device.address)) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                val bytes = value.copyOf()
                event {
                    val enabled = bytes.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    val disabled = bytes.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                    val valid = descriptor.uuid == BleConstants.CCCD_UUID && !preparedWrite && offset == 0 && (enabled || disabled)
                    if (responseNeeded) respond(device, requestId, if (valid) 0 else BluetoothGatt.GATT_FAILURE)
                    if (valid) {
                        val s = sessions.getOrPut(device.address) { Session(device) }; s.subscribed = enabled
                        if (enabled) onSubscribed(device) else remove(device.address)
                    }
                }
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                val bytes = value.copyOf()
                event {
                    val valid = characteristic.uuid == BleConstants.DATA_UUID && !preparedWrite && offset == 0 && isReady(device.address)
                    if (responseNeeded) respond(device, requestId, if (valid) 0 else BluetoothGatt.GATT_FAILURE)
                    if (valid) reassembler.accept(device.address, bytes)?.let { onBytes(device, it) }
                }
            }
            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = event { respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED) }
            override fun onNotificationSent(device: BluetoothDevice, status: Int) = event {
                val s = sessions[device.address] ?: return@event
                s.timer?.let(handler::removeCallbacks); s.timer = null
                val p = s.queue.peekFirst() ?: return@event
                BleTransportTelemetry.record("BLE_INDICATION_RESULT", device.address, status = status)
                if (status != 0) {
                    DiagnosticLogger.warning("GATT-SERVER", "Indication failed peer=${device.address.takeLast(5)} status=$status fragment=${p.index + 1}/${p.chunks.size} queued=${s.queue.size}")
                    disconnect(s)
                } else { p.index++; pump(s) }
            }
        }
        server = runCatching { manager.openGattServer(app, cb) }.getOrNull()
        if (server == null || !runCatching { server!!.addService(service) }.getOrDefault(false)) { stop(); onReady(false) }
    }
    private fun respond(d: BluetoothDevice, id: Int, status: Int, value: ByteArray? = null) {
        runCatching { server?.sendResponse(d, id, status, 0, value) }.onFailure { DiagnosticLogger.warning("GATT-SERVER", "Response failed: ${it.javaClass.simpleName}") }
    }
    fun send(address: String, bytes: ByteArray, done: (Boolean, Long) -> Unit): Boolean {
        val s = sessions[address]?.takeIf { it.subscribed } ?: return false
        if (s.queue.size >= 10) return false
        val chunks = runCatching { BleFragmentCodec.fragment(bytes, s.payload) }.getOrNull() ?: return false
        s.queue.addLast(Pending(chunks, done))
        if (s.queue.size == 1) pump(s)
        return true
    }
    private fun pump(s: Session) {
        if (s.timer != null) return
        val p = s.queue.peekFirst() ?: return
        if (p.index >= p.chunks.size) {
            s.queue.removeFirst(); p.done(true, SystemClock.elapsedRealtime() - p.start); pump(s); return
        }
        val g = server ?: return disconnect(s)
        val c = g.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.NOTIFY_UUID) ?: return disconnect(s)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) g.notifyCharacteristicChanged(s.device, c, true, p.chunks[p.index]) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value = p.chunks[p.index]; @Suppress("DEPRECATION") g.notifyCharacteristicChanged(s.device, c, true) }
        }.getOrDefault(false)
        if (!ok) { disconnect(s); return }
        BleTransportTelemetry.record("BLE_FRAGMENT_INDICATION", s.device.address, p.chunks[p.index].size)
        val r = Runnable {
            DiagnosticLogger.warning("GATT-SERVER", "Indication timeout peer=${s.device.address.takeLast(5)} fragment=${p.index + 1}/${p.chunks.size} queued=${s.queue.size}")
            disconnect(s)
        }
        s.timer = r; handler.postDelayed(r, 8_000L)
    }
    private fun disconnect(s: Session) {
        DiagnosticLogger.warning("GATT-SERVER", "Disconnect peer=${s.device.address.takeLast(5)} queued=${s.queue.size} reason=transport recovery")
        runCatching { server?.cancelConnection(s.device) }; remove(s.device.address)
    }
    private fun remove(address: String) {
        val s = sessions.remove(address) ?: return
        s.timer?.let(handler::removeCallbacks)
        val pending = s.queue.toList(); s.queue.clear()
        reassembler.clear(address); onClosed(address)
        pending.forEach { it.done(false, SystemClock.elapsedRealtime() - it.start) }
    }
    fun stop() {
        generation++
        sessions.keys.toList().forEach(::remove)
        runCatching { server?.close() }; server = null; reassembler.clear()
    }
}
