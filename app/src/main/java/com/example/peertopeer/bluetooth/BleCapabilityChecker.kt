package com.example.peertopeer.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager

/** Hardware capability only. Adapter enabled/disabled state is checked separately. */
data class BleCapabilities(val bleSupported: Boolean, val advertisingSupported: Boolean)

class BleCapabilityChecker(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    fun check(): BleCapabilities {
        val ble = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = manager.adapter
        val advertising = runCatching {
            adapter != null && adapter.isMultipleAdvertisementSupported
        }.getOrDefault(false)
        return BleCapabilities(ble, advertising)
    }
}
