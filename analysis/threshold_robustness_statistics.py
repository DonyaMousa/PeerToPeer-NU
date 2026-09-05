#!/usr/bin/env python3
"""
CARBLE threshold-robustness statistical analysis
================================================

Purpose
-------
Quantify whether the frozen CARBLE full-transition conclusions are sensitive
to moderate, pre-specified shifts in the regime thresholds.

Configurations
--------------
EARLY   = .80 / .70 / .60 / .50
NOMINAL = .75 / .65 / .55 / .45
LATE    = .70 / .60 / .50 / .40

NOMINAL remains the official CARBLE-v1.0 configuration regardless of outcome.

Design
------
30 paired seeds per threshold configuration.
Statistical unit = one seed/run.

Planned contrasts:
    EARLY - NOMINAL
    LATE  - NOMINAL

Metrics:
    PDR
    conditional mean latency
    physical attempts
    retransmissions
    attempts per delivered packet
    retransmissions per delivered packet
    first M1/M2/M3/LOW entry where defined

Inference:
    paired Wilcoxon signed-rank
    matched-pairs rank-biserial correlation
    95% BCa bootstrap CI of paired mean difference
    Holm correction within metric across the two planned contrasts

Outputs
-------
app/build/research/FINAL-STATISTICS/
    threshold_robustness_descriptive.csv
    threshold_robustness_pairwise.csv
    threshold_robustness_stage_reach.csv
    threshold_robustness_interpretation.txt
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

SOURCE = (
    RESEARCH
    / "CARBLE-THRESHOLD-ROBUSTNESS"
    / "carble_threshold_robustness_runs.csv"
)

OUT = RESEARCH / "FINAL-STATISTICS"

CONFIGS = ["EARLY", "NOMINAL", "LATE"]
EXPECTED_SEEDS = set(range(1, 31))

METRICS = [
    "pdr",
    "conditionalMeanLatency",
    "physicalAttempts",
    "retransmissions",
    "attemptsPerDelivered",
    "retransmissionsPerDelivered",
]

TIME_METRICS = [
    "firstM1Time",
    "firstM2Time",
    "firstM3Time",
    "firstLowTime",
]


def _read() -> pd.DataFrame:
    if not SOURCE.exists():
        raise FileNotFoundError(
            f"Threshold robustness dataset not found:\n  {SOURCE}"
        )

    df = pd.read_csv(SOURCE)

    required = {
        "thresholdConfig",
        "seed",
        "pdr",
        "conditionalMeanLatency",
        "physicalAttempts",
        "retransmissions",
        "attemptsPerDelivered",
        "retransmissionsPerDelivered",
        "firstM1Time",
        "firstM2Time",
        "firstM3Time",
        "firstLowTime",
        "hasAllStages",
        "strictFirstEntryOrder",
    }

    missing = required - set(df.columns)
    if missing:
        raise ValueError(
            f"Threshold dataset missing required columns: {sorted(missing)}"
        )

    df["seed"] = pd.to_numeric(df["seed"], errors="raise").astype(int)

    if len(df) != 90:
        raise ValueError(
            f"Expected 90 runs (3 configs × 30 seeds), found {len(df)}."
        )

    for config in CONFIGS:
        seeds = set(
            df.loc[
                df["thresholdConfig"] == config,
                "seed",
            ].tolist()
        )

        if seeds != EXPECTED_SEEDS:
            raise ValueError(
                f"{config}: expected seeds 1..30, found {sorted(seeds)}."
            )

    if df.duplicated(["thresholdConfig", "seed"]).any():
        raise ValueError(
            "Duplicate thresholdConfig/seed rows detected."
        )

    return df


def _bootstrap_mean_ci(
    values: np.ndarray,
    seed: int,
) -> tuple[float, float]:

    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values)]

    if len(values) == 0:
        return np.nan, np.nan

    if len(values) == 1 or np.all(values == values[0]):
        v = float(values[0])
        return v, v

    result = stats.bootstrap(
        (values,),
        np.mean,
        confidence_level=0.95,
        n_resamples=10_000,
        method="BCa",
        random_state=np.random.RandomState(seed),
        vectorized=False,
    )

    return (
        float(result.confidence_interval.low),
        float(result.confidence_interval.high),
    )


def _rank_biserial(d: np.ndarray) -> float:
    d = np.asarray(d, dtype=float)
    d = d[np.isfinite(d)]
    d = d[d != 0]

    if len(d) == 0:
        return 0.0

    ranks = stats.rankdata(
        np.abs(d),
        method="average",
    )

    w_plus = float(ranks[d > 0].sum())
    w_minus = float(ranks[d < 0].sum())

    denom = w_plus + w_minus

    if denom == 0:
        return 0.0

    return (w_plus - w_minus) / denom


def _wilcoxon_p(d: np.ndarray) -> float:
    d = np.asarray(d, dtype=float)
    d = d[np.isfinite(d)]

    if len(d) == 0 or np.all(d == 0):
        return 1.0

    return float(
        stats.wilcoxon(
            d,
            zero_method="wilcox",
            correction=False,
            alternative="two-sided",
            method="auto",
        ).pvalue
    )


def _holm(p_values: list[float]) -> list[float]:
    p = np.asarray(p_values, dtype=float)
    m = len(p)

    order = np.argsort(p)
    adjusted_sorted = np.empty(m, dtype=float)

    running = 0.0

    for rank, idx in enumerate(order):
        candidate = (m - rank) * p[idx]
        running = max(running, candidate)
        adjusted_sorted[rank] = min(running, 1.0)

    adjusted = np.empty(m, dtype=float)

    for rank, idx in enumerate(order):
        adjusted[idx] = adjusted_sorted[rank]

    return adjusted.tolist()


def descriptive(df: pd.DataFrame) -> pd.DataFrame:
    rows = []

    for config in CONFIGS:
        g = df[df["thresholdConfig"] == config]

        for metric in METRICS + TIME_METRICS:
            values = pd.to_numeric(
                g[metric],
                errors="coerce",
            ).dropna().to_numpy(float)

            if len(values) == 0:
                continue

            lo, hi = _bootstrap_mean_ci(
                values,
                seed=20260920 + len(rows),
            )

            rows.append(
                {
                    "thresholdConfig": config,
                    "metric": metric,
                    "n": len(values),
                    "mean": float(np.mean(values)),
                    "median": float(np.median(values)),
                    "sd": (
                        float(np.std(values, ddof=1))
                        if len(values) > 1
                        else np.nan
                    ),
                    "iqr": float(
                        np.percentile(values, 75)
                        - np.percentile(values, 25)
                    ),
                    "min": float(np.min(values)),
                    "max": float(np.max(values)),
                    "meanCi95Low_BCa": lo,
                    "meanCi95High_BCa": hi,
                }
            )

    return pd.DataFrame(rows)


def pairwise(df: pd.DataFrame) -> pd.DataFrame:
    rows = []

    nominal = (
        df[df["thresholdConfig"] == "NOMINAL"]
        .set_index("seed")
        .sort_index()
    )

    for candidate_name in ["EARLY", "LATE"]:
        candidate = (
            df[df["thresholdConfig"] == candidate_name]
            .set_index("seed")
            .sort_index()
        )

        for metric in METRICS:
            c = pd.to_numeric(
                candidate.loc[
                    sorted(EXPECTED_SEEDS),
                    metric,
                ],
                errors="coerce",
            ).to_numpy(float)

            n = pd.to_numeric(
                nominal.loc[
                    sorted(EXPECTED_SEEDS),
                    metric,
                ],
                errors="coerce",
            ).to_numpy(float)

            finite = np.isfinite(c) & np.isfinite(n)

            c = c[finite]
            n = n[finite]
            d = c - n

            lo, hi = _bootstrap_mean_ci(
                d,
                seed=20261010 + len(rows),
            )

            nominal_mean = float(np.mean(n))
            mean_diff = float(np.mean(d))

            rows.append(
                {
                    "metric": metric,
                    "comparison": f"{candidate_name}_vs_NOMINAL",
                    "candidate": candidate_name,
                    "nPairedSeeds": len(d),
                    "candidateMean": float(np.mean(c)),
                    "nominalMean": nominal_mean,
                    "meanPairedDifference_candidateMinusNominal": mean_diff,
                    "differenceCi95Low_BCa": lo,
                    "differenceCi95High_BCa": hi,
                    "pdrDifferencePercentagePoints": (
                        mean_diff * 100.0
                        if metric == "pdr"
                        else np.nan
                    ),
                    "relativeMeanChangePercent": (
                        mean_diff / nominal_mean * 100.0
                        if nominal_mean != 0
                        else np.nan
                    ),
                    "wilcoxonP": _wilcoxon_p(d),
                    "rankBiserial": _rank_biserial(d),
                }
            )

    result = pd.DataFrame(rows)
    result["wilcoxonP_Holm"] = np.nan

    for metric, idx in result.groupby("metric").groups.items():
        result.loc[
            idx,
            "wilcoxonP_Holm",
        ] = _holm(
            result.loc[
                idx,
                "wilcoxonP",
            ].tolist()
        )

    return result


def stage_reach(df: pd.DataFrame) -> pd.DataFrame:
    rows = []

    for config in CONFIGS:
        g = df[
            df["thresholdConfig"] == config
        ].copy()

        for metric in [
            "hasAllStages",
            "strictFirstEntryOrder",
        ]:
            values = (
                g[metric]
                .astype(str)
                .str.lower()
                .map(
                    {
                        "true": 1.0,
                        "false": 0.0,
                    }
                )
            )

            rows.append(
                {
                    "thresholdConfig": config,
                    "metric": metric,
                    "nSeeds": int(values.notna().sum()),
                    "countTrue": int(values.sum()),
                    "proportionTrue": float(values.mean()),
                }
            )

        for stage_metric in TIME_METRICS:
            valid = pd.to_numeric(
                g[stage_metric],
                errors="coerce",
            )

            rows.append(
                {
                    "thresholdConfig": config,
                    "metric": f"{stage_metric}_observed",
                    "nSeeds": 30,
                    "countTrue": int(valid.notna().sum()),
                    "proportionTrue": float(valid.notna().mean()),
                }
            )

    return pd.DataFrame(rows)


def write_interpretation(
    df: pd.DataFrame,
    pair: pd.DataFrame,
) -> None:

    means = (
        df.groupby(
            "thresholdConfig"
        )[
            [
                "pdr",
                "conditionalMeanLatency",
                "attemptsPerDelivered",
            ]
        ]
        .mean()
    )

    early = means.loc["EARLY"]
    nominal = means.loc["NOMINAL"]
    late = means.loc["LATE"]

    text = f"""CARBLE THRESHOLD ROBUSTNESS — ANALYSIS NOTE
