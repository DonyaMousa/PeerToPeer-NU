#!/usr/bin/env python3
"""
CARBLE final figures — standard/basic Matplotlib UI
===================================================

Purpose
-------
Restore the earlier simple figure style:
- standalone figures
- normal Matplotlib colors
- visible titles
- standard grid
- simple markers/lines
- no compressed publication multi-panels
- no hatching-heavy layout

Research corrections retained:
1) Controlled PF scenarios are NOT connected by lines.
2) Qcurrent and Qroute are plotted separately so route-confidence is not
   incorrectly shown against M2/M3/LOW thresholds.
3) New frozen evidence (phase decision shares, threshold robustness,
   computational scalability) is included as separate, simple figures.

Run:
    python analysis/final_simulation_figures.py

Output:
    app/build/research/FINAL-FIGURES/
"""

from __future__ import annotations

from pathlib import Path
import csv
import numpy as np
import pandas as pd

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path.cwd()

RESEARCH_CANDIDATES = [
    ROOT / "app" / "build" / "research",
    ROOT / "build" / "research",
]

RESEARCH = next(
    (p for p in RESEARCH_CANDIDATES if p.exists()),
    RESEARCH_CANDIDATES[0],
)

STATS = RESEARCH / "FINAL-STATISTICS"
FULL = RESEARCH / "CARBLE-FULL-TRANSITION-COMPARISON"
OUT = RESEARCH / "FINAL-FIGURES"

PROTOCOL_ORDER = ["B0", "MM", "2RH", "CARBLE"]
SCALABILITY_PROTOCOL_ORDER = ["B0", "MM", "TWO_RH", "CARBLE"]

MAIN_ORDER = ["S01", "S02", "S03", "S04", "S05"]
PREF_ORDER = ["PF_A_M1", "PF_B1_M2", "PF_B2_M3", "PF_C_LOW"]

DISPLAY_PROTOCOL = {
    "B0": "B0",
    "MM": "MM",
    "2RH": "2RH",
    "TWO_RH": "2RH",
    "CARBLE": "CARBLE",
}

DISPLAY_PREF = {
    "PF_A_M1": "PF-A / M1",
    "PF_B1_M2": "PF-B1 / M2",
    "PF_B2_M3": "PF-B2 / M3",
    "PF_C_LOW": "PF-C / LOW",
}


def read_csv(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"Required source not found: {path}")
    return pd.read_csv(path)


def save_figure(fig: plt.Figure, stem: str) -> tuple[Path, Path]:
    OUT.mkdir(parents=True, exist_ok=True)

    png = OUT / f"{stem}.png"
    pdf = OUT / f"{stem}.pdf"

    fig.savefig(
        png,
        dpi=300,
        bbox_inches="tight",
    )
    fig.savefig(
        pdf,
        bbox_inches="tight",
    )

    plt.close(fig)
    return png, pdf


def prepare_metric(
    df: pd.DataFrame,
    metric: str,
    scenario_order: list[str] | None = None,
    protocol_order: list[str] | None = None,
) -> pd.DataFrame:

    sub = df[df["metric"] == metric].copy()

    if scenario_order is not None and "scenario" in sub.columns:
        sub["scenario"] = pd.Categorical(
            sub["scenario"],
            categories=scenario_order,
            ordered=True,
        )

    if protocol_order is not None and "protocol" in sub.columns:
        sub["protocol"] = pd.Categorical(
            sub["protocol"],
            categories=protocol_order,
            ordered=True,
        )

    sort_columns = []
    if "scenario" in sub.columns:
        sort_columns.append("scenario")
    if "protocol" in sub.columns:
        sort_columns.append("protocol")

    if sort_columns:
        sub = sub.sort_values(sort_columns)

    return sub


