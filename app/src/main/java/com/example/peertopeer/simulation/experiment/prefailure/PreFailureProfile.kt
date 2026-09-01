package com.example.peertopeer.simulation.experiment.prefailure

class PreFailureProfile(
    val phases: List<PreFailurePhase>
) {

    init {
        require(phases.isNotEmpty()) {
            "PreFailureProfile requires at least one phase."
        }

        require(
            phases.map { it.phaseIndex } ==
                    (1..phases.size).toList()
        ) {
            "phaseIndex values must be contiguous and start at 1."
        }

        for (i in 1 until phases.size) {
            require(
                phases[i - 1].endTimeExclusive ==
                        phases[i].startTime
            ) {
                "Pre-failure phases must be contiguous."
            }
        }
    }

    fun probabilityAt(
        time: Long
    ): Double {

        return phaseAt(time)
            .successProbability
    }

    fun phaseAt(
        time: Long
    ): PreFailurePhase {

        return phases.firstOrNull {
            it.contains(time)
        } ?: phases.last()
    }

    companion object {

        /**
         * Seven-stage gradual degradation:
         *
         * 0.95 -> 0.85 -> 0.75 -> 0.65 ->
         * 0.55 -> 0.45 -> 0.35
         *
         * Each phase lasts 150 simulation-time units.
         */
        fun defaultProfile():
            PreFailureProfile {

            val probabilities =
                listOf(
                    0.95,
                    0.85,
                    0.75,
                    0.65,
                    0.55,
                    0.45,
                    0.35
                )

            val phaseDuration =
                150L

            val phases =
                probabilities.mapIndexed {
                        index,
                        probability ->

                    val start =
                        index *
                                phaseDuration

                    PreFailurePhase(
                        phaseIndex =
                            index + 1,

                        startTime =
                            start,

                        endTimeExclusive =
                            start +
                                    phaseDuration,

                        successProbability =
                            probability
                    )
                }

            return PreFailureProfile(
                phases
            )
        }
    }
}
