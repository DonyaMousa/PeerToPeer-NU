package com.example.peertopeer.bluetooth

/** App-visible BLE operations, not controller/link-layer retransmission counters. */
object BleTransportTelemetry {
    var observer: ((String, String, Int, Int?) -> Unit)? = null
    fun record(event: String, address: String, bytes: Int = 0, status: Int? = null) {
        observer?.invoke(event, address, bytes, status)
    }
}
