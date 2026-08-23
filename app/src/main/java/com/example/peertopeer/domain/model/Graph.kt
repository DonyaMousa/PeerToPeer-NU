package com.example.peertopeer.domain.model

class Graph {

    /*
     * Fast internal representation:
     *
     * nodes:
     * "A" -> Node(...)
     *
     * adjacency:
     * "A" -> { "B" -> 1, "C" -> 2 }
     */
    private val nodes =
        mutableMapOf<String, Node>()

    private val adjacency =
        mutableMapOf<String, MutableMap<String, Int>>()

    private var topologyVersion: Long = 0


    // =====================================================
    // NODE OPERATIONS
    // =====================================================

    fun addNode(node: Node) {

        require(node.nodeId.isNotBlank()) {
            "Node ID must not be blank"
        }

        val existing =
            nodes[node.nodeId]

        /*
         * Exact same node already exists.
         */
        if (existing == node) {
            return
        }

        val isNewNode =
            existing == null

        nodes[node.nodeId] = node

        adjacency.putIfAbsent(
            node.nodeId,
            mutableMapOf()
        )

        /*
         * Display-name changes do not change
         * physical network topology.
         */
        if (isNewNode) {
            topologyVersion++
        }
    }


    fun removeNode(nodeId: String): Boolean {

        if (!nodes.containsKey(nodeId)) {
            return false
        }

        /*
         * Remove this node from all neighbors.
         */
        val neighbors =
            adjacency[nodeId]
                ?.keys
                ?.toList()
                ?: emptyList()

        for (neighborId in neighbors) {
            adjacency[neighborId]
                ?.remove(nodeId)
        }

        adjacency.remove(nodeId)
        nodes.remove(nodeId)

        topologyVersion++

        return true
    }


    fun getNode(nodeId: String): Node? {
        return nodes[nodeId]
    }


    fun containsNode(nodeId: String): Boolean {
        return nodes.containsKey(nodeId)
    }

    /*
     * Compatibility with older code/tests
     * that passed Node directly.
     */
    fun containsNode(node: Node): Boolean {
        return containsNode(node.nodeId)
    }


    fun getNodes(): List<Node> {
        return nodes.values
            .sortedBy { it.nodeId }
    }


    // =====================================================
    // EDGE OPERATIONS
    // =====================================================

    fun addEdge(
        from: String,
        to: String,
        weight: Int = 1
    ) {

        validateEdge(
            from = from,
            to = to,
            weight = weight
        )

        val currentWeight =
            adjacency[from]?.get(to)

        /*
         * Exact same edge already exists.
         */
        if (currentWeight == weight) {
            return
        }

        adjacency
            .getValue(from)[to] = weight

        adjacency
            .getValue(to)[from] = weight

        topologyVersion++
    }


    /*
     * Compatibility with old:
     *
     * graph.addEdge(
     *     Edge(...)
     * )
     */
    fun addEdge(edge: Edge) {

        addEdge(
            from = edge.from,
            to = edge.to,
            weight = edge.weight
        )
    }


    fun removeEdge(
        from: String,
        to: String
    ): Boolean {

        val existed =
            adjacency[from]
                ?.containsKey(to)
                ?: false

        if (!existed) {
            return false
        }

        adjacency[from]?.remove(to)
        adjacency[to]?.remove(from)

        topologyVersion++

        return true
    }


    fun updateEdge(
        from: String,
        to: String,
        weight: Int
    ): Boolean {

        require(weight > 0) {
            "Edge weight must be positive"
        }

        val oldWeight =
            adjacency[from]?.get(to)
                ?: return false

        if (oldWeight == weight) {
            return false
        }

        adjacency
            .getValue(from)[to] = weight

        adjacency
            .getValue(to)[from] = weight

        topologyVersion++

        return true
    }


    fun updateEdge(edge: Edge): Boolean {

        return updateEdge(
            from = edge.from,
            to = edge.to,
            weight = edge.weight
        )
    }


    fun containsEdge(
        from: String,
        to: String
    ): Boolean {

        return adjacency[from]
            ?.containsKey(to)
            ?: false
    }


    // =====================================================
// EDGE COST
// =====================================================

    /*
     * Original project API.
     * Keep this for compatibility with routing/tests.
     */
    fun edgeCost(
        from: String,
        to: String
    ): Int? {
        return adjacency[from]?.get(to)
    }

    /*
     * Compatibility alias.
     */
    fun getCost(
        from: String,
        to: String
    ): Int? {
        return edgeCost(
            from = from,
            to = to
        )
    }

    /*
     * More descriptive alias for future routing code.
     */
    fun getEdgeWeight(
        from: String,
        to: String
    ): Int? {
        return edgeCost(
            from = from,
            to = to
        )
    }


    // =====================================================
    // NEIGHBORS
    // =====================================================

    /*
     * Preserve the original Edge-based API.
     *
     * Example:
     *
     * getNeighbors("A")
     *
     * [
     *   Edge(A, B, 1),
     *   Edge(A, C, 2)
     * ]
     */
    fun getNeighbors(
        nodeId: String
    ): List<Edge> {

        val neighbors =
            adjacency[nodeId]
                ?: return emptyList()

        return neighbors
            .map { (to, weight) ->

                Edge(
                    from = nodeId,
                    to = to,
                    weight = weight
                )
            }
            .sortedBy { it.to }
    }


    // =====================================================
    // ALL EDGES
    // =====================================================

    /*
     * Because B0 is currently undirected:
     *
     * adjacency stores:
     * A -> B
     * B -> A
     *
     * But getEdges() returns only ONE Edge.
     */
    fun getEdges(): List<Edge> {

        val result =
            mutableListOf<Edge>()

        val seen =
            mutableSetOf<Pair<String, String>>()

        for ((from, neighbors) in adjacency) {

            for ((to, weight) in neighbors) {

                val canonicalPair =
                    if (from < to) {
                        Pair(from, to)
                    } else {
                        Pair(to, from)
                    }

                if (!seen.add(canonicalPair)) {
                    continue
                }

                result.add(
                    Edge(
                        from = canonicalPair.first,
                        to = canonicalPair.second,
                        weight = weight
                    )
                )
            }
        }

        return result.sortedWith(
            compareBy<Edge> { it.from }
                .thenBy { it.to }
        )
    }


    // =====================================================
    // TOPOLOGY VERSION
    // =====================================================

    fun getTopologyVersion(): Long {
        return topologyVersion
    }


    // =====================================================
    // VALIDATION
    // =====================================================

    private fun validateEdge(
        from: String,
        to: String,
        weight: Int
    ) {

        require(from.isNotBlank()) {
            "Source node ID must not be blank"
        }

        require(to.isNotBlank()) {
            "Destination node ID must not be blank"
        }

        require(from != to) {
            "Self-links are not allowed"
        }

        require(weight > 0) {
            "Edge weight must be positive"
        }

        require(nodes.containsKey(from)) {
            "Source node $from does not exist"
        }

        require(nodes.containsKey(to)) {
            "Destination node $to does not exist"
        }
    }
}