package com.example.peertopeer.simulation

class TimedLinkStateTable {

    private data class LinkKey(
        val fromNodeId: String,
        val toNodeId: String
    )

    private data class LinkState(
        val isUp: Boolean,
        val changedAt: Long
    )

    private val links =
        mutableMapOf<LinkKey, LinkState>()

    fun setDirectedLinkState(
        fromNodeId: String,
        toNodeId: String,
        isUp: Boolean,
        changedAt: Long
    ) {
        validateLink(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId
        )

        require(changedAt >= 0) {
            "changedAt must not be negative"
        }

        links[
            LinkKey(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId
            )
        ] = LinkState(
            isUp = isUp,
            changedAt = changedAt
        )
    }

    fun setBidirectionalLinkState(
        nodeA: String,
        nodeB: String,
        isUp: Boolean,
        changedAt: Long
    ) {
        setDirectedLinkState(
            fromNodeId = nodeA,
            toNodeId = nodeB,
            isUp = isUp,
            changedAt = changedAt
        )

        setDirectedLinkState(
            fromNodeId = nodeB,
            toNodeId = nodeA,
            isUp = isUp,
            changedAt = changedAt
        )
    }

    fun isLinkUp(
        fromNodeId: String,
        toNodeId: String
    ): Boolean {
        validateLink(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId
        )

        return links[
            LinkKey(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId
            )
        ]?.isUp ?: false
    }

    fun lastChangedAt(
        fromNodeId: String,
        toNodeId: String
    ): Long? {
        validateLink(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId
        )

        return links[
            LinkKey(
                fromNodeId = fromNodeId,
                toNodeId = toNodeId
            )
        ]?.changedAt
    }

    private fun validateLink(
        fromNodeId: String,
        toNodeId: String
    ) {
        require(fromNodeId.isNotBlank()) {
            "fromNodeId must not be blank"
        }

        require(toNodeId.isNotBlank()) {
            "toNodeId must not be blank"
        }

        require(fromNodeId != toNodeId) {
            "A link cannot connect a node to itself"
        }
    }
}