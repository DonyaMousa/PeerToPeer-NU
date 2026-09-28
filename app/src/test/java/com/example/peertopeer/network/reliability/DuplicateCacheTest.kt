package com.example.peertopeer.network.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateCacheTest {
    @Test
    fun relaySuppressesNearDuplicateButAllowsLaterRetry() {
        val cache = DuplicateCache(maxEntries = 16, suppressionWindowMs = 1_500L)
        assertTrue(cache.firstSeen("p", 1_000L))
        assertFalse(cache.firstSeen("p", 1_500L))
        assertTrue(cache.firstSeen("p", 2_600L))
    }

    @Test
    fun destinationKeepsPayloadIdempotentAcrossAckRetryWindow() {
        val cache = DuplicateCache(maxEntries = 16, suppressionWindowMs = 600_000L)
        assertTrue(cache.firstSeen("p", 1_000L))
        assertFalse(cache.firstSeen("p", 9_000L))
    }

    @Test
    fun forgetAllowsImmediateReacceptance() {
        val cache = DuplicateCache(maxEntries = 16, suppressionWindowMs = 600_000L)
        assertTrue(cache.firstSeen("p", 1_000L))
        cache.forget("p")
        assertTrue(cache.firstSeen("p", 1_001L))
    }
}
