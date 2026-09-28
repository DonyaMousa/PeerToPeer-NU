package com.example.peertopeer.bluetooth

import org.junit.Assert.*
import org.junit.Test

class BleFragmentCodecTest {
    @Test fun minimumMtuRoundTripArabic() {
        val bytes = "رسالة تجريبية طويلة عبر البلوتوث".repeat(40).toByteArray()
        val frames = BleFragmentCodec.fragment(bytes, 10)
        assertTrue(frames.all { it.size <= 20 })
        val decoder = BleFragmentCodec.Reassembler()
        var result: ByteArray? = null
        frames.forEach { decoder.accept("A", it)?.let { value -> result = value } }
        assertArrayEquals(bytes, result)
    }
    @Test fun interleavedAttemptsDoNotMix() {
        val first = BleFragmentCodec.fragment("alpha".repeat(10).toByteArray(), 10)
        val second = BleFragmentCodec.fragment("beta".repeat(10).toByteArray(), 10)
        val decoder = BleFragmentCodec.Reassembler()
        assertNull(decoder.accept("A", first[0]))
        var result: ByteArray? = null
        second.reversed().forEach { decoder.accept("A", it)?.let { value -> result = value } }
        assertArrayEquals("beta".repeat(10).toByteArray(), result)
    }
    @Test fun partialTransferExpires() {
        var clock = 0L
        val decoder = BleFragmentCodec.Reassembler { clock }
        val frames = BleFragmentCodec.fragment(ByteArray(20) { it.toByte() }, 10)
        assertNull(decoder.accept("A", frames[0]))
        clock = 30_001
        assertNull(decoder.accept("A", frames[1]))
    }
    @Test fun differentSendersRemainIsolated() {
        val frames = BleFragmentCodec.fragment(ByteArray(20) { it.toByte() }, 10)
        val decoder = BleFragmentCodec.Reassembler()
        assertNull(decoder.accept("A", frames[0]))
        assertNull(decoder.accept("B", frames[1]))
        assertArrayEquals(ByteArray(20) { it.toByte() }, decoder.accept("A", frames[1]))
    }
    @Test fun clearOnDisconnectDiscardsPartialTransfer() {
        val frames = BleFragmentCodec.fragment(ByteArray(20), 10)
        val decoder = BleFragmentCodec.Reassembler()
        decoder.accept("A", frames[0]); decoder.clear("A")
        assertNull(decoder.accept("A", frames[1]))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedPacketRejected() {
        BleFragmentCodec.fragment(ByteArray(BleFragmentCodec.MAX_PACKET_BYTES + 1))
    }
}
