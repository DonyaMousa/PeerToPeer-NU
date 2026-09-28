package com.example.peertopeer.bluetooth

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7e7b0011-2c4f-4b7c-9a42-7b5d91f1a001")
    val DATA_UUID: UUID = UUID.fromString("7e7b0012-2c4f-4b7c-9a42-7b5d91f1a001")
    val NOTIFY_UUID: UUID = UUID.fromString("7e7b0013-2c4f-4b7c-9a42-7b5d91f1a001")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MANUFACTURER_ID = 0x07E2
    const val MAX_FRAGMENT_PAYLOAD = 160
}