def mean_ci_arrays(
    rows: pd.DataFrame,
    percent: bool = False,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:

    means = rows["mean"].to_numpy(dtype=float, copy=True)
    lows = rows["meanCi95Low_BCa"].to_numpy(dtype=float, copy=True)
    highs = rows["meanCi95High_BCa"].to_numpy(dtype=float, copy=True)

    if percent:
        means *= 100.0
        lows *= 100.0
        highs *= 100.0

    return means, means - lows, highs - means


# =============================================================================
# FIG 01 — STANDARD PDR
# =============================================================================

def fig01_main_pdr(main_desc: pd.DataFrame):

    fig, ax = plt.subplots(figsize=(10, 6))

    sub = prepare_metric(
        main_desc,
        "pdr",
        scenario_order=MAIN_ORDER,
        protocol_order=PROTOCOL_ORDER,
    )

    x = np.arange(len(MAIN_ORDER))
    offsets = np.linspace(-0.24, 0.24, len(PROTOCOL_ORDER))

    for offset, protocol in zip(offsets, PROTOCOL_ORDER):
        rows = (
            sub[sub["protocol"] == protocol]
            .set_index("scenario")
            .loc[MAIN_ORDER]
            .reset_index()
        )

        means, lower, upper = mean_ci_arrays(rows, percent=True)

        ax.errorbar(
            x + offset,
            means,
            yerr=np.vstack([lower, upper]),
            marker="o",
            linestyle="none",
            capsize=4,
            label=protocol,
        )

    ax.set_title("Standard scenarios: packet delivery ratio", fontsize=18)
    ax.set_xlabel("Scenario", fontsize=13)
    ax.set_ylabel("Packet delivery ratio (%)", fontsize=13)
    ax.set_xticks(x)
    ax.set_xticklabels(MAIN_ORDER)
    ax.grid(True, alpha=0.25)
    ax.legend(ncol=4)

    return save_figure(fig, "fig01_main_pdr")


# =============================================================================
# FIG 02 — STANDARD LATENCY
# =============================================================================

def fig02_main_latency(main_desc: pd.DataFrame):

    fig, ax = plt.subplots(figsize=(10, 6))

    sub = prepare_metric(
        main_desc,
        "conditionalMeanLatency",
        scenario_order=MAIN_ORDER,
        protocol_order=PROTOCOL_ORDER,
    )

    x = np.arange(len(MAIN_ORDER))
    offsets = np.linspace(-0.24, 0.24, len(PROTOCOL_ORDER))

    for offset, protocol in zip(offsets, PROTOCOL_ORDER):
        rows = (
            sub[sub["protocol"] == protocol]
            .set_index("scenario")
            .loc[MAIN_ORDER]
            .reset_index()
        )

        means, lower, upper = mean_ci_arrays(rows)

        ax.errorbar(
            x + offset,
            means,
            yerr=np.vstack([lower, upper]),
            marker="o",
            linestyle="none",
            capsize=4,
            label=protocol,
        )

    ax.set_title("Standard scenarios: conditional latency", fontsize=18)
    ax.set_xlabel("Scenario", fontsize=13)
    ax.set_ylabel("Conditional mean latency (time units)", fontsize=13)
    ax.set_xticks(x)
    ax.set_xticklabels(MAIN_ORDER)
    ax.grid(True, alpha=0.25)
    ax.legend(ncol=4)

    return save_figure(fig, "fig02_main_latency")


# =============================================================================
# FIG 03 — CONTROLLED PF PDR
# =============================================================================

def fig03_prefailure_pdr(pref_desc: pd.DataFrame):

    fig, ax = plt.subplots(figsize=(10, 6))

    sub = prepare_metric(
        pref_desc,
        "pdr",
        scenario_order=PREF_ORDER,
        protocol_order=PROTOCOL_ORDER,
    )

    x = np.arange(len(PREF_ORDER))
    offsets = np.linspace(-0.24, 0.24, len(PROTOCOL_ORDER))

    for offset, protocol in zip(offsets, PROTOCOL_ORDER):
        rows = (
            sub[sub["protocol"] == protocol]
            .set_index("scenario")
            .loc[PREF_ORDER]
            .reset_index()
        )

        means, lower, upper = mean_ci_arrays(rows, percent=True)

        # Important research correction:
        # separate experimental conditions -> points only, NO connecting line.
        ax.errorbar(
            x + offset,
            means,
            yerr=np.vstack([lower, upper]),
            marker="o",
            linestyle="none",
            capsize=4,
            label=protocol,
        )

    ax.set_title(
        "Controlled pre-failure conditions: packet delivery ratio",
        fontsize=18,
    )
    ax.set_xlabel("Controlled condition", fontsize=13)
    ax.set_ylabel("Packet delivery ratio (%)", fontsize=13)
    ax.set_xticks(x)
    ax.set_xticklabels([DISPLAY_PREF[s] for s in PREF_ORDER])
    ax.grid(True, alpha=0.25)
    ax.legend(ncol=4)

    return save_figure(fig, "fig03_prefailure_pdr")


# =============================================================================
# FIG 04 — FULL TRADE-OFF
# =============================================================================

def fig04_full_transition_tradeoff(
    full_desc: pd.DataFrame,
    resource_desc: pd.DataFrame,
):

    pdr = (
        prepare_metric(full_desc, "pdr", protocol_order=PROTOCOL_ORDER)
        .set_index("protocol")
    )

    attempts = (
        prepare_metric(
            resource_desc,
            "attemptsPerDelivered",
            protocol_order=PROTOCOL_ORDER,
        )
        .set_index("protocol")
    )

    fig, ax = plt.subplots(figsize=(8, 7))

    annotation_offsets = {
        "B0": (10, 8),
        "MM": (10, 8),
        "2RH": (10, 8),
        "CARBLE": (10, 8),
    }

    for protocol in PROTOCOL_ORDER:
        x = float(attempts.loc[protocol, "mean"])
        y = float(pdr.loc[protocol, "mean"]) * 100.0

        x_low = float(attempts.loc[protocol, "meanCi95Low_BCa"])
        x_high = float(attempts.loc[protocol, "meanCi95High_BCa"])

        y_low = float(pdr.loc[protocol, "meanCi95Low_BCa"]) * 100.0
        y_high = float(pdr.loc[protocol, "meanCi95High_BCa"]) * 100.0

        ax.errorbar(
            x,
            y,
            xerr=np.array([[x - x_low], [x_high - x]]),
            yerr=np.array([[y - y_low], [y_high - y]]),
            marker="o",
            linestyle="none",
            capsize=4,
        )

        ax.annotate(
            protocol,
            xy=(x, y),
            xytext=annotation_offsets[protocol],
            textcoords="offset points",
            fontsize=12,
        )

    ax.set_title(
        "Full transition: reliability–resource trade-off",
        fontsize=18,
    )
    ax.set_xlabel(
        "Physical attempts per delivered packet",
        fontsize=13,
    )
    ax.set_ylabel("Packet delivery ratio (%)", fontsize=13)
    ax.grid(True, alpha=0.25)

    return save_figure(fig, "fig04_full_transition_tradeoff")


# =============================================================================
# FIG 05 — FIRST ENTRY TIMELINE
# =============================================================================

def lifecycle_row(
    lifecycle: pd.DataFrame,
    metric: str,
) -> pd.Series:

    row = lifecycle[
        lifecycle["mechanismMetric"] == metric
    ]

    if len(row) != 1:
        raise ValueError(f"Expected one lifecycle row for {metric}")

    return row.iloc[0]


def fig05_carble_first_entry_timeline(
    lifecycle: pd.DataFrame,
):

    stages = [
        ("firstM1Time", "M1"),
        ("firstM2Time", "M2"),
        ("firstM3Time", "M3"),
        ("firstLowTime", "LOW"),
    ]

    means = []
    lows = []
    highs = []
    labels = []

    for metric, label in stages:
        row = lifecycle_row(lifecycle, metric)
        means.append(float(row["meanOrRate"]))
        lows.append(float(row["ci95Low_BCa"]))
        highs.append(float(row["ci95High_BCa"]))
        labels.append(label)

    means = np.asarray(means)
    lows = np.asarray(lows)
    highs = np.asarray(highs)

    y = np.arange(len(labels))

    fig, ax = plt.subplots(figsize=(10, 6))

    ax.errorbar(
        means,
        y,
        xerr=np.vstack([means - lows, highs - means]),
        marker="o",
        linestyle="none",
        capsize=4,
    )

    ax.axvline(
        600,
        linestyle="--",
        alpha=0.7,
        label="Instability injection t=600",
    )

    ax.axvline(
        750,
        linestyle=":",
        alpha=0.8,
        label="Additional injection t=750",
    )

    ax.set_yticks(y)
    ax.set_yticklabels(labels)
    ax.invert_yaxis()

    ax.set_title(
        "CARBLE lifecycle: first entry into each adaptive stage",
        fontsize=18,
    )
    ax.set_xlabel(
        "First-entry time (simulation time units)",
        fontsize=13,
    )
    ax.set_ylabel("Adaptive stage", fontsize=13)
    ax.grid(True, axis="x", alpha=0.25)
    ax.legend()

    return save_figure(fig, "fig05_carble_first_entry_timeline")


# =============================================================================
# CONFIDENCE HELPERS
# =============================================================================

def binned_confidence(
    events: pd.DataFrame,
    bin_width: int = 25,
) -> pd.DataFrame:

    e = events.copy()

    e["eventTime"] = pd.to_numeric(
        e["eventTime"],
        errors="coerce",
    )

    e["currentHopConfidence"] = pd.to_numeric(
        e["currentHopConfidence"],
        errors="coerce",
    )

    e["routeConfidence"] = pd.to_numeric(
        e["routeConfidence"],
        errors="coerce",
    )

    e = e.dropna(subset=["eventTime"])

    e["timeBin"] = (
        e["eventTime"] // bin_width
    ) * bin_width

    per_seed = (
        e.groupby(["seed", "timeBin"], as_index=False)
        .agg(
            qCurrent=("currentHopConfidence", "median"),
            qRoute=("routeConfidence", "median"),
        )
    )

    return (
        per_seed.groupby("timeBin", as_index=False)
        .agg(
            qCurrentMedian=("qCurrent", "median"),
            qCurrentQ25=(
                "qCurrent",
                lambda x: np.nanpercentile(x, 25),
            ),
            qCurrentQ75=(
                "qCurrent",
                lambda x: np.nanpercentile(x, 75),
            ),
            qRouteMedian=("qRoute", "median"),
            qRouteQ25=(
                "qRoute",
                lambda x: np.nanpercentile(x, 25),
            ),
            qRouteQ75=(
                "qRoute",
                lambda x: np.nanpercentile(x, 75),
            ),
        )
    )


# =============================================================================
# FIG 06 — QCURRENT
# =============================================================================

def fig06_carble_current_confidence(
    events: pd.DataFrame,
):

    agg = binned_confidence(events)

    fig, ax = plt.subplots(figsize=(11, 6))

    ax.fill_between(
        agg["timeBin"],
        agg["qCurrentQ25"],
        agg["qCurrentQ75"],
        alpha=0.18,
        label="Interquartile range",
    )

    ax.plot(
        agg["timeBin"],
        agg["qCurrentMedian"],
        marker="o",
        label="Median Qcurrent",
    )

    thresholds = [
        (0.75, "0.75  HIGH / M1"),
        (0.65, "0.65  M1 / M2"),
        (0.55, "0.55  M2 / M3"),
        (0.45, "0.45  M3 / LOW"),
    ]

    for threshold, label in thresholds:
        ax.axhline(
            threshold,
            linestyle="--",
            alpha=0.65,
        )
        ax.text(
            agg["timeBin"].max() + 10,
            threshold,
            label,
            va="center",
            fontsize=10,
        )

    ax.axvline(600, linestyle=":", alpha=0.8)
    ax.axvline(750, linestyle=":", alpha=0.8)

    ax.set_title(
        "CARBLE full transition: current-hop confidence trajectory",
        fontsize=18,
    )
    ax.set_xlabel("Simulation time", fontsize=13)
    ax.set_ylabel("Current-hop confidence", fontsize=13)
    ax.set_ylim(0.35, 1.01)
    ax.grid(True, alpha=0.2)
    ax.legend()

    return save_figure(fig, "fig06_carble_current_confidence")


# =============================================================================
# FIG 07 — QROUTE
# =============================================================================

def fig07_carble_route_confidence(
    events: pd.DataFrame,
):

    agg = binned_confidence(events)

    fig, ax = plt.subplots(figsize=(11, 6))

    ax.fill_between(
        agg["timeBin"],
        agg["qRouteQ25"],
        agg["qRouteQ75"],
        alpha=0.18,
        label="Interquartile range",
    )

    ax.plot(
        agg["timeBin"],
        agg["qRouteMedian"],
        marker="s",
        label="Median Qroute",
    )

    # Research correction:
    # Qroute can only generate the downstream-warning M1 condition.
    ax.axhline(
        0.75,
        linestyle="--",
        alpha=0.75,
        label="0.75 downstream-warning threshold",
    )

    ax.axvline(600, linestyle=":", alpha=0.8)
    ax.axvline(750, linestyle=":", alpha=0.8)

    ax.set_title(
        "CARBLE full transition: remaining-route confidence trajectory",
        fontsize=18,
    )
    ax.set_xlabel("Simulation time", fontsize=13)
    ax.set_ylabel("Remaining-route confidence", fontsize=13)
    ax.set_ylim(0.35, 1.01)
    ax.grid(True, alpha=0.2)
    ax.legend()

    return save_figure(fig, "fig07_carble_route_confidence")


# =============================================================================
# FIG 08 — PHASE DECISION SHARES
# =============================================================================

def fig08_phase_decision_shares(
    phase_summary: pd.DataFrame,
):

    stage_metrics = [
        ("highShare", "HIGH"),
        ("m1Share", "M1"),
        ("m2Share", "M2"),
        ("m3Share", "M3"),
        ("lowShare", "LOW"),
    ]

    phases = np.arange(1, 8)
    success_probs = [0.90, 0.75, 0.60, 0.45, 0.30, 0.15, 0.05]

    fig, ax = plt.subplots(figsize=(11, 6))

    bottom = np.zeros(7)

    for metric, label in stage_metrics:
        values = []

        for phase in phases:
            row = phase_summary[
                (phase_summary["phaseIndex"] == phase)
                & (phase_summary["metric"] == metric)
            ]

            if len(row) != 1:
                raise ValueError(
                    f"Expected phase {phase}, metric {metric}"
                )

            values.append(
                float(row.iloc[0]["mean"]) * 100.0
            )

        values = np.asarray(values)

        ax.bar(
            phases,
            values,
            bottom=bottom,
            label=label,
        )

        bottom += values

    ax.set_title(
        "CARBLE full transition: adaptive decision shares by degradation phase",
        fontsize=18,
    )
    ax.set_xlabel(
        "Phase / link success probability",
        fontsize=13,
    )
    ax.set_ylabel("Decision share (%)", fontsize=13)

    ax.set_xticks(phases)
    ax.set_xticklabels(
        [
            f"P{i}\n{p:.2f}"
            for i, p in zip(phases, success_probs)
        ]
    )

    ax.set_ylim(0, 100)
    ax.grid(True, axis="y", alpha=0.2)
    ax.legend(ncol=5)

    return save_figure(fig, "fig08_phase_decision_shares")


# =============================================================================
# FIG 09 — FULL ATTEMPTS / DELIVERED
# =============================================================================

def protocol_metric_plot(
    df: pd.DataFrame,
    metric: str,
    title: str,
    ylabel: str,
    stem: str,
):

    sub = (
        prepare_metric(
            df,
            metric,
            protocol_order=PROTOCOL_ORDER,
        )
        .set_index("protocol")
        .loc[PROTOCOL_ORDER]
    )

    x = np.arange(len(PROTOCOL_ORDER))

    means, lower, upper = mean_ci_arrays(sub.reset_index())

    fig, ax = plt.subplots(figsize=(8, 6))

    ax.errorbar(
        x,
        means,
        yerr=np.vstack([lower, upper]),
        marker="o",
        linestyle="none",
        capsize=4,
    )

    ax.set_xticks(x)
    ax.set_xticklabels(PROTOCOL_ORDER)

    ax.set_title(title, fontsize=18)
    ax.set_xlabel("Protocol", fontsize=13)
    ax.set_ylabel(ylabel, fontsize=13)
    ax.grid(True, axis="y", alpha=0.25)

    return save_figure(fig, stem)


def fig09_full_attempts_per_delivered(
    resource_desc: pd.DataFrame,
):
    return protocol_metric_plot(
        resource_desc,
        metric="attemptsPerDelivered",
        title="Full transition: physical attempts per delivered packet",
        ylabel="Physical attempts per delivered packet",
        stem="fig09_full_attempts_per_delivered",
    )


# =============================================================================
# FIG 10 — RELAY ATTEMPT CONCENTRATION
# =============================================================================

def fig10_full_relay_attempt_concentration(
    resource_desc: pd.DataFrame,
):
    return protocol_metric_plot(
        resource_desc,
        metric="maxRelayAttemptShare",
        title="Full transition: maximum relay-attempt share",
        ylabel="Maximum relay-attempt share",
        stem="fig10_full_relay_attempt_concentration",
    )


# =============================================================================
# FIG 11 — JAIN ATTEMPT INDEX
# =============================================================================

def fig11_full_relay_attempt_jain(
    resource_desc: pd.DataFrame,
):
    return protocol_metric_plot(
        resource_desc,
        metric="jainRelayAttemptFairness",
        title="Full transition: Jain relay-attempt index",
        ylabel="Jain relay-attempt index",
        stem="fig11_full_relay_attempt_jain",
    )


# =============================================================================
# FIG 12 — THRESHOLD ROBUSTNESS
# =============================================================================

def fig12_threshold_robustness(
    threshold_desc: pd.DataFrame,
):

    config_order = ["EARLY", "NOMINAL", "LATE"]

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(12, 5.5),
    )

    for ax, metric, ylabel, percent in [
        (
            axes[0],
            "pdr",
            "Packet delivery ratio (%)",
            True,
        ),
        (
            axes[1],
            "attemptsPerDelivered",
            "Physical attempts per delivered packet",
            False,
        ),
    ]:

        rows = (
            threshold_desc[
                threshold_desc["metric"] == metric
            ]
            .set_index("thresholdConfig")
            .loc[config_order]
            .reset_index()
        )

        means, lower, upper = mean_ci_arrays(
            rows,
            percent=percent,
        )

        x = np.arange(len(config_order))

        ax.errorbar(
            x,
            means,
            yerr=np.vstack([lower, upper]),
            marker="o",
            linestyle="none",
            capsize=4,
        )

        ax.set_xticks(x)
        ax.set_xticklabels(
            ["Early", "Nominal", "Late"]
        )
        ax.set_xlabel("Threshold configuration")
        ax.set_ylabel(ylabel)
        ax.grid(True, alpha=0.25)

    axes[0].set_title("Reliability")
    axes[1].set_title("Resource effort")

    fig.suptitle(
        "CARBLE threshold robustness",
        fontsize=18,
    )

    fig.tight_layout()

    return save_figure(fig, "fig12_threshold_robustness")


