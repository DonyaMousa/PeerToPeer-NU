#!/usr/bin/env python3
"""
CARBLE routing scalability statistics
====================================

Purpose
-------
Analyze the frozen JVM-side computational scalability benchmark:

    4 protocols x 2 topology densities x 5 node counts x 30 graph seeds
    = 1200 independent benchmark rows.

Statistical unit
----------------
One graph seed / benchmark row.

The 500 measured routing decisions inside each row are NOT treated as
independent replications. The primary computational outcome is the
within-seed median routing/controller latency (microseconds).

Primary comparisons
-------------------
For each topology x node-count block:

    CARBLE vs B0
    CARBLE vs MM
    CARBLE vs TWO_RH

Inference
---------
- paired Wilcoxon signed-rank test
- matched-pairs rank-biserial effect size
- 95% BCa bootstrap CI of paired mean difference
- 95% BCa bootstrap CI of paired median-latency ratio
- Holm correction across the three CARBLE contrasts within each
  topology x node-count family

Additional descriptive scaling analysis
---------------------------------------
For each protocol x topology:
- log-log slope of mean seed-median latency versus node count
- R^2
This is descriptive, not a claim of asymptotic complexity.

Outputs
-------
app/build/research/FINAL-STATISTICS/

    scalability_descriptive.csv
    scalability_pairwise_carble.csv
    scalability_scaling_summary.csv
    scalability_interpretation.txt
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
    / "CARBLE-ROUTING-SCALABILITY"
    / "routing_scalability_runs.csv"
)

OUT = RESEARCH / "FINAL-STATISTICS"

PROTOCOLS = ["B0", "MM", "TWO_RH", "CARBLE"]
TOPOLOGIES = ["SPARSE", "MODERATE"]
NODE_COUNTS = [10, 25, 50, 100, 200]
EXPECTED_SEEDS = set(range(1, 31))

PRIMARY_METRIC = "medianLatencyUs"
SECONDARY_METRICS = [
    "meanLatencyUs",
    "p95LatencyUs",
]

BASELINES = ["B0", "MM", "TWO_RH"]


def _read() -> pd.DataFrame:
    if not SOURCE.exists():
        raise FileNotFoundError(
            f"Scalability dataset not found:\n  {SOURCE}"
        )

    df = pd.read_csv(SOURCE)

    required = {
        "protocol",
        "topology",
        "nodeCount",
        "seed",
        "undirectedEdgeCount",
        "measuredDecisions",
        "pathFoundCount",
        "meanPathHops",
        "meanLatencyUs",
        "medianLatencyUs",
        "p95LatencyUs",
    }

    missing = required - set(df.columns)
    if missing:
        raise ValueError(
            f"Scalability dataset missing columns: {sorted(missing)}"
        )

    df["seed"] = pd.to_numeric(
        df["seed"],
        errors="raise",
    ).astype(int)

    df["nodeCount"] = pd.to_numeric(
        df["nodeCount"],
        errors="raise",
    ).astype(int)

    if len(df) != 1200:
        raise ValueError(
            f"Expected 1200 benchmark rows, found {len(df)}."
        )

    for topology in TOPOLOGIES:
        for node_count in NODE_COUNTS:
            block = df[
                (df["topology"] == topology)
                & (df["nodeCount"] == node_count)
            ]

            for protocol in PROTOCOLS:
                seeds = set(
                    block.loc[
                        block["protocol"] == protocol,
                        "seed",
                    ].tolist()
                )

                if seeds != EXPECTED_SEEDS:
                    raise ValueError(
                        f"{topology} N={node_count} {protocol}: "
                        f"expected seeds 1..30."
                    )

    if df.duplicated(
        ["protocol", "topology", "nodeCount", "seed"]
    ).any():
        raise ValueError(
            "Duplicate protocol/topology/nodeCount/seed rows detected."
        )

    if not (
        (df["measuredDecisions"] == 500)
        & (df["pathFoundCount"] == 500)
    ).all():
        raise ValueError(
            "Expected all benchmark rows to contain 500 measured decisions "
            "with 500 successful route/controller outcomes."
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


def _bootstrap_ratio_ci(
    numerator: np.ndarray,
    denominator: np.ndarray,
    seed: int,
) -> tuple[float, float]:
    numerator = np.asarray(numerator, dtype=float)
    denominator = np.asarray(denominator, dtype=float)

    finite = (
        np.isfinite(numerator)
        & np.isfinite(denominator)
        & (denominator > 0)
    )

    numerator = numerator[finite]
    denominator = denominator[finite]

    if len(numerator) == 0:
        return np.nan, np.nan

    ratios = numerator / denominator

    return _bootstrap_mean_ci(
        ratios,
        seed=seed,
    )


def _rank_biserial(differences: np.ndarray) -> float:
    d = np.asarray(differences, dtype=float)
    d = d[np.isfinite(d)]
    d = d[d != 0]

    if len(d) == 0:
        return 0.0

    ranks = stats.rankdata(
        np.abs(d),
        method="average",
    )

    positive = float(ranks[d > 0].sum())
    negative = float(ranks[d < 0].sum())

    denominator = positive + negative

    if denominator == 0:
        return 0.0

    return (positive - negative) / denominator


def _wilcoxon_p(differences: np.ndarray) -> float:
    d = np.asarray(differences, dtype=float)
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


def build_descriptive(df: pd.DataFrame) -> pd.DataFrame:
    rows = []

    for topology in TOPOLOGIES:
        for node_count in NODE_COUNTS:
            for protocol in PROTOCOLS:
                g = df[
                    (df["topology"] == topology)
                    & (df["nodeCount"] == node_count)
                    & (df["protocol"] == protocol)
                ]

                for metric in [
                    PRIMARY_METRIC,
                    *SECONDARY_METRICS,
                    "meanPathHops",
                    "undirectedEdgeCount",
                ]:
                    values = pd.to_numeric(
                        g[metric],
                        errors="raise",
                    ).to_numpy(float)

                    lo, hi = _bootstrap_mean_ci(
                        values,
                        seed=20260930 + len(rows),
                    )

                    rows.append(
                        {
                            "topology": topology,
                            "nodeCount": node_count,
                            "protocol": protocol,
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
                            "meanCi95Low_BCa": lo,
                            "meanCi95High_BCa": hi,
                        }
                    )

    return pd.DataFrame(rows)


def build_pairwise(df: pd.DataFrame) -> pd.DataFrame:
    rows = []

    for topology in TOPOLOGIES:
        for node_count in NODE_COUNTS:

            block = df[
                (df["topology"] == topology)
                & (df["nodeCount"] == node_count)
            ]

            carble = (
                block[
                    block["protocol"] == "CARBLE"
                ]
                .set_index("seed")
                .sort_index()
            )

            for metric in [
                PRIMARY_METRIC,
                *SECONDARY_METRICS,
            ]:
                metric_rows = []

                for baseline_name in BASELINES:
                    baseline = (
                        block[
                            block["protocol"] == baseline_name
                        ]
                        .set_index("seed")
                        .sort_index()
                    )

                    seeds = sorted(EXPECTED_SEEDS)

                    c = pd.to_numeric(
                        carble.loc[
                            seeds,
                            metric,
                        ],
                        errors="raise",
                    ).to_numpy(float)

                    b = pd.to_numeric(
                        baseline.loc[
                            seeds,
                            metric,
                        ],
                        errors="raise",
                    ).to_numpy(float)

                    difference = c - b
                    ratio = c / b

                    diff_lo, diff_hi = _bootstrap_mean_ci(
                        difference,
                        seed=20261030 + len(rows) + len(metric_rows),
                    )

                    ratio_lo, ratio_hi = _bootstrap_ratio_ci(
                        c,
                        b,
                        seed=20261130 + len(rows) + len(metric_rows),
                    )

                    metric_rows.append(
                        {
                            "topology": topology,
                            "nodeCount": node_count,
                            "metric": metric,
                            "comparison": f"CARBLE_vs_{baseline_name}",
                            "baseline": baseline_name,
                            "nPairedSeeds": len(seeds),
                            "carbleMean": float(np.mean(c)),
                            "baselineMean": float(np.mean(b)),
                            "meanPairedDifferenceUs_CARBLEminusBaseline":
                                float(np.mean(difference)),
                            "differenceCi95Low_BCa": diff_lo,
                            "differenceCi95High_BCa": diff_hi,
                            "meanPairedRatio_CARBLEoverBaseline":
                                float(np.mean(ratio)),
                            "ratioCi95Low_BCa": ratio_lo,
                            "ratioCi95High_BCa": ratio_hi,
                            "relativeOverheadPercentFromMeanRatio":
                                float((np.mean(ratio) - 1.0) * 100.0),
                            "wilcoxonP": _wilcoxon_p(difference),
                            "rankBiserial": _rank_biserial(difference),
                        }
                    )

                p_adjusted = _holm(
                    [
                        r["wilcoxonP"]
                        for r in metric_rows
                    ]
                )

                for row, adjusted in zip(
                    metric_rows,
                    p_adjusted,
                ):
                    row["wilcoxonP_Holm"] = adjusted
                    rows.append(row)

    return pd.DataFrame(rows)


def build_scaling_summary(
    descriptive_df: pd.DataFrame,
) -> pd.DataFrame:
    rows = []

    primary = descriptive_df[
        descriptive_df["metric"] == PRIMARY_METRIC
    ]

    for topology in TOPOLOGIES:
        for protocol in PROTOCOLS:
            g = primary[
                (primary["topology"] == topology)
                & (primary["protocol"] == protocol)
            ].sort_values("nodeCount")

            x = np.log(
                g["nodeCount"].to_numpy(float)
            )

            y = np.log(
                g["mean"].to_numpy(float)
            )

            slope, intercept = np.polyfit(
                x,
                y,
                1,
            )

            predicted = intercept + slope * x

            ss_res = float(
                np.sum(
                    (y - predicted) ** 2
                )
            )

            ss_tot = float(
                np.sum(
                    (y - np.mean(y)) ** 2
                )
            )

            r_squared = (
                1.0 - ss_res / ss_tot
                if ss_tot > 0
                else np.nan
            )

            rows.append(
                {
                    "topology": topology,
                    "protocol": protocol,
                    "descriptiveLogLogSlope": float(slope),
                    "rSquared": r_squared,
                    "nodeCounts": "10;25;50;100;200",
                    "note":
                        "Descriptive empirical scaling only; "
                        "not an asymptotic complexity estimate.",
                }
            )

    return pd.DataFrame(rows)


def write_interpretation(
    desc: pd.DataFrame,
    pair: pd.DataFrame,
    scaling: pd.DataFrame,
) -> None:

    def mean_latency(
        topology: str,
        node_count: int,
        protocol: str,
    ) -> float:
        row = desc[
            (desc["topology"] == topology)
            & (desc["nodeCount"] == node_count)
            & (desc["protocol"] == protocol)
            & (desc["metric"] == PRIMARY_METRIC)
        ]

        return float(row.iloc[0]["mean"])

    lines = [
        "CARBLE ROUTING SCALABILITY — ANALYSIS NOTE",
        "==========================================",
        "",
        "Primary outcome:",
        "  within-seed median routing/controller decision latency (microseconds)",
        "",
        "Interpretive boundary:",
        "  This benchmark measures JVM-side synchronous computation only.",
        "  It is not BLE packet latency, Android-device energy, or end-to-end delay.",
        "",
    ]

    for topology in TOPOLOGIES:
        lines.append(f"{topology}:")
        for n in NODE_COUNTS:
            values = ", ".join(
                f"{p}={mean_latency(topology, n, p):.3f} us"
                for p in PROTOCOLS
            )
            lines.append(f"  N={n}: {values}")
        lines.append("")

    lines.extend(
        [
            "Reporting guidance:",
            "- Use paired seed-level inference; do not treat the 500 decisions as independent runs.",
            "- Emphasize absolute microsecond cost as well as relative overhead.",
            "- For larger graphs, MM/2RH/CARBLE may converge in absolute cost because",
            "  graph search dominates the fixed controller-classification overhead.",
            "- Do not interpret small negative CARBLE-vs-MM/2RH differences as",
            "  algorithmic speedups unless paired confidence intervals support them.",
            "- The log-log slope is descriptive only.",
            "",
        ]
    )

    (OUT / "scalability_interpretation.txt").write_text(
        "\n".join(lines),
        encoding="utf-8",
    )


def main() -> None:
    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    df = _read()

    desc = build_descriptive(df)
    pair = build_pairwise(df)
    scaling = build_scaling_summary(desc)

    desc.to_csv(
        OUT / "scalability_descriptive.csv",
        index=False,
    )

    pair.to_csv(
        OUT / "scalability_pairwise_carble.csv",
        index=False,
    )

    scaling.to_csv(
        OUT / "scalability_scaling_summary.csv",
        index=False,
    )

    write_interpretation(
        desc,
        pair,
        scaling,
    )

    primary_pairs = pair[
        pair["metric"] == PRIMARY_METRIC
    ][
        [
            "topology",
            "nodeCount",
            "comparison",
            "meanPairedDifferenceUs_CARBLEminusBaseline",
            "differenceCi95Low_BCa",
            "differenceCi95High_BCa",
            "meanPairedRatio_CARBLEoverBaseline",
            "relativeOverheadPercentFromMeanRatio",
            "wilcoxonP_Holm",
            "rankBiserial",
        ]
    ]

    print()
    print("==============================================================================================================")
    print("ROUTING SCALABILITY STATISTICS COMPLETE")
    print(f"Output: {OUT.resolve()}")
    print()
    print("Primary outcome: within-seed median decision latency (us)")
    print(primary_pairs.to_string(index=False))
    print()
    print("Descriptive log-log scaling:")
    print(
        scaling[
            [
                "topology",
                "protocol",
                "descriptiveLogLogSlope",
                "rSquared",
            ]
        ].to_string(index=False)
    )
    print("==============================================================================================================")


if __name__ == "__main__":
    main()
