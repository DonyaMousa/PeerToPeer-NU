package com.example.peertopeer.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import com.example.peertopeer.domain.identity.NodeIdentity

class BleAdvertiser(private val adapter: BluetoothAdapter) {
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(identity: NodeIdentity, onStatus: (String) -> Unit) {
        stop()
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            onStatus("BLE advertising unavailable")
            return
        }
        val safeName = identity.displayName
            .map { ch -> if (ch.code in 32..126) ch else '?' }
            .joinToString("")
            .take(6)
        val compact = ("N11|" + identity.nodeId.padEnd(8, '0').take(8) + "|" + safeName)
            .toByteArray(Charsets.US_ASCII)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BleConstants.MANUFACTURER_ID, compact)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = onStatus("Advertising")
            override fun onStartFailure(errorCode: Int) = onStatus("Advertise error $errorCode")
        }
        callback = cb
        runCatching { advertiser.startAdvertising(settings, data, cb) }
            .onFailure { onStatus("Advertise start failed") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        callback = null
    }
}
