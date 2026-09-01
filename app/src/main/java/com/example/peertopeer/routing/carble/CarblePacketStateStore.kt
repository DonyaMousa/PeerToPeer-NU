package com.example.peertopeer.routing.carble

class CarblePacketStateStore {

    private val states =
        mutableMapOf<
                String,
                CarblePacketState
                >()


    // =====================================================
    // GET
    // =====================================================

    fun get(
        messageId: String
    ): CarblePacketState? {

        require(
            messageId.isNotBlank()
        )

        return states[
            messageId
        ]
    }


    // =====================================================
    // GET OR CREATE
    // =====================================================

    fun getOrCreate(
        messageId: String
    ): CarblePacketState {

        require(
            messageId.isNotBlank()
        )

        return states
            .getOrPut(
                messageId
            ) {

                CarblePacketState(
                    messageId =
                        messageId
                )
            }
    }


    // =====================================================
    // UPDATE
    // =====================================================

    fun update(
        state: CarblePacketState
    ) {

        states[
            state.messageId
        ] =
            state
    }


    // =====================================================
    // CLEAR
    // =====================================================

    fun remove(
        messageId: String
    ) {

        states.remove(
            messageId
        )
    }


    fun clear() {

        states.clear()
    }


    fun size(): Int {

        return states.size
    }
}