==========================================

This is a robustness analysis, not a threshold-selection exercise.
NOMINAL (.75/.65/.55/.45) remains CARBLE-v1.0.

Mean outcomes:
EARLY:
  PDR={early['pdr']:.6f}
  conditional latency={early['conditionalMeanLatency']:.6f}
  attempts/delivered={early['attemptsPerDelivered']:.6f}

NOMINAL:
  PDR={nominal['pdr']:.6f}
  conditional latency={nominal['conditionalMeanLatency']:.6f}
  attempts/delivered={nominal['attemptsPerDelivered']:.6f}

LATE:
  PDR={late['pdr']:.6f}
  conditional latency={late['conditionalMeanLatency']:.6f}
  attempts/delivered={late['attemptsPerDelivered']:.6f}

Interpretive boundary:
- If EARLY/LATE remain directionally better than the frozen 2RH full-transition
  baseline on PDR and conditional latency, the architectural conclusion is
  robust to moderate threshold shifts.
- If stage reach/order changes, that should be reported explicitly. Stage
  traversal is a controller-mechanism property and may be more threshold
  sensitive than aggregate delivery performance.
- Do not choose EARLY or LATE as a replacement configuration based on these
  results. Doing so would turn a robustness study into post-hoc tuning.
"""

    (OUT / "threshold_robustness_interpretation.txt").write_text(
        text,
        encoding="utf-8",
    )


def main() -> None:
    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    df = _read()

    desc = descriptive(df)
    pair = pairwise(df)
    reach = stage_reach(df)

    desc.to_csv(
        OUT / "threshold_robustness_descriptive.csv",
        index=False,
    )

    pair.to_csv(
        OUT / "threshold_robustness_pairwise.csv",
        index=False,
    )

    reach.to_csv(
        OUT / "threshold_robustness_stage_reach.csv",
        index=False,
    )

    write_interpretation(
        df,
        pair,
    )

    print()
    print("======================================================================")
    print("THRESHOLD ROBUSTNESS STATISTICS COMPLETE")
    print(f"Output: {OUT.resolve()}")
    print()
    print("Primary paired contrasts:")
    print(
        pair[
            pair["metric"].isin(
                [
                    "pdr",
                    "conditionalMeanLatency",
                    "attemptsPerDelivered",
                ]
            )
        ][
            [
                "comparison",
                "metric",
                "meanPairedDifference_candidateMinusNominal",
                "differenceCi95Low_BCa",
                "differenceCi95High_BCa",
                "wilcoxonP_Holm",
                "rankBiserial",
            ]
        ].to_string(index=False)
    )
    print("======================================================================")


if __name__ == "__main__":
    main()
