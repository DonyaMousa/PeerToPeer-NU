package com.example.peertopeer.domain.model

class Graph {

    private var topologyVersion = 0L

    private val nodes =
        mutableMapOf<String, Node>()

    /*
     * Adjacency list:
     *
     * nodeId -> (neighborId -> edgeWeight)
     *
     * Example:
     *
     * A -> { B=1, D=1 }
     * B -> { A=1, C=1 }
     */
    private val adjacency =
        mutableMapOf<String, MutableMap<String, Int>>()

    fun addNode(node: Node) {

        if (nodes.containsKey(node.nodeId)) {
            return
        }

        nodes[node.nodeId] = node
        adjacency[node.nodeId] = mutableMapOf()

        topologyVersion++
    }

    fun removeNode(nodeId: String) {

        if (!nodes.containsKey(nodeId)) {
            return
        }

        nodes.remove(nodeId)

        adjacency.remove(nodeId)

        adjacency.values.forEach { neighbors ->
            neighbors.remove(nodeId)
        }

        topologyVersion++
    }

    fun getNode(nodeId: String): Node? {
        return nodes[nodeId]
    }

    fun containsNode(nodeId: String): Boolean {
        return nodes.containsKey(nodeId)
    }

    fun containsNode(node: Node): Boolean {
        return containsNode(node.nodeId)
    }

    fun getTopologyVersion(): Long {
        return topologyVersion
    }

    fun addEdge(edge: Edge) {

        require(edge.from != edge.to) {
            "A node cannot connect to itself."
        }

        require(edge.weight > 0) {
            "Edge weight must be greater than zero."
        }

        require(containsNode(edge.from)) {
            "Source node does not exist: ${edge.from}"
        }

        require(containsNode(edge.to)) {
            "Destination node does not exist: ${edge.to}"
        }

        val oldWeight =
            adjacency[edge.from]?.get(edge.to)

        if (oldWeight == edge.weight) {
            return
        }

        adjacency[edge.from]!![edge.to] =
            edge.weight

        adjacency[edge.to]!![edge.from] =
            edge.weight

        topologyVersion++
    }

    fun removeEdge(
        from: String,
        to: String
    ) {

        val existed =
            adjacency[from]?.containsKey(to) == true

        if (!existed) {
            return
        }

        adjacency[from]?.remove(to)
        adjacency[to]?.remove(from)

        topologyVersion++
    }

    fun updateEdge(edge: Edge) {
        addEdge(edge)
    }

    fun containsEdge(
        from: String,
        to: String
    ): Boolean {
        return adjacency[from]
            ?.containsKey(to) == true
    }

    fun getNeighbors(
        nodeId: String
    ): List<Edge> {

        val neighbors =
            adjacency[nodeId]
                ?: return emptyList()

        return neighbors.map { (neighborId, weight) ->
            Edge(
                from = nodeId,
                to = neighborId,
                weight = weight
            )
        }
    }

    fun getNodes(): List<Node> {
        return nodes.values.toList()
    }

    fun getEdges(): List<Edge> {

        val result =
            mutableListOf<Edge>()

        val processed =
            mutableSetOf<String>()

        adjacency.forEach { (from, neighbors) ->

            neighbors.forEach { (to, weight) ->

                val edgeKey =
                    if (from < to) {
                        "$from:$to"
                    } else {
                        "$to:$from"
                    }

                if (processed.add(edgeKey)) {

                    result.add(
                        Edge(
                            from = from,
                            to = to,
                            weight = weight
                        )
                    )
                }
            }
        }

        return result
    }

    fun edgeCost(
        from: String,
        to: String
    ): Int {

        return adjacency[from]?.get(to)
            ?: throw IllegalArgumentException(
                "No edge exists between $from and $to."
            )
    }
}