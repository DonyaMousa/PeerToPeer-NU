#!/usr/bin/env python3
"""
CARBLE phase-wise regime decision analysis
==========================================

Purpose
-------
Quantify how CARBLE's controller escalates across the frozen full-transition
experiment without adding new simulations.

This analysis uses only the frozen event-level CARBLE evidence.

Important terminology
---------------------
The outputs report DECISION/EVALUATION shares, not "time occupancy".
A CARBLE regime event represents a controller evaluation/action associated
with packet forwarding. Multiple evaluations can occur at the same simulation
time and different seeds can generate different numbers of events.

To avoid mixing actions with decisions, the decision population is restricted
to rows with a non-null currentHopConfidence. Those rows correspond to
controller evaluations that actually computed confidence.

Phase design
------------
Seven frozen 150-time-unit phases:

Phase 1: p=.90, instability evidence=0
Phase 2: p=.75, instability evidence=0
Phase 3: p=.60, instability evidence=0
Phase 4: p=.45, instability evidence=0
Phase 5: p=.30, instability evidence=2   (injected at t=600)
Phase 6: p=.15, instability evidence=5   (cumulative at t=750)
Phase 7: p=.05, instability evidence=5

Outputs
-------
Written to:
    app/build/research/FINAL-STATISTICS/

1) carble_phase_decision_by_seed.csv
   One row per seed × phase.

2) carble_phase_decision_summary.csv
   Across-seed descriptive statistics and 95% BCa bootstrap CI for:
   HIGH/M1/M2/M3/LOW decision shares, non-HIGH share, and escalation index.

3) carble_nominal_phase_summary.csv
   Phase-1 high-reliability behavior only.

4) carble_phase_transition_matrix.csv
   Counts of consecutive evaluation-regime transitions across all seeds.

The script does NOT modify raw evidence, thresholds, or simulation outputs.
"""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pandas as pd
from scipy import stats


ROOT = Path.cwd()

RESEARCH_CANDIDATES = [
    ROOT / "app" / "build" / "research",
    ROOT / "build" / "research",
]

RESEARCH = next(
    (p for p in RESEARCH_CANDIDATES if p.exists()),
    RESEARCH_CANDIDATES[0],
)

FULL = RESEARCH / "CARBLE-FULL-TRANSITION-COMPARISON"
STATS = RESEARCH / "FINAL-STATISTICS"

EVENT_FILE = FULL / "full_carble_transition_events.csv"

EXPECTED_SEEDS = set(range(1, 31))

PHASES = {
    1: (0, 150, 0.90, 0),
    2: (150, 300, 0.75, 0),
    3: (300, 450, 0.60, 0),
    4: (450, 600, 0.45, 0),
    5: (600, 750, 0.30, 2),
    6: (750, 900, 0.15, 5),
    7: (900, 1050, 0.05, 5),
}

REGIME_LEVEL = {
    "HIGH": 0,
    "M1": 1,
    "M2": 2,
    "M3": 3,
    "LOW": 4,
}

SHARE_METRICS = [
    "highShare",
    "m1Share",
    "m2Share",
    "m3Share",
    "lowShare",
    "nonHighShare",
    "meanEscalationLevel",
]


def _read(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(
            f"Required frozen event file not found:\n  {path}"
        )
    return pd.read_csv(path)


def _bootstrap_mean_ci(
    values: np.ndarray,
    confidence_level: float = 0.95,
    n_resamples: int = 10_000,
    seed: int = 20260902,
) -> tuple[float, float]:

    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values)]

    if len(values) == 0:
        return float("nan"), float("nan")

    if len(values) == 1 or np.all(values == values[0]):
        v = float(values[0])
        return v, v

    result = stats.bootstrap(
        (values,),
        np.mean,
        confidence_level=confidence_level,
        n_resamples=n_resamples,
        method="BCa",
        random_state=np.random.RandomState(seed),
        vectorized=False,
    )

    return (
        float(result.confidence_interval.low),
        float(result.confidence_interval.high),
    )


def _classify_stage(row: pd.Series) -> str:
    regime = str(row["regime"]).strip().upper()

    if regime == "MEDIUM":
        stage = str(row["mediumStage"]).strip().upper()
        if stage in {"M1", "M2", "M3"}:
            return stage
        raise ValueError(
            "MEDIUM evaluation event missing valid mediumStage."
        )

    if regime in {"HIGH", "LOW"}:
        return regime

    raise ValueError(f"Unknown CARBLE regime: {row['regime']}")


