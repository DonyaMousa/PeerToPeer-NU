package com.example.peertopeer.simulation

fun interface TimedRouteProvider {

    /*
     * Original two-parameter method.
     *
     * Keep this as the single abstract method so all
     * existing TimedRouteProvider lambdas continue
     * compiling unchanged.
     */
    fun findPath(
        currentNodeId: String,
        destinationId: String
    ): List<String>?

    /*
     * Instrumented overload.
     *
     * Packet-aware callers can provide messageId.
     *
     * Older route providers do not need to know about
     * messageId, so by default we simply delegate to
     * the original method.
     */
    fun findPath(
        currentNodeId: String,
        destinationId: String,
        messageId: String?
    ): List<String>? {

        return findPath(
            currentNodeId = currentNodeId,
            destinationId = destinationId
        )
    }
}