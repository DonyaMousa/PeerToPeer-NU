#!/usr/bin/env python3
"""
CARBLE final simulation statistical analysis
============================================

Run from the Android project root:

    python analysis/final_simulation_statistics.py

Required packages:
    pandas
    numpy
    scipy

This script does NOT rerun simulations and does NOT modify raw research data.
It reads the frozen CSV evidence under build/research/ and writes derived
statistical outputs to:

    build/research/FINAL-STATISTICS/

Statistical unit:
    one independent simulation seed/run (NOT individual packets)

Planned inferential comparisons:
    CARBLE vs B0
    CARBLE vs MM
    CARBLE vs 2RH

Paired by seed within scenario/condition.

Primary endpoint:
    PDR

Secondary endpoints:
    conditional mean end-to-end latency among delivered packets,
    physical attempts,
    retransmissions,
    attempts per delivered packet,
    retransmissions per delivered packet.

Inference:
    - descriptive mean/median/SD/IQR/min/max
    - 95% BCa bootstrap CI for run-level means
    - paired Wilcoxon signed-rank test
    - paired rank-biserial correlation
    - 95% BCa bootstrap CI for paired mean differences
    - Holm correction within each experiment-family × metric
    - Friedman omnibus test across all four protocols, paired by seed

Resource note:
    attempts/retransmissions are simulation resource/energy proxies;
    they are NOT measured joules or battery drain.
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

try:
    import numpy as np
    import pandas as pd
    from scipy import stats
except ImportError as exc:
    raise SystemExit(
        "Missing scientific Python dependency. Install with:\n"
        "  python -m pip install pandas numpy scipy\n\n"
        f"Original import error: {exc}"
    )


ROOT = Path.cwd()

# Android unit-test outputs normally live under:
#   <project>/app/build/research/
#
# Older/manual exports may live under:
#   <project>/build/research/
#
# Prefer app/build/research when it exists, but support both.
RESEARCH_CANDIDATES = [
    ROOT / "app" / "build" / "research",
    ROOT / "build" / "research",
]

RESEARCH = next(
    (path for path in RESEARCH_CANDIDATES if path.exists()),
    RESEARCH_CANDIDATES[0],
)

OUT = RESEARCH / "FINAL-STATISTICS"

MAIN_DIRS = {
    "B0": RESEARCH / "B0-DAY06-FINAL",
    "MM": RESEARCH / "MM-FINAL",
    "2RH": RESEARCH / "2RH-FINAL",
    "CARBLE": RESEARCH / "CARBLE-V1-FINAL-150",
}

PREF_FAILURE_FILE = (
    RESEARCH
    / "CARBLE-PREFAILURE-FINAL-COMPARISON"
    / "prefailure_protocol_comparison.csv"
)

FULL_DIR = RESEARCH / "CARBLE-FULL-TRANSITION-COMPARISON"
FULL_FILE = FULL_DIR / "full_carble_transition_comparison.csv"
FULL_AUDIT_FILE = FULL_DIR / "full_carble_transition_audit.csv"
FULL_EVENT_FILE = FULL_DIR / "full_carble_transition_events.csv"
FULL_RESOURCE_FILE = FULL_DIR / "full_transition_resource_summary.csv"
FULL_RELAY_FILE = FULL_DIR / "full_transition_relay_burden.csv"

EXPECTED_PROTOCOLS = ["B0", "MM", "2RH", "CARBLE"]
EXPECTED_MAIN_SCENARIOS = ["S01", "S02", "S03", "S04", "S05"]
EXPECTED_PREF_CONDITIONS = ["PF_A_M1", "PF_B1_M2", "PF_B2_M3", "PF_C_LOW"]
EXPECTED_SEEDS = set(range(1, 31))

CORE_METRICS = [
    "pdr",
    "conditionalMeanLatency",
    "physicalAttempts",
    "retransmissions",
    "attemptsPerDelivered",
    "retransmissionsPerDelivered",
]

RESOURCE_METRICS = [
    "attemptsPerDelivered",
    "retransmissionsPerDelivered",
    "totalRelayAttempts",
    "totalRelayForwards",
    "maxRelayAttemptShare",
    "maxRelayForwardShare",
    "maxMeanRelayAttemptRatio",
    "jainRelayAttemptFairness",
    "jainRelayForwardFairness",
]


# ---------------------------------------------------------------------
# Basic utilities
# ---------------------------------------------------------------------

def _read(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"Required research file not found:\n  {path}")
    return pd.read_csv(path)


def _first_existing(columns: Iterable[str], aliases: Sequence[str]) -> str | None:
    cols = set(columns)
    for alias in aliases:
        if alias in cols:
            return alias
    return None


def _required_column(
    df: pd.DataFrame,
    aliases: Sequence[str],
    context: str,
) -> str:
    found = _first_existing(df.columns, aliases)
    if found is None:
        raise ValueError(
            f"{context}: none of the expected columns exists: {list(aliases)}\n"
            f"Actual columns: {list(df.columns)}"
        )
    return found


def _optional_numeric(
    df: pd.DataFrame,
    aliases: Sequence[str],
) -> pd.Series:
    found = _first_existing(df.columns, aliases)
    if found is None:
        return pd.Series(np.nan, index=df.index, dtype=float)
    return pd.to_numeric(df[found], errors="coerce")


def _normalize_protocol(value: object) -> str:
    text = str(value).strip().upper()
    mapping = {
        "TWO_RH": "2RH",
        "TWO-RH": "2RH",
        "2RH": "2RH",
        "B0": "B0",
        "MM": "MM",
        "CARBLE": "CARBLE",
    }
    return mapping.get(text, text)


def _normalize_scenario(value: object) -> str:
    text = str(value).upper()
    for s in EXPECTED_MAIN_SCENARIOS:
        if s in text:
            return s
    return text


def _safe_div(num: pd.Series, den: pd.Series) -> pd.Series:
    num = pd.to_numeric(num, errors="coerce")
    den = pd.to_numeric(den, errors="coerce")
    out = num / den.replace(0, np.nan)
    return out.astype(float)


def _sample_sd(values: np.ndarray) -> float:
    if len(values) < 2:
        return float("nan")
    return float(np.std(values, ddof=1))


def _iqr(values: np.ndarray) -> float:
    return float(np.percentile(values, 75) - np.percentile(values, 25))


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

    try:
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
    except Exception:
        # Conservative reproducible percentile fallback if BCa degenerates.
        rng = np.random.default_rng(seed)
        samples = rng.choice(values, size=(n_resamples, len(values)), replace=True)
        means = samples.mean(axis=1)
        alpha = (1.0 - confidence_level) / 2.0
        return (
            float(np.quantile(means, alpha)),
            float(np.quantile(means, 1.0 - alpha)),
        )


def _rank_biserial(differences: np.ndarray) -> float:
    """Matched-pairs rank-biserial correlation: (W+ - W-) / (W+ + W-)."""
    d = np.asarray(differences, dtype=float)
    d = d[np.isfinite(d)]
    d = d[d != 0]

    if len(d) == 0:
        return 0.0

    ranks = stats.rankdata(np.abs(d), method="average")
    w_plus = float(ranks[d > 0].sum())
    w_minus = float(ranks[d < 0].sum())
    denom = w_plus + w_minus
    if denom == 0:
        return 0.0
    return (w_plus - w_minus) / denom


def _wilcoxon_p(differences: np.ndarray) -> float:
    d = np.asarray(differences, dtype=float)
    d = d[np.isfinite(d)]

    if len(d) == 0 or np.all(d == 0):
        return 1.0

    try:
        return float(
            stats.wilcoxon(
                d,
                zero_method="wilcox",
                correction=False,
                alternative="two-sided",
                method="auto",
            ).pvalue
        )
    except ValueError:
        return 1.0


def _holm_adjust(p_values: Sequence[float]) -> list[float]:
    """Holm step-down FWER adjustment."""
    p = np.asarray(p_values, dtype=float)
    m = len(p)
    if m == 0:
        return []

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


def _jain(values: Sequence[float]) -> float:
    x = np.asarray(values, dtype=float)
    x = x[np.isfinite(x)]
    if len(x) == 0:
        return float("nan")
    denom = len(x) * np.square(x).sum()
    if denom == 0:
        return 1.0
    return float(np.square(x.sum()) / denom)


# ---------------------------------------------------------------------
# Main S01-S05 normalization
# ---------------------------------------------------------------------

def load_main_runs() -> pd.DataFrame:
    frames: list[pd.DataFrame] = []

    for expected_protocol, directory in MAIN_DIRS.items():
        runs = _read(directory / "runs.csv")
        summary = _read(directory / "run_summary.csv")

        run_id_runs = _required_column(runs, ["runId"], f"{directory}/runs.csv")
        run_id_summary = _required_column(
            summary, ["runId"], f"{directory}/run_summary.csv"
        )

        merged = runs.merge(
            summary,
            left_on=run_id_runs,
            right_on=run_id_summary,
            how="inner",
            suffixes=("_config", "_summary"),
            validate="one_to_one",
        )

        if len(merged) != len(runs) or len(merged) != len(summary):
            raise ValueError(
                f"{directory}: runs.csv and run_summary.csv do not reconcile "
                f"one-to-one ({len(runs)} config rows, {len(summary)} summaries, "
                f"{len(merged)} merged)."
            )

        scenario_col = _required_column(
            merged, ["scenarioId", "scenarioId_config"], f"{directory}"
        )
        seed_col = _required_column(
            merged, ["seed", "seed_config"], f"{directory}"
        )

        delivered_col = _required_column(
            merged,
            ["deliveredPackets", "delivered"],
            f"{directory} run summary",
        )
        generated_col = _required_column(
            merged,
            ["generatedPackets", "generated"],
            f"{directory} run summary",
        )
        dropped_col = _required_column(
            merged,
            ["droppedPackets", "dropped"],
            f"{directory} run summary",
        )
        pdr_col = _required_column(
            merged,
            ["packetDeliveryRatio", "pdr"],
            f"{directory} run summary",
        )
        latency_col = _required_column(
            merged,
            ["meanLatency", "averageLatency", "avgLatency"],
            f"{directory} run summary",
        )
        attempts_col = _required_column(
            merged,
            ["physicalAttempts"],
            f"{directory} run summary",
        )
        retrans_col = _required_column(
            merged,
            ["retransmissions"],
            f"{directory} run summary",
        )

        out = pd.DataFrame(
            {
                "experimentFamily": "MAIN",
                "scenario": merged[scenario_col].map(_normalize_scenario),
                "protocol": expected_protocol,
                "seed": pd.to_numeric(merged[seed_col], errors="raise").astype(int),
                "runId": merged[run_id_runs].astype(str),
                "generated": pd.to_numeric(merged[generated_col], errors="raise"),
                "delivered": pd.to_numeric(merged[delivered_col], errors="raise"),
                "dropped": pd.to_numeric(merged[dropped_col], errors="raise"),
                "pdr": pd.to_numeric(merged[pdr_col], errors="raise"),
                "conditionalMeanLatency": pd.to_numeric(
                    merged[latency_col], errors="coerce"
                ),
                "physicalAttempts": pd.to_numeric(
                    merged[attempts_col], errors="raise"
                ),
                "retransmissions": pd.to_numeric(
                    merged[retrans_col], errors="raise"
                ),
            }
        )

        out["attemptsPerDelivered"] = _safe_div(
            out["physicalAttempts"], out["delivered"]
        )
        out["retransmissionsPerDelivered"] = _safe_div(
            out["retransmissions"], out["delivered"]
        )

        # Optional secondary telemetry retained for descriptive use.
        out["routeCalculations"] = _optional_numeric(
            merged, ["routeCalculations", "routingCalculations"]
        )
        out["cacheInvalidations"] = _optional_numeric(
            merged, ["cacheInvalidations"]
        )
        out["queueFullDrops"] = _optional_numeric(
            merged, ["queueFullDrops", "queueDrops"]
        )

        frames.append(out)

    result = pd.concat(frames, ignore_index=True)
    validate_paired_design(
        result,
        group_col="scenario",
        expected_groups=EXPECTED_MAIN_SCENARIOS,
        family_name="MAIN S01-S05",
    )
    return result


def load_main_resource_runs(main_runs: pd.DataFrame) -> pd.DataFrame:
    """Derive run-level relay burden from frozen resource_samples.csv files."""
    frames: list[pd.DataFrame] = []

    for protocol, directory in MAIN_DIRS.items():
        resources = _read(directory / "resource_samples.csv")
        config = _read(directory / "runs.csv")

        node_count_col = _required_column(
            config, ["nodeCount"], f"{directory}/runs.csv"
        )
        scenario_col = _required_column(
            config, ["scenarioId"], f"{directory}/runs.csv"
        )
        seed_col = _required_column(config, ["seed"], f"{directory}/runs.csv")

        meta = config[["runId", scenario_col, seed_col, node_count_col]].copy()
        meta.columns = ["runId", "scenario", "seed", "nodeCount"]
        meta["scenario"] = meta["scenario"].map(_normalize_scenario)
        meta["seed"] = pd.to_numeric(meta["seed"], errors="raise").astype(int)
        meta["nodeCount"] = pd.to_numeric(
            meta["nodeCount"], errors="raise"
        ).astype(int)

        res = resources.merge(meta, on="runId", how="inner", validate="many_to_one")
        if len(res) != len(resources):
            raise ValueError(f"{directory}: resource rows could not all map to runs.")

        for run_id, g in res.groupby("runId", sort=False):
            scenario = str(g["scenario"].iloc[0])
            seed = int(g["seed"].iloc[0])
            node_count = int(g["nodeCount"].iloc[0])
            destination = f"N{node_count - 1}"

            relays = g[
                (g["nodeId"].astype(str) != "N0")
                & (g["nodeId"].astype(str) != destination)
            ].copy()

            relay_attempts = pd.to_numeric(
                relays["physicalAttempts"], errors="raise"
            ).to_numpy(dtype=float)
            relay_forwards = pd.to_numeric(
                relays["packetsForwarded"], errors="raise"
            ).to_numpy(dtype=float)

            total_relay_attempts = float(relay_attempts.sum())
            total_relay_forwards = float(relay_forwards.sum())

            run_match = main_runs[
                (main_runs["protocol"] == protocol)
                & (main_runs["scenario"] == scenario)
                & (main_runs["seed"] == seed)
            ]
            if len(run_match) != 1:
                raise ValueError(
                    f"Could not uniquely map resource run {run_id} "
                    f"to main run ({protocol}, {scenario}, {seed})."
                )
            rr = run_match.iloc[0]

            frames.append(
                pd.DataFrame(
                    [
                        {
                            "experimentFamily": "MAIN_RESOURCE",
                            "scenario": scenario,
                            "protocol": protocol,
                            "seed": seed,
                            "runId": run_id,
                            "delivered": float(rr["delivered"]),
                            "pdr": float(rr["pdr"]),
                            "physicalAttempts": float(rr["physicalAttempts"]),
                            "retransmissions": float(rr["retransmissions"]),
                            "attemptsPerDelivered": float(
                                rr["attemptsPerDelivered"]
                            ),
                            "retransmissionsPerDelivered": float(
                                rr["retransmissionsPerDelivered"]
                            ),
                            "totalRelayAttempts": total_relay_attempts,
                            "totalRelayForwards": total_relay_forwards,
                            "maxRelayAttemptShare": (
                                float(relay_attempts.max() / total_relay_attempts)
                                if total_relay_attempts > 0
                                else 0.0
                            ),
                            "maxRelayForwardShare": (
                                float(relay_forwards.max() / total_relay_forwards)
                                if total_relay_forwards > 0
                                else 0.0
                            ),
                            "maxMeanRelayAttemptRatio": (
                                float(relay_attempts.max() / relay_attempts.mean())
                                if len(relay_attempts) > 0
                                and relay_attempts.mean() > 0
                                else 0.0
                            ),
                            "jainRelayAttemptFairness": _jain(relay_attempts),
                            "jainRelayForwardFairness": _jain(relay_forwards),
                        }
                    ]
                )
            )

    result = pd.concat(frames, ignore_index=True)
    validate_paired_design(
        result,
        group_col="scenario",
        expected_groups=EXPECTED_MAIN_SCENARIOS,
        family_name="MAIN RESOURCE S01-S05",
    )
    return result


# ---------------------------------------------------------------------
# Pre-failure normalization
# ---------------------------------------------------------------------

def load_prefailure_runs() -> pd.DataFrame:
    df = _read(PREF_FAILURE_FILE)

    protocol = df["protocol"].map(_normalize_protocol)
    condition = df["condition"].astype(str)

    out = pd.DataFrame(
        {
            "experimentFamily": "PREF_FAILURE",
            "scenario": condition,
            "protocol": protocol,
            "seed": pd.to_numeric(df["seed"], errors="raise").astype(int),
            "runId": df["runId"].astype(str),
            "generated": pd.to_numeric(df["generated"], errors="raise"),
            "delivered": pd.to_numeric(df["delivered"], errors="raise"),
            "dropped": pd.to_numeric(df["dropped"], errors="raise"),
            "pdr": pd.to_numeric(df["pdr"], errors="raise"),
            "conditionalMeanLatency": pd.to_numeric(
                df["meanLatency"], errors="coerce"
            ),
            "physicalAttempts": pd.to_numeric(
                df["physicalAttempts"], errors="raise"
            ),
            "retransmissions": pd.to_numeric(
                df["retransmissions"], errors="raise"
            ),
        }
    )

    out["attemptsPerDelivered"] = _safe_div(
        out["physicalAttempts"], out["delivered"]
    )
    out["retransmissionsPerDelivered"] = _safe_div(
        out["retransmissions"], out["delivered"]
    )

    validate_paired_design(
        out,
        group_col="scenario",
        expected_groups=EXPECTED_PREF_CONDITIONS,
        family_name="PRE-FAILURE",
    )
    return out


# ---------------------------------------------------------------------
# Full-transition normalization
# ---------------------------------------------------------------------

def load_full_transition_runs() -> pd.DataFrame:
    df = _read(FULL_FILE)

    out = pd.DataFrame(
        {
            "experimentFamily": "FULL_TRANSITION",
            "scenario": "FULL_HIGH_M1_M2_M3_LOW",
            "protocol": df["protocol"].map(_normalize_protocol),
            "seed": pd.to_numeric(df["seed"], errors="raise").astype(int),
            "runId": df["runId"].astype(str),
            "generated": pd.to_numeric(df["generated"], errors="raise"),
            "delivered": pd.to_numeric(df["delivered"], errors="raise"),
            "dropped": pd.to_numeric(df["dropped"], errors="raise"),
            "pdr": pd.to_numeric(df["pdr"], errors="raise"),
            "conditionalMeanLatency": pd.to_numeric(
                df["meanLatency"], errors="coerce"
            ),
            "physicalAttempts": pd.to_numeric(
                df["physicalAttempts"], errors="raise"
            ),
            "retransmissions": pd.to_numeric(
                df["retransmissions"], errors="raise"
            ),
        }
    )
    out["attemptsPerDelivered"] = _safe_div(
        out["physicalAttempts"], out["delivered"]
    )
    out["retransmissionsPerDelivered"] = _safe_div(
        out["retransmissions"], out["delivered"]
    )

    validate_paired_design(
        out,
        group_col="scenario",
        expected_groups=["FULL_HIGH_M1_M2_M3_LOW"],
        family_name="FULL TRANSITION",
    )
    return out


def load_full_resource_runs() -> pd.DataFrame:
    df = _read(FULL_RESOURCE_FILE)
    df = df.copy()
    df["protocol"] = df["protocol"].map(_normalize_protocol)
    df["scenario"] = "FULL_HIGH_M1_M2_M3_LOW"
    df["experimentFamily"] = "FULL_RESOURCE"
    df["seed"] = pd.to_numeric(df["seed"], errors="raise").astype(int)

    needed = [
        "attemptsPerDelivered",
        "retransmissionsPerDelivered",
        "totalRelayAttempts",
        "totalRelayForwards",
        "maxRelayAttemptShare",
        "maxRelayForwardShare",
        "maxMeanRelayAttemptRatio",
        "jainRelayAttemptFairness",
        "jainRelayForwardFairness",
    ]
    for c in needed:
        df[c] = pd.to_numeric(df[c], errors="coerce")

    validate_paired_design(
        df,
        group_col="scenario",
        expected_groups=["FULL_HIGH_M1_M2_M3_LOW"],
        family_name="FULL RESOURCE",
    )
    return df


# ---------------------------------------------------------------------
# Design validation
# ---------------------------------------------------------------------

def validate_paired_design(
    df: pd.DataFrame,
    group_col: str,
    expected_groups: Sequence[str],
    family_name: str,
) -> None:
    dup = df.duplicated([group_col, "protocol", "seed"])
    if dup.any():
        examples = df.loc[dup, [group_col, "protocol", "seed"]].head()
        raise ValueError(
            f"{family_name}: duplicate group/protocol/seed rows detected:\n{examples}"
        )

    missing_groups = set(expected_groups) - set(df[group_col].unique())
    if missing_groups:
        raise ValueError(
            f"{family_name}: missing expected groups {sorted(missing_groups)}. "
            "The final statistics must not proceed on an incomplete experiment set."
        )

    for group in expected_groups:
        subset = df[df[group_col] == group]
        protocols = set(subset["protocol"])
        missing_protocols = set(EXPECTED_PROTOCOLS) - protocols
        if missing_protocols:
            raise ValueError(
                f"{family_name} {group}: missing protocols "
                f"{sorted(missing_protocols)}."
            )

        for protocol in EXPECTED_PROTOCOLS:
            seeds = set(
                subset.loc[subset["protocol"] == protocol, "seed"]
                .astype(int)
                .tolist()
            )
            if seeds != EXPECTED_SEEDS:
                raise ValueError(
                    f"{family_name} {group} {protocol}: expected seeds 1..30, "
                    f"found {sorted(seeds)}."
                )


# ---------------------------------------------------------------------
# Descriptive + inferential statistics
# ---------------------------------------------------------------------

def descriptive_table(
    df: pd.DataFrame,
    group_col: str,
    metrics: Sequence[str],
) -> pd.DataFrame:
    rows: list[dict] = []

    for (group, protocol), g in df.groupby([group_col, "protocol"], sort=True):
        for metric in metrics:
            if metric not in g.columns:
                continue
            values = pd.to_numeric(g[metric], errors="coerce").to_numpy(float)
            values = values[np.isfinite(values)]
            if len(values) == 0:
                continue

            ci_low, ci_high = _bootstrap_mean_ci(values)
            rows.append(
                {
                    "scenario": group,
                    "protocol": protocol,
                    "metric": metric,
                    "n": len(values),
                    "mean": float(np.mean(values)),
                    "median": float(np.median(values)),
                    "sd": _sample_sd(values),
                    "iqr": _iqr(values),
                    "min": float(np.min(values)),
                    "max": float(np.max(values)),
                    "meanCi95Low_BCa": ci_low,
                    "meanCi95High_BCa": ci_high,
                }
            )

    return pd.DataFrame(rows)


def friedman_table(
    df: pd.DataFrame,
    group_col: str,
    metrics: Sequence[str],
) -> pd.DataFrame:
    rows: list[dict] = []

    for group, g in df.groupby(group_col, sort=True):
        for metric in metrics:
            if metric not in g.columns:
                continue

            pivot = g.pivot(index="seed", columns="protocol", values=metric)
            if not all(p in pivot.columns for p in EXPECTED_PROTOCOLS):
                continue
            pivot = pivot[EXPECTED_PROTOCOLS].dropna()

            if len(pivot) < 2:
                continue

            arrays = [pivot[p].to_numpy(float) for p in EXPECTED_PROTOCOLS]

            # Exact structural tie:
            # every protocol has exactly the same value for every paired seed.
            #
            # In this case the Friedman statistic is undefined/degenerate.
            # We preserve this explicitly instead of reporting a fabricated p=1.
            exact_tie = all(
                np.array_equal(arrays[0], candidate)
                for candidate in arrays[1:]
            )

            if exact_tie:
                stat = np.nan
                p = np.nan
                status = "EXACT_TIE_NOT_APPLICABLE"
            else:
                try:
                    stat, p = stats.friedmanchisquare(*arrays)
                    stat = float(stat)
                    p = float(p)

                    # scipy can return nan for other fully degenerate tied data.
                    if not np.isfinite(stat) or not np.isfinite(p):
                        stat = np.nan
                        p = np.nan
                        status = "DEGENERATE_TIES_NOT_APPLICABLE"
                    else:
                        status = "COMPUTED"
                except ValueError:
                    stat = np.nan
                    p = np.nan
                    status = "DEGENERATE_TIES_NOT_APPLICABLE"

            rows.append(
                {
                    "scenario": group,
                    "metric": metric,
                    "nPairedSeeds": len(pivot),
                    "friedmanChiSquare": stat,
                    "df": len(EXPECTED_PROTOCOLS) - 1,
                    "pValue": p,
                    "testStatus": status,
                }
            )

    return pd.DataFrame(rows)


def pairwise_carble_table(
    df: pd.DataFrame,
    group_col: str,
    metrics: Sequence[str],
    family_label: str,
) -> pd.DataFrame:
    rows: list[dict] = []

    for group, g in df.groupby(group_col, sort=True):
        carble = g[g["protocol"] == "CARBLE"].set_index("seed")

        for baseline in ["B0", "MM", "2RH"]:
            base = g[g["protocol"] == baseline].set_index("seed")
            common = sorted(set(carble.index).intersection(base.index))

            if set(common) != EXPECTED_SEEDS:
                raise ValueError(
                    f"{family_label} {group}: CARBLE vs {baseline} "
                    "does not preserve all 30 paired seeds."
                )

            for metric in metrics:
                if metric not in g.columns:
                    continue

                c = pd.to_numeric(
                    carble.loc[common, metric], errors="coerce"
                ).to_numpy(float)
                b = pd.to_numeric(
                    base.loc[common, metric], errors="coerce"
                ).to_numpy(float)
                finite = np.isfinite(c) & np.isfinite(b)
                c = c[finite]
                b = b[finite]
                d = c - b

                if len(d) == 0:
                    continue

                ci_low, ci_high = _bootstrap_mean_ci(
                    d, seed=20260902 + len(rows)
                )
                p = _wilcoxon_p(d)
                rbc = _rank_biserial(d)

                baseline_mean = float(np.mean(b))
                mean_diff = float(np.mean(d))
                relative = (
                    mean_diff / baseline_mean * 100.0
                    if baseline_mean != 0
                    else float("nan")
                )

                rows.append(
                    {
                        "family": family_label,
                        "scenario": group,
                        "metric": metric,
                        "comparison": f"CARBLE_vs_{baseline}",
                        "baseline": baseline,
                        "nPairedSeeds": len(d),
                        "carbleMean": float(np.mean(c)),
                        "baselineMean": baseline_mean,
                        "meanPairedDifference_CARBLEminusBaseline": mean_diff,
                        "differenceCi95Low_BCa": ci_low,
                        "differenceCi95High_BCa": ci_high,
                        "pdrDifferencePercentagePoints": (
                            mean_diff * 100.0 if metric == "pdr" else np.nan
                        ),
                        "relativeMeanChangePercent": relative,
                        "wilcoxonP": p,
                        "rankBiserial": rbc,
                    }
                )

    result = pd.DataFrame(rows)

    # Holm correction: one FWER family per experiment-family × outcome.
    if not result.empty:
        result["wilcoxonP_Holm"] = np.nan
        for metric, idx in result.groupby("metric").groups.items():
            pvals = result.loc[idx, "wilcoxonP"].tolist()
            result.loc[idx, "wilcoxonP_Holm"] = _holm_adjust(pvals)

    return result


# ---------------------------------------------------------------------
# CARBLE mechanism/lifecycle statistics
# ---------------------------------------------------------------------

def carble_lifecycle_summary() -> tuple[pd.DataFrame, pd.DataFrame]:
    audit = _read(FULL_AUDIT_FILE)

    bool_cols = ["hasAllStages", "strictFirstEntryOrder"]
    for c in bool_cols:
        if c in audit.columns:
            audit[c] = (
                audit[c]
                .astype(str)
                .str.strip()
                .str.lower()
                .map({"true": True, "false": False})
            )

    summary_rows: list[dict] = []

    for c in bool_cols:
        if c in audit.columns:
            valid = audit[c].dropna()
            summary_rows.append(
                {
                    "mechanismMetric": c,
                    "n": len(valid),
                    "meanOrRate": float(valid.astype(float).mean()),
                    "median": np.nan,
                    "sd": np.nan,
                    "iqr": np.nan,
                    "min": np.nan,
                    "max": np.nan,
                    "ci95Low_BCa": np.nan,
                    "ci95High_BCa": np.nan,
                }
            )

    numeric_metrics = [
        "firstM1Time",
        "firstM2Time",
        "firstM3Time",
        "firstLowTime",
        "m1ToM2",
        "m2ToM3",
        "m3ToLow",
        "m1ToLowLeadTime",
        "minCurrentHopConfidence",
        "minRouteConfidence",
        "HIGH",
        "M1",
        "M2",
        "M3",
        "LOW",
        "carry",
        "probe",
        "probeSuccess",
        "probeFailure",
        "fallbackDrops",
    ]

    for c in numeric_metrics:
        if c not in audit.columns:
            continue
        values = pd.to_numeric(audit[c], errors="coerce").dropna().to_numpy(float)
        if len(values) == 0:
            continue
        lo, hi = _bootstrap_mean_ci(values)
        summary_rows.append(
            {
                "mechanismMetric": c,
                "n": len(values),
                "meanOrRate": float(np.mean(values)),
                "median": float(np.median(values)),
                "sd": _sample_sd(values),
                "iqr": _iqr(values),
                "min": float(np.min(values)),
                "max": float(np.max(values)),
                "ci95Low_BCa": lo,
                "ci95High_BCa": hi,
            }
        )

    events = _read(FULL_EVENT_FILE)
    event_summary_rows: list[dict] = []

    # Event counts/shares are mechanism evidence, not protocol-comparison outcomes.
    if {"seed", "regime"}.issubset(events.columns):
        carble_events = events.copy()
        total = len(carble_events)
        for regime, count in carble_events["regime"].value_counts().items():
            event_summary_rows.append(
                {
                    "dimension": "regime",
                    "value": regime,
                    "count": int(count),
                    "share": float(count / total) if total else np.nan,
                }
            )

    if "mediumStage" in events.columns:
        stage = events["mediumStage"].dropna()
        stage = stage[stage.astype(str).str.strip() != ""]
        total_stage = len(stage)
        for value, count in stage.value_counts().items():
            event_summary_rows.append(
                {
                    "dimension": "mediumStage",
                    "value": value,
                    "count": int(count),
                    "share": float(count / total_stage) if total_stage else np.nan,
                }
            )

    return pd.DataFrame(summary_rows), pd.DataFrame(event_summary_rows)


# ---------------------------------------------------------------------
# Output / manifest
# ---------------------------------------------------------------------

def write_analysis_plan() -> None:
    text = """CARBLE SIMULATION STATISTICAL ANALYSIS PLAN
