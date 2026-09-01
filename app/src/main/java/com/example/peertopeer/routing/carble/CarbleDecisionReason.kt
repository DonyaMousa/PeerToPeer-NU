package com.example.peertopeer.routing.carble

enum class CarbleDecisionReason {

    /*
     * Current hop and remaining route are healthy.
     */
    HEALTHY_ROUTE,

    /*
     * Current physical forwarding hop itself is in
     * the MEDIUM confidence region.
     */
    LOCAL_MEDIUM,

    /*
     * Current hop remains HIGH, but a later route hop
     * has entered the pre-failure region.
     */
    DOWNSTREAM_WARNING,

    /*
     * Current physical forwarding hop is LOW.
     */
    LOCAL_LOW,

    /*
     * Reserved for provider-level use when no current
     * route exists.
     */
    NO_ROUTE,

    /*
     * Reserved for TTL, expiry, unavailable-neighbor
     * or other hard protocol overrides.
     */
    HARD_OVERRIDE
}