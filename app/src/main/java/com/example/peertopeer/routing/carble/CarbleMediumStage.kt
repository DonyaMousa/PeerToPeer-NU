package com.example.peertopeer.routing.carble

enum class CarbleMediumStage {

    /*
     * Watchful deterministic forwarding.
     *
     * Current hop:
     * 0.65 <= Q < 0.75
     *
     * Also used when the current hop is HIGH but a
     * downstream route hop is below the HIGH threshold.
     */
    M1,

    /*
     * Prepared failover.
     *
     * 0.55 <= Q < 0.65
     *
     * Primary route remains preferred, but one backup
     * candidate may be prepared.
     */
    M2,

    /*
     * Delayed controlled backup.
     *
     * 0.45 <= Q < 0.55
     *
     * One delayed backup opportunity may be activated.
     */
    M3
}