===========================================

STATISTICAL UNIT
One independent simulation replication/seed. Individual packets are NOT
treated as independent samples.

EXPERIMENT FAMILIES
1. MAIN: S01-S05 standard benchmark comparison, B0/MM/2RH/CARBLE, 30 seeds each.
2. PRE-FAILURE: PF-A(M1), PF-B1(M2), PF-B2(M3), PF-C(LOW), 30 paired seeds each.
3. FULL TRANSITION: HIGH->M1->M2->M3->LOW lifecycle, 30 paired seeds.
4. RESOURCE/BURDEN: resource proxies from frozen per-node transmission evidence.

PRIMARY ENDPOINT
Packet Delivery Ratio (PDR).

SECONDARY ENDPOINTS
Conditional mean end-to-end latency among DELIVERED packets;
physical attempts; retransmissions; attempts per delivered packet;
retransmissions per delivered packet.

MECHANISM ENDPOINTS (CARBLE ONLY)
First M1/M2/M3/LOW entry; M1->LOW lead time; Qcurrent/Qroute minima;
stage/regime decision shares; backup/carry/probe/fallback behavior.
These explain mechanism and are not used as if B0/MM/2RH had equivalent states.

DESCRIPTIVE STATISTICS
n, mean, median, sample SD, IQR, min, max, 95% BCa bootstrap CI for the run-level mean.