def load_decision_events() -> pd.DataFrame:
    events = _read(EVENT_FILE).copy()

    required = {
        "seed",
        "eventTime",
        "currentHopConfidence",
        "routeConfidence",
        "regime",
        "mediumStage",
    }

    missing = required - set(events.columns)
    if missing:
        raise ValueError(
            f"Event file missing required columns: {sorted(missing)}"
        )

    events["seed"] = pd.to_numeric(events["seed"], errors="raise").astype(int)
    events["eventTime"] = pd.to_numeric(
        events["eventTime"], errors="raise"
    )
    events["currentHopConfidence"] = pd.to_numeric(
        events["currentHopConfidence"], errors="coerce"
    )
    events["routeConfidence"] = pd.to_numeric(
        events["routeConfidence"], errors="coerce"
    )

    # Evaluation rows have an actual Qcurrent. Action-only rows do not.
    decisions = events[
        events["currentHopConfidence"].notna()
    ].copy()

    if decisions.empty:
        raise ValueError(
            "No controller evaluation events were found."
        )

    if set(decisions["seed"].unique()) != EXPECTED_SEEDS:
        raise ValueError(
            "Expected CARBLE decision evidence for seeds 1..30."
        )

    decisions["stage"] = decisions.apply(
        _classify_stage,
        axis=1,
    )

    decisions["phaseIndex"] = (
        (decisions["eventTime"] // 150)
        .astype(int)
        .clip(lower=0, upper=6)
        + 1
    )

    return decisions.sort_values(
        ["seed", "eventTime"]
    ).reset_index(drop=True)


def build_phase_by_seed(
    decisions: pd.DataFrame,
) -> pd.DataFrame:

    rows: list[dict] = []

    for seed in sorted(EXPECTED_SEEDS):
        seed_df = decisions[decisions["seed"] == seed]

        for phase_index, (
            start,
            end,
            success_probability,
            instability,
        ) in PHASES.items():

            phase = seed_df[
                seed_df["phaseIndex"] == phase_index
            ]

            total = len(phase)

            if total == 0:
                raise ValueError(
                    f"Seed {seed}, phase {phase_index} has no decision events."
                )

            counts = {
                stage: int((phase["stage"] == stage).sum())
                for stage in REGIME_LEVEL
            }

            shares = {
                stage: counts[stage] / total
                for stage in REGIME_LEVEL
            }

            escalation_level = float(
                phase["stage"]
                .map(REGIME_LEVEL)
                .mean()
            )

            rows.append(
                {
                    "seed": seed,
                    "phaseIndex": phase_index,
                    "phaseStart": start,
                    "phaseEndExclusive": end,
                    "successProbability": success_probability,
                    "cumulativeInstabilityEvidence": instability,
                    "decisionCount": total,
                    "HIGH": counts["HIGH"],
                    "M1": counts["M1"],
                    "M2": counts["M2"],
                    "M3": counts["M3"],
                    "LOW": counts["LOW"],
                    "highShare": shares["HIGH"],
                    "m1Share": shares["M1"],
                    "m2Share": shares["M2"],
                    "m3Share": shares["M3"],
                    "lowShare": shares["LOW"],
                    "nonHighShare": 1.0 - shares["HIGH"],
                    "meanEscalationLevel": escalation_level,
                    "meanQcurrent": float(
                        phase["currentHopConfidence"].mean()
                    ),
                    "meanQroute": float(
                        phase["routeConfidence"].mean()
                    ),
                    "minQcurrent": float(
                        phase["currentHopConfidence"].min()
                    ),
                    "minQroute": float(
                        phase["routeConfidence"].min()
                    ),
                }
            )

    result = pd.DataFrame(rows)

    if len(result) != 30 * 7:
        raise ValueError(
            f"Expected 210 seed×phase rows, found {len(result)}."
        )

    return result


def build_phase_summary(
    by_seed: pd.DataFrame,
) -> pd.DataFrame:

    rows: list[dict] = []

    for phase_index, phase in by_seed.groupby(
        "phaseIndex",
        sort=True,
    ):

        metadata = phase.iloc[0]

        for metric in SHARE_METRICS:
            values = pd.to_numeric(
                phase[metric],
                errors="raise",
            ).to_numpy(float)

            low, high = _bootstrap_mean_ci(
                values,
                seed=20260902 + phase_index,
            )

            rows.append(
                {
                    "phaseIndex": int(phase_index),
                    "successProbability": float(
                        metadata["successProbability"]
                    ),
                    "cumulativeInstabilityEvidence": int(
                        metadata["cumulativeInstabilityEvidence"]
                    ),
                    "metric": metric,
                    "nSeeds": len(values),
                    "mean": float(np.mean(values)),
                    "median": float(np.median(values)),
                    "sd": float(np.std(values, ddof=1)),
                    "iqr": float(
                        np.percentile(values, 75)
                        - np.percentile(values, 25)
                    ),
                    "min": float(np.min(values)),
                    "max": float(np.max(values)),
                    "meanCi95Low_BCa": low,
                    "meanCi95High_BCa": high,
                }
            )

    return pd.DataFrame(rows)


def build_nominal_summary(
    by_seed: pd.DataFrame,
) -> pd.DataFrame:

    phase1 = by_seed[
        by_seed["phaseIndex"] == 1
    ].copy()

    rows: list[dict] = []

    for metric in SHARE_METRICS:
        values = pd.to_numeric(
            phase1[metric],
            errors="raise",
        ).to_numpy(float)

        low, high = _bootstrap_mean_ci(
            values,
            seed=20261001,
        )

        rows.append(
            {
                "phaseIndex": 1,
                "successProbability": 0.90,
                "cumulativeInstabilityEvidence": 0,
                "metric": metric,
                "nSeeds": len(values),
                "mean": float(np.mean(values)),
                "median": float(np.median(values)),
                "sd": float(np.std(values, ddof=1)),
                "iqr": float(
                    np.percentile(values, 75)
                    - np.percentile(values, 25)
                ),
                "min": float(np.min(values)),
                "max": float(np.max(values)),
                "meanCi95Low_BCa": low,
                "meanCi95High_BCa": high,
            }
        )

    return pd.DataFrame(rows)


def build_transition_matrix(
    decisions: pd.DataFrame,
) -> pd.DataFrame:

    transitions: dict[tuple[str, str], int] = {}

    for seed, seed_df in decisions.groupby(
        "seed",
        sort=True,
    ):

        stages = seed_df[
            "stage"
        ].tolist()

        for previous, current in zip(
            stages[:-1],
            stages[1:],
        ):
            key = (previous, current)
            transitions[key] = transitions.get(key, 0) + 1

    rows = []

    for previous in REGIME_LEVEL:
        for current in REGIME_LEVEL:
            rows.append(
                {
                    "fromStage": previous,
                    "toStage": current,
                    "count": transitions.get(
                        (previous, current),
                        0,
                    ),
                }
            )

    result = pd.DataFrame(rows)

    totals = (
        result.groupby("fromStage")["count"]
        .transform("sum")
    )

    result["shareWithinFromStage"] = np.where(
        totals > 0,
        result["count"] / totals,
        np.nan,
    )

    return result


def print_key_results(
    summary: pd.DataFrame,
    nominal: pd.DataFrame,
) -> None:

    def metric_row(
        frame: pd.DataFrame,
        phase: int,
        metric: str,
    ) -> pd.Series:
        row = frame[
            (frame["phaseIndex"] == phase)
            & (frame["metric"] == metric)
        ]
        if len(row) != 1:
            raise ValueError(
                f"Expected one row for phase={phase}, metric={metric}."
            )
        return row.iloc[0]

    high_nominal = metric_row(
        nominal,
        1,
        "highShare",
    )

    nonhigh_nominal = metric_row(
        nominal,
        1,
        "nonHighShare",
    )

    print()
    print("==========================================================================")
    print("CARBLE PHASE-WISE DECISION ANALYSIS")
    print(
        "Phase 1 nominal/high-reliability HIGH decision share: "
        f"{high_nominal['mean']:.4f} "
        f"(95% BCa CI {high_nominal['meanCi95Low_BCa']:.4f}–"
        f"{high_nominal['meanCi95High_BCa']:.4f})"
    )
    print(
        "Phase 1 non-HIGH decision share: "
        f"{nonhigh_nominal['mean']:.4f} "
        f"(95% BCa CI {nonhigh_nominal['meanCi95Low_BCa']:.4f}–"
        f"{nonhigh_nominal['meanCi95High_BCa']:.4f})"
    )
    print()
    print(
        "phase,p,instability,HIGHshare,M1share,M2share,M3share,LOWshare,"
        "meanEscalationLevel"
    )

    for phase in range(1, 8):
        values = {}

        for metric in [
            "highShare",
            "m1Share",
            "m2Share",
            "m3Share",
            "lowShare",
            "meanEscalationLevel",
        ]:
            values[metric] = float(
                metric_row(
                    summary,
                    phase,
                    metric,
                )["mean"]
            )

        p = PHASES[phase][2]
        instability = PHASES[phase][3]

        print(
            f"{phase},{p:.2f},{instability},"
            f"{values['highShare']:.4f},"
            f"{values['m1Share']:.4f},"
            f"{values['m2Share']:.4f},"
            f"{values['m3Share']:.4f},"
            f"{values['lowShare']:.4f},"
            f"{values['meanEscalationLevel']:.4f}"
        )

    print("==========================================================================")



def main() -> None:

    STATS.mkdir(
        parents=True,
        exist_ok=True,
    )

    decisions = load_decision_events()

    by_seed = build_phase_by_seed(
        decisions
    )

    summary = build_phase_summary(
        by_seed
    )

    nominal = build_nominal_summary(
        by_seed
    )

    transitions = build_transition_matrix(
        decisions
    )

    by_seed.to_csv(
        STATS / "carble_phase_decision_by_seed.csv",
        index=False,
    )

    summary.to_csv(
        STATS / "carble_phase_decision_summary.csv",
        index=False,
    )

    nominal.to_csv(
        STATS / "carble_nominal_phase_summary.csv",
        index=False,
    )

    transitions.to_csv(
        STATS / "carble_phase_transition_matrix.csv",
        index=False,
    )

    print_key_results(
        summary,
        nominal,
    )

    print(
        f"Output: {STATS.resolve()}"
    )


if __name__ == "__main__":
    main()
