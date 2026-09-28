package com.example.peertopeer.network.topology

import com.example.peertopeer.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyRepositoryTest {
    private var elapsed = 1_000L
    private var wall = 1_000_000L

    private fun repository() = TopologyRepository(
        localNode = Node("AAAAAAAA", "A"),
        elapsedRealtime = { elapsed },
        wallClockMs = { wall }
    )

    @Test
    fun advertisementDoesNotPretendGattIsReady() {
        val topology = repository()
        topology.upsertDirectPeer("BBBBBBBB", "B", "AA:BB:CC:DD:EE:FF", -55)

        val peer = topology.allPeers().single()
        assertFalse(peer.isDirect)
        assertNull(peer.hopEstimate)
    }

    @Test
    fun liveRelayRouteChangesToDirectAndBackAsGattChanges() {
        val topology = repository()
        topology.upsertDirectPeer("BBBBBBBB", "B", "AA:BB:CC:DD:EE:01", -60)
        topology.upsertDirectPeer("CCCCCCCC", "C", "AA:BB:CC:DD:EE:02", -75)
        topology.setTransportReady("BBBBBBBB", true)
        assertTrue(topology.updateRemoteReport("BBBBBBBB", 10, setOf("CCCCCCCC"), 20_000))

        val peers = { topology.allPeers().associateBy { it.nodeId } }
        assertEquals(2, peers().getValue("CCCCCCCC").hopEstimate)
        assertFalse(peers().getValue("CCCCCCCC").isDirect)

        topology.setTransportReady("CCCCCCCC", true)
        assertEquals(1, peers().getValue("CCCCCCCC").hopEstimate)
        assertTrue(peers().getValue("CCCCCCCC").isDirect)

        topology.setTransportReady("CCCCCCCC", false)
        assertEquals(2, peers().getValue("CCCCCCCC").hopEstimate)
        assertFalse(peers().getValue("CCCCCCCC").isDirect)

        assertTrue(topology.updateRemoteReport("BBBBBBBB", 11, emptySet(), 20_000))
        assertNull(peers().getValue("CCCCCCCC").hopEstimate)
    }

    @Test
    fun expiredRelayReportCannotKeepPeerReachable() {
        val topology = repository()
        topology.setTransportReady("BBBBBBBB", true)
        assertTrue(topology.updateRemoteReport("BBBBBBBB", 20, setOf("CCCCCCCC"), 1_000))
        assertEquals(2, topology.allPeers().single { it.nodeId == "CCCCCCCC" }.hopEstimate)

        elapsed += 1_001
        assertNull(topology.allPeers().single { it.nodeId == "CCCCCCCC" }.hopEstimate)
    }

    @Test
    fun olderRemoteTopologyCannotRestoreWithdrawnLink() {
        val topology = repository()
        topology.setTransportReady("BBBBBBBB", true)
        assertTrue(topology.updateRemoteReport("BBBBBBBB", 30, setOf("CCCCCCCC"), 20_000))
        assertTrue(topology.updateRemoteReport("BBBBBBBB", 31, emptySet(), 20_000))

        // A delayed copy is not allowed to put B–C back into the route graph.
        assertFalse(topology.updateRemoteReport("BBBBBBBB", 30, setOf("CCCCCCCC"), 20_000))
        assertNull(topology.allPeers().single { it.nodeId == "CCCCCCCC" }.hopEstimate)
    }
}