# =============================================================================
# FIG 13 — COMPUTATIONAL SCALABILITY
# =============================================================================

def fig13_computational_scalability(
    scalability_desc: pd.DataFrame,
):

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(13, 5.5),
        sharey=True,
    )

    for ax, topology in zip(
        axes,
        ["SPARSE", "MODERATE"],
    ):

        sub = scalability_desc[
            (scalability_desc["topology"] == topology)
            & (
                scalability_desc["metric"]
                == "medianLatencyUs"
            )
        ]

        for protocol in SCALABILITY_PROTOCOL_ORDER:

            rows = (
                sub[
                    sub["protocol"] == protocol
                ]
                .sort_values("nodeCount")
            )

            x = rows["nodeCount"].to_numpy(
                dtype=float,
                copy=True,
            )

            means = rows["mean"].to_numpy(
                dtype=float,
                copy=True,
            )

            lows = rows["meanCi95Low_BCa"].to_numpy(
                dtype=float,
                copy=True,
            )

            highs = rows["meanCi95High_BCa"].to_numpy(
                dtype=float,
                copy=True,
            )

            ax.errorbar(
                x,
                means,
                yerr=np.vstack(
                    [
                        means - lows,
                        highs - means,
                    ]
                ),
                marker="o",
                linestyle="-",
                capsize=3,
                label=DISPLAY_PROTOCOL[protocol],
            )

        ax.set_title(
            "Sparse topology"
            if topology == "SPARSE"
            else "Moderate topology"
        )
        ax.set_xlabel("Number of nodes")
        ax.grid(True, alpha=0.25)
        ax.legend()

    axes[0].set_ylabel(
        "Median routing/controller decision latency (µs)"
    )

    fig.suptitle(
        "Routing/controller computational scalability",
        fontsize=18,
    )

    fig.tight_layout()

    return save_figure(fig, "fig13_computational_scalability")


# =============================================================================
# MANIFEST
# =============================================================================

def write_manifest(
    generated: list[tuple[str, Path, Path]],
):

    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    path = OUT / "figure_manifest.csv"

    with path.open(
        "w",
        newline="",
        encoding="utf-8",
    ) as f:

        writer = csv.writer(f)

        writer.writerow(
            [
                "figure",
                "png",
                "pdf",
            ]
        )

        for name, png, pdf in generated:
            writer.writerow(
                [
                    name,
                    png.name,
                    pdf.name,
                ]
            )

    return path


# =============================================================================
# MAIN
# =============================================================================

def main():

    print(
        f"Research root: "
        f"{RESEARCH.resolve()}"
    )

    print(
        f"Final figures: "
        f"{OUT.resolve()}"
    )

    main_desc = read_csv(
        STATS / "main_descriptive.csv"
    )

    pref_desc = read_csv(
        STATS / "prefailure_descriptive.csv"
    )

    full_desc = read_csv(
        STATS / "full_transition_descriptive.csv"
    )

    lifecycle = read_csv(
        STATS / "carble_lifecycle_summary.csv"
    )

    resource_desc = read_csv(
        STATS / "full_resource_descriptive.csv"
    )

    events = read_csv(
        FULL / "full_carble_transition_events.csv"
    )

    phase_summary = read_csv(
        STATS / "carble_phase_decision_summary.csv"
    )

    threshold_desc = read_csv(
        STATS / "threshold_robustness_descriptive.csv"
    )

    scalability_desc = read_csv(
        STATS / "scalability_descriptive.csv"
    )

    generated = []

    functions = [
        ("fig01_main_pdr", lambda: fig01_main_pdr(main_desc)),
        ("fig02_main_latency", lambda: fig02_main_latency(main_desc)),
        ("fig03_prefailure_pdr", lambda: fig03_prefailure_pdr(pref_desc)),
        (
            "fig04_full_transition_tradeoff",
            lambda: fig04_full_transition_tradeoff(
                full_desc,
                resource_desc,
            ),
        ),
        (
            "fig05_carble_first_entry_timeline",
            lambda: fig05_carble_first_entry_timeline(
                lifecycle
            ),
        ),
        (
            "fig06_carble_current_confidence",
            lambda: fig06_carble_current_confidence(
                events
            ),
        ),
        (
            "fig07_carble_route_confidence",
            lambda: fig07_carble_route_confidence(
                events
            ),
        ),
        (
            "fig08_phase_decision_shares",
            lambda: fig08_phase_decision_shares(
                phase_summary
            ),
        ),
        (
            "fig09_full_attempts_per_delivered",
            lambda: fig09_full_attempts_per_delivered(
                resource_desc
            ),
        ),
        (
            "fig10_full_relay_attempt_concentration",
            lambda: fig10_full_relay_attempt_concentration(
                resource_desc
            ),
        ),
        (
            "fig11_full_relay_attempt_jain",
            lambda: fig11_full_relay_attempt_jain(
                resource_desc
            ),
        ),
        (
            "fig12_threshold_robustness",
            lambda: fig12_threshold_robustness(
                threshold_desc
            ),
        ),
        (
            "fig13_computational_scalability",
            lambda: fig13_computational_scalability(
                scalability_desc
            ),
        ),
    ]

    for name, function in functions:
        png, pdf = function()
        generated.append(
            (
                name,
                png,
                pdf,
            )
        )
        print(
            f"Generated: {name}"
        )

    manifest = write_manifest(
        generated
    )

    print()
    print(
        "============================================================"
    )
    print(
        "STANDARD FIGURE SET COMPLETE"
    )
    print(
        f"Figures generated: "
        f"{len(generated)}"
    )
    print(
        f"Manifest: "
        f"{manifest.resolve()}"
    )
    print(
        "Each figure exists as PNG (300 dpi) and vector PDF."
    )
    print(
        "============================================================"
    )


if __name__ == "__main__":
    main()
