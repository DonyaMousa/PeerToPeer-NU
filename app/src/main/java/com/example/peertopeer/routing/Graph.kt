package com.example.peertopeer.routing
import com.example.peertopeer.domain.model.Edge
import com.example.peertopeer.domain.model.Node

class Graph {

    private val nodes = mutableMapOf<String, Node>()
    private val edges = mutableListOf<Edge>()

    fun addNode(node: Node) {
        nodes[node.nodeId] = node
    }

    fun removeNode(nodeId: String) {
        nodes.remove(nodeId)
        edges.removeAll {
            it.from == nodeId || it.to == nodeId
        }
    }

    fun addEdge(edge: Edge) {
        edges.add(edge)
    }

    fun removeEdge(from: String, to: String) {
        edges.removeAll {
            (it.from == from && it.to == to) ||
                    (it.from == to && it.to == from)
        }
    }

    fun updateEdge(edge: Edge) {
        removeEdge(edge.from, edge.to)
        addEdge(edge)
    }

    fun getNeighbors(nodeId: String): List<Edge> {
        return edges.filter {
            it.from == nodeId || it.to == nodeId
        }
    }

    fun getNodes(): List<Node> {
        return nodes.values.toList()
    }

    fun getEdges(): List<Edge> {
        return edges.toList()
    }
}