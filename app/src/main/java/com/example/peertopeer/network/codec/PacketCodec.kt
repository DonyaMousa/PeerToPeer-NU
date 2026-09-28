package com.example.peertopeer.network.codec

import com.example.peertopeer.network.model.NetworkPacket
import com.example.peertopeer.network.model.PacketType
import com.example.peertopeer.network.model.ProtocolType
import org.json.JSONArray
import org.json.JSONObject

object PacketCodec {
    fun encode(packet: NetworkPacket): ByteArray {
        val json = JSONObject()
            .put("v", packet.version)
            .put("id", packet.messageId)
            .put("src", packet.sourceId)
            .put("dst", packet.destinationId)
            .put("prev", packet.previousHopId ?: JSONObject.NULL)
            .put("custodyNext", packet.custodyNextHopId ?: JSONObject.NULL)
            .put("lowReevaluations", packet.lowReevaluations)
            .put("copies", packet.copyBudgetRemaining)
            .put("hop", packet.hopCount)
            .put("max", packet.maxHops)
            .put("created", packet.createdAtElapsedMs)
            .put("createdEpoch", packet.createdAtEpochMs)
            .put("expiry", packet.expiryAfterMs)
            .put("protocol", packet.protocol.name)
            .put("type", packet.type.name)
            .put("payload", packet.payload)
            .put("trace", JSONArray(packet.routeTrace))
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): NetworkPacket? = runCatching {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val traceJson = json.optJSONArray("trace") ?: JSONArray()
        val trace = buildList {
            for (i in 0 until traceJson.length()) add(traceJson.getString(i))
        }
        require(json.getInt("v") in 3..5)
        require(json.getString("src").matches(Regex("[0-9A-F]{8}")))
        require(json.getString("dst").matches(Regex("[0-9A-F]{8}")))
        require(json.getInt("hop") in 0..12 && json.getInt("max") in 1..12)
        require(json.getLong("expiry") in 1..86_400_000L)
        require(json.getString("id").length in 1..200)
        NetworkPacket(
            version = json.getInt("v"),
            messageId = json.getString("id"),
            sourceId = json.getString("src"),
            destinationId = json.getString("dst"),
            previousHopId = json.optString("prev").takeUnless { it.isBlank() || it == "null" },
            custodyNextHopId = json.optString("custodyNext").takeUnless { it.isBlank() || it == "null" },
            lowReevaluations = json.optInt("lowReevaluations", 0).coerceIn(0, 4),
            copyBudgetRemaining = json.optInt("copies", 1).coerceIn(0, 1),
            hopCount = json.getInt("hop"),
            maxHops = json.getInt("max"),
            createdAtElapsedMs = json.getLong("created"),
            createdAtEpochMs = json.optLong("createdEpoch", System.currentTimeMillis()),
            expiryAfterMs = json.getLong("expiry"),
            protocol = ProtocolType.valueOf(json.getString("protocol")),
            type = PacketType.valueOf(json.getString("type")),
            payload = json.getString("payload"),
            routeTrace = trace
        )
    }.getOrNull()
}
