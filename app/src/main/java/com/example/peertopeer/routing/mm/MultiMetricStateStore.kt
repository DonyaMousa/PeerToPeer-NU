package com.example.peertopeer.routing.mm

class MultiMetricStateStore {

    private val states =
        mutableMapOf<
                Pair<String, String>,
                MultiMetricLinkState
                >()

    fun update(
        state: MultiMetricLinkState
    ) {

        states[
            state.fromNodeId to
                    state.toNodeId
        ] =
            state
    }

    fun get(
        fromNodeId: String,
        toNodeId: String
    ): MultiMetricLinkState? {

        return states[
            fromNodeId to
                    toNodeId
        ]
    }

    fun remove(
        fromNodeId: String,
        toNodeId: String
    ) {

        states.remove(
            fromNodeId to
                    toNodeId
        )
    }

    fun clear() {

        states.clear()
    }

    fun getAll():
            List<MultiMetricLinkState> {

        return states.values
            .toList()
    }
}