INFERENTIAL DESIGN
Paired by seed.
Friedman omnibus test across B0/MM/2RH/CARBLE for each scenario and outcome.
Planned paired contrasts: CARBLE vs B0, CARBLE vs MM, CARBLE vs 2RH.
Paired Wilcoxon signed-rank test.
Matched-pairs rank-biserial correlation as effect size.
95% BCa bootstrap CI for the paired mean difference.
Holm family-wise error correction within each experiment family and metric.

PDR REPORTING
Report CARBLE-baseline differences primarily in percentage points.
Relative percentage change may be shown secondarily.

LATENCY INTERPRETATION
meanLatency is conditional on packets that were successfully delivered.
It must be interpreted jointly with PDR because protocols that drop difficult
packets can appear artificially faster.

RESOURCE INTERPRETATION
physical attempts/retransmissions are simulation resource/energy proxies.
They are NOT joules, battery percentage, or measured BLE power.
Jain indices and max-share metrics describe relay-burden distribution/
concentration. Equal relay burden is not assumed to be inherently optimal in
structurally asymmetric topologies.

CALIBRATION EXCLUSION
PF calibration runs used to identify frozen conditions are excluded from
inferential validation statistics. Only the frozen 30-seed comparison runs
enter final inference.

CLAIM BOUNDARY
The simulations can support a favorable reliability-latency-resource trade-off
and staged escalation under the tested conditions. They do not establish
global mathematical minimum-cost optimality or measured real-device energy
savings.
"""
    (OUT / "statistical_analysis_plan.txt").write_text(text, encoding="utf-8")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    print("Loading and validating frozen research datasets...")
    print(f"Research root: {RESEARCH.resolve()}")

    main_runs = load_main_runs()
    pref_runs = load_prefailure_runs()
    full_runs = load_full_transition_runs()
    main_resource = load_main_resource_runs(main_runs)
    full_resource = load_full_resource_runs()

    # Canonical normalized evidence used by final analysis.
    all_runs = pd.concat([main_runs, pref_runs, full_runs], ignore_index=True)
    all_runs.to_csv(OUT / "research_run_level.csv", index=False)

    main_resource.to_csv(OUT / "research_main_resource_run_level.csv", index=False)
    full_resource.to_csv(OUT / "research_full_resource_run_level.csv", index=False)

    # Descriptives.
    descriptive_table(
        main_runs, "scenario", CORE_METRICS
    ).to_csv(OUT / "main_descriptive.csv", index=False)

    descriptive_table(
        pref_runs, "scenario", CORE_METRICS
    ).to_csv(OUT / "prefailure_descriptive.csv", index=False)

    descriptive_table(
        full_runs, "scenario", CORE_METRICS
    ).to_csv(OUT / "full_transition_descriptive.csv", index=False)

    descriptive_table(
        main_resource, "scenario", RESOURCE_METRICS
    ).to_csv(OUT / "main_resource_descriptive.csv", index=False)

    descriptive_table(
        full_resource, "scenario", RESOURCE_METRICS
    ).to_csv(OUT / "full_resource_descriptive.csv", index=False)

    # Omnibus.
    friedman_table(
        main_runs, "scenario", CORE_METRICS
    ).to_csv(OUT / "main_friedman.csv", index=False)

    friedman_table(
        pref_runs, "scenario", CORE_METRICS
    ).to_csv(OUT / "prefailure_friedman.csv", index=False)

    friedman_table(
        full_runs, "scenario", CORE_METRICS
    ).to_csv(OUT / "full_transition_friedman.csv", index=False)

    # Planned paired contrasts.
    pairwise_carble_table(
        main_runs,
        "scenario",
        CORE_METRICS,
        "MAIN",
    ).to_csv(OUT / "main_pairwise_carble.csv", index=False)

    pairwise_carble_table(
        pref_runs,
        "scenario",
        CORE_METRICS,
        "PREF_FAILURE",
    ).to_csv(OUT / "prefailure_pairwise_carble.csv", index=False)

    pairwise_carble_table(
        full_runs,
        "scenario",
        CORE_METRICS,
        "FULL_TRANSITION",
    ).to_csv(OUT / "full_transition_pairwise_carble.csv", index=False)

    pairwise_carble_table(
        main_resource,
        "scenario",
        RESOURCE_METRICS,
        "MAIN_RESOURCE",
    ).to_csv(OUT / "main_resource_pairwise_carble.csv", index=False)

    pairwise_carble_table(
        full_resource,
        "scenario",
        RESOURCE_METRICS,
        "FULL_RESOURCE",
    ).to_csv(OUT / "full_resource_pairwise_carble.csv", index=False)

    mechanism, event_shares = carble_lifecycle_summary()
    mechanism.to_csv(OUT / "carble_lifecycle_summary.csv", index=False)
    event_shares.to_csv(OUT / "carble_event_shares.csv", index=False)

    # Preserve raw Step-2/3 evidence in normalized final-analysis folder by
    # writing path manifest only; raw files stay untouched in their source folder.
    write_analysis_plan()

    print()
    print("==============================================================")
    print("FINAL SIMULATION STATISTICS COMPLETE")
    print(f"Output: {OUT.resolve()}")
    print(f"Normalized run-level rows: {len(all_runs)}")
    print("Expected: MAIN 600 + PRE-FAILURE 480 + FULL 120 = 1200")
    print("Calibration runs are not included in inferential datasets.")
    print("==============================================================")


if __name__ == "__main__":
    main()
