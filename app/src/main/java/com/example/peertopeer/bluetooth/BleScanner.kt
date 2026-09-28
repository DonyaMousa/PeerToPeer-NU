package com.example.peertopeer.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

class BleScanner(private val adapter: BluetoothAdapter) {
    data class Discovery(
        val nodeId: String,
        val displayName: String,
        val device: BluetoothDevice,
        val rssi: Int
    )

    companion object {
        private const val EMIT_INTERVAL_MS = 1_000L
    }

    private var callback: ScanCallback? = null
    private var peerCallback: ((Discovery) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    private val lastEmitByNode = ConcurrentHashMap<String, Long>()
    @Volatile private var paused = false
    @Volatile private var started = false

    @SuppressLint("MissingPermission")
    fun start(onPeer: (Discovery) -> Unit, onStatus: (String) -> Unit) {
        stop()
        peerCallback = onPeer
        statusCallback = onStatus
        lastEmitByNode.clear()
        started = true
        paused = false

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                parse(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::parse)
            }

            override fun onScanFailed(errorCode: Int) {
                statusCallback?.invoke("Scan error $errorCode")
            }
        }
        callback = cb
        startPlatformScan(cb)
    }

    @SuppressLint("MissingPermission")
    fun pause() {
        if (!started || paused) return
        val cb = callback ?: return
        runCatching { adapter.bluetoothLeScanner?.stopScan(cb) }
        paused = true
    }

    @SuppressLint("MissingPermission")
    fun resume() {
        if (!started || !paused) return
        val cb = callback ?: return
        paused = false
        startPlatformScan(cb)
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformScan(cb: ScanCallback) {
        val scanner = adapter.bluetoothLeScanner ?: run {
            statusCallback?.invoke("BLE scanning unavailable")
            return
        }

        val settings = ScanSettings.Builder()
            // Low latency is intentional in the research build so Direct ↔ hop
            // transitions are observed quickly. New GATT connection attempts
            // briefly pause scanning to reduce radio contention/status 133.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching { scanner.startScan(listOf(ScanFilter.Builder().setManufacturerData(BleConstants.MANUFACTURER_ID, "N11|".toByteArray(Charsets.US_ASCII)).build()), settings, cb) }
            .onSuccess { statusCallback?.invoke("Scanning") }
            .onFailure { statusCallback?.invoke("Scan start failed") }
    }

    private fun parse(result: ScanResult) {
        if (paused) return
        val bytes = result.scanRecord
            ?.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)
            ?: return

        val raw = bytes.toString(Charsets.UTF_8)
        if (!raw.startsWith("N11|")) return
        val parts = raw.removePrefix("N11|").split('|', limit = 2)
        val id = parts.getOrNull(0)
            ?.take(8)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(Regex("[0-9A-F]{8}")) }
            ?: return

        val name = parts.getOrNull(1)
            ?.trim()
            .orEmpty()
            .ifBlank { id }

        val now = SystemClock.elapsedRealtime()
        val previous = lastEmitByNode[id]
        if (previous != null && now - previous < EMIT_INTERVAL_MS) return
        lastEmitByNode[id] = now

        peerCallback?.invoke(
            Discovery(
                nodeId = id,
                displayName = name,
                device = result.device,
                rssi = result.rssi
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback
        if (cb != null) {
            runCatching { adapter.bluetoothLeScanner?.stopScan(cb) }
        }
        callback = null
        peerCallback = null
        statusCallback = null
        paused = false
        started = false
        lastEmitByNode.clear()
    }
}
