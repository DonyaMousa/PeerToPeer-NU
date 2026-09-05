package com.example.peertopeer.Simulation.experiment.environment

import com.example.peertopeer.domain.model.Graph
import com.example.peertopeer.domain.model.Node
import com.example.peertopeer.simulation.SimulationEngine
import com.example.peertopeer.simulation.experiment.environment.PhysicalLinkEventScheduler
import com.example.peertopeer.simulation.experiment.instrumentation.RecorderInstrumentation
import com.example.peertopeer.simulation.experiment.record.TopologyEventType
import com.example.peertopeer.simulation.experiment.recording.ExperimentRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalLinkEventSchedulerTest {

    @Test
    fun flap_mutates_graph_and_records_down_and_up() {
        val graph = Graph().apply {
            addNode(Node("N1", "N1"))
            addNode(Node("N2", "N2"))
            addEdge("N1", "N2", 1)
        }

        val engine = SimulationEngine()
        val recorder = ExperimentRecorder("TEST-RUN")
        val instrumentation = RecorderInstrumentation(recorder)

        PhysicalLinkEventScheduler.install(
            engine = engine,
            graph = graph,
            instrumentation = instrumentation,
            runId = "TEST-RUN",
            events = PhysicalLinkEventScheduler.flap(
                fromNodeId = "N1",
                toNodeId = "N2",
                downAt = 10L,
                upAt = 20L
            )
        )

        engine.runUntil(10L)
        assertTrue(!graph.containsEdge("N1", "N2"))

        engine.runUntil(20L)
        assertTrue(graph.containsEdge("N1", "N2"))

        val events = recorder.getTopologyEventRecords()
        assertEquals(2, events.size)
        assertEquals(TopologyEventType.LINK_DOWN, events[0].eventType)
        assertEquals(TopologyEventType.LINK_UP, events[1].eventType)
        assertEquals(10L, events[0].eventTime)
        assertEquals(20L, events[1].eventTime)
    }
}
