package com.example.peertopeer.routing.carble

data class CarbleBackupSelection(

    val candidate:
    CarbleBackupCandidate,

    val score:
    Double

) {

    init {

        require(
            score in 0.0..1.0
        ) {
            "score must be between 0.0 and 1.0."
        }
    }
}