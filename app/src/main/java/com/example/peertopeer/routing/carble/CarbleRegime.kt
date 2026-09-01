package com.example.peertopeer.routing.carble

enum class CarbleRegime {

    /*
     * Healthy deterministic operation.
     *
     * Uses frozen MM-v1.0 behavior.
     */
    HIGH,

    /*
     * Pre-failure operating region.
     *
     * Uses M1 / M2 / M3 staged resilience.
     */
    MEDIUM,

    /*
     * Severe degradation / unavailable forwarding
     * opportunity.
     *
     * Uses bounded store-carry-forward behavior.
     */
    LOW
}