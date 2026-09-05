#!/usr/bin/env python3
"""
CARBLE publication figure set
=============================

Purpose
-------
Replace exploratory standalone plots with a restrained, publication-oriented
figure system built only from the frozen simulation/statistical evidence.

This script DOES NOT rerun simulations or modify any evidence.

Run from project root:
    python analysis/publication_simulation_figures.py

Reads:
    app/build/research/FINAL-STATISTICS/
    app/build/research/CARBLE-FULL-TRANSITION-COMPARISON/

Writes:
    app/build/research/PUBLICATION-FIGURES/

Output:
    7 figure groups, each as vector PDF + 600-dpi PNG
    publication_figure_manifest.csv

Design principles
-----------------
- no large dashboard-style titles inside plots
- compact final-paper dimensions
- consistent typography
- marker + line-style redundancy, not color alone
- minimal chart furniture
- 95% BCa confidence intervals where available
- separate Qcurrent regime thresholds from Qroute downstream warning
- no connecting lines between independent controlled scenarios
- seed/run remains the inferential unit
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
OUT = RESEARCH / "PUBLICATION-FIGURES"

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
    "PF_A_M1": "PF-A\nM1",
    "PF_B1_M2": "PF-B1\nM2",
    "PF_B2_M3": "PF-B2\nM3",
    "PF_C_LOW": "PF-C\nLOW",
}

PROTOCOL_MARKERS = {
    "B0": "o",
    "MM": "s",
    "2RH": "^",
    "TWO_RH": "^",
    "CARBLE": "D",
}

PROTOCOL_LINESTYLES = {
    "B0": "-",
    "MM": "--",
    "2RH": "-.",
    "TWO_RH": "-.",
    "CARBLE": ":",
}

PHASE_SUCCESS = {
    1: 0.90,
    2: 0.75,
    3: 0.60,
    4: 0.45,
    5: 0.30,
    6: 0.15,
    7: 0.05,
}


# =============================================================================
# PUBLICATION STYLE
# =============================================================================

def apply_publication_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "sans-serif",
            "font.sans-serif": [
                "Arial",
                "Helvetica",
                "Liberation Sans",
                "DejaVu Sans",
            ],
            "font.size": 8.0,
            "axes.labelsize": 8.0,
            "axes.titlesize": 8.0,
            "xtick.labelsize": 7.2,
            "ytick.labelsize": 7.2,
            "legend.fontsize": 7.0,
            "axes.linewidth": 0.75,
            "lines.linewidth": 1.0,
            "lines.markersize": 4.2,
            "xtick.major.width": 0.7,
            "ytick.major.width": 0.7,
            "xtick.major.size": 3.0,
            "ytick.major.size": 3.0,
            "legend.frameon": False,
            "figure.dpi": 150,
            "savefig.dpi": 600,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )


def clean_axes(ax: plt.Axes) -> None:
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.tick_params(direction="out")
    ax.margins(x=0.04)


def panel_label(ax: plt.Axes, label: str) -> None:
    ax.text(
        -0.13,
        1.04,
        label,
        transform=ax.transAxes,
        ha="left",
        va="bottom",
        fontweight="bold",
        fontsize=8.0,
    )


def _read(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(
            f"Required publication-figure source not found:\n  {path}"
        )
    return pd.read_csv(path)


def _save(fig: plt.Figure, stem: str) -> tuple[Path, Path]:
    OUT.mkdir(parents=True, exist_ok=True)

    png = OUT / f"{stem}.png"
    pdf = OUT / f"{stem}.pdf"

    fig.savefig(
        png,
        dpi=600,
        bbox_inches="tight",
        pad_inches=0.03,
    )
    fig.savefig(
        pdf,
        bbox_inches="tight",
        pad_inches=0.03,
    )

    plt.close(fig)
    return png, pdf


def _metric_rows(
    df: pd.DataFrame,
    metric: str,
    scenario_order: list[str],
) -> pd.DataFrame:
    sub = df[df["metric"] == metric].copy()

    sub["scenario"] = pd.Categorical(
        sub["scenario"],
        categories=scenario_order,
        ordered=True,
    )

    sub["protocol"] = pd.Categorical(
        sub["protocol"],
        categories=PROTOCOL_ORDER,
        ordered=True,
    )

    return sub.sort_values(
        ["scenario", "protocol"]
    )


def _ci_arrays(
    rows: pd.DataFrame,
    percent: bool = False,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:

    means = rows["mean"].to_numpy(dtype=float, copy=True)
    lows = rows["meanCi95Low_BCa"].to_numpy(dtype=float, copy=True)
    highs = rows["meanCi95High_BCa"].to_numpy(dtype=float, copy=True)

    if percent:
        means = means * 100.0
        lows = lows * 100.0
        highs = highs * 100.0

    lower = means - lows
    upper = highs - means

    return means, lower, upper


def _protocol_errorbar_points(
    ax: plt.Axes,
    df: pd.DataFrame,
    metric: str,
    scenario_order: list[str],
    scenario_labels: list[str],
    percent: bool = False,
    show_legend: bool = True,
) -> None:

    sub = _metric_rows(
        df,
        metric,
        scenario_order,
    )

    x = np.arange(
        len(scenario_order),
        dtype=float,
    )

    offsets = np.linspace(
        -0.22,
        0.22,
        len(PROTOCOL_ORDER),
    )

    for offset, protocol in zip(
        offsets,
        PROTOCOL_ORDER,
    ):

        rows = (
            sub[
                sub["protocol"] == protocol
            ]
            .set_index("scenario")
            .loc[scenario_order]
            .reset_index()
        )

        means, lower, upper = _ci_arrays(
            rows,
            percent=percent,
        )

        ax.errorbar(
            x + offset,
            means,
            yerr=np.vstack(
                [lower, upper]
            ),
            fmt=PROTOCOL_MARKERS[protocol],
            linestyle="none",
            capsize=2.2,
            elinewidth=0.8,
            markeredgewidth=0.8,
            label=DISPLAY_PROTOCOL[protocol],
        )

    ax.set_xticks(x)
    ax.set_xticklabels(
        scenario_labels
    )

    if show_legend:
        ax.legend(
            ncol=4,
            loc="best",
            handletextpad=0.45,
            columnspacing=1.0,
        )

    clean_axes(ax)


# =============================================================================
# FIGURE 1 — STANDARD SCENARIOS
# =============================================================================

def figure_standard_performance(
    main_desc: pd.DataFrame,
) -> tuple[Path, Path]:

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(7.16, 2.65),
        constrained_layout=True,
    )

    ax = axes[0]
    _protocol_errorbar_points(
        ax,
        main_desc,
        metric="pdr",
        scenario_order=MAIN_ORDER,
        scenario_labels=MAIN_ORDER,
        percent=True,
        show_legend=False,
    )
    ax.set_ylabel("Packet delivery ratio (%)")
    ax.set_xlabel("Standard scenario")
    panel_label(ax, "(a)")

    ax = axes[1]
    _protocol_errorbar_points(
        ax,
        main_desc,
        metric="conditionalMeanLatency",
        scenario_order=MAIN_ORDER,
        scenario_labels=MAIN_ORDER,
        percent=False,
        show_legend=False,
    )
    ax.set_ylabel("Conditional latency (time units)")
    ax.set_xlabel("Standard scenario")
    panel_label(ax, "(b)")

    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(
        handles,
        labels,
        loc="upper center",
        ncol=4,
        bbox_to_anchor=(0.5, 1.03),
        handletextpad=0.45,
        columnspacing=1.1,
    )

    return _save(
        fig,
        "fig01_standard_performance",
    )


# =============================================================================
# FIGURE 2 — CONTROLLED REGIME VALIDATION
# =============================================================================

def figure_controlled_regime_validation(
    pref_desc: pd.DataFrame,
) -> tuple[Path, Path]:

    fig, ax = plt.subplots(
        figsize=(3.5, 2.75),
        constrained_layout=True,
    )

    _protocol_errorbar_points(
        ax,
        pref_desc,
        metric="pdr",
        scenario_order=PREF_ORDER,
        scenario_labels=[
            DISPLAY_PREF[x]
            for x in PREF_ORDER
        ],
        percent=True,
        show_legend=True,
    )

    ax.set_ylabel("Packet delivery ratio (%)")
    ax.set_xlabel("Controlled condition")

    return _save(
        fig,
        "fig02_controlled_regime_validation",
    )


# =============================================================================
# FIGURE 3 — CARBLE LIFECYCLE MECHANISM
# =============================================================================

def _binned_confidence(
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

    e = e.dropna(
        subset=["eventTime"]
    )

    e["timeBin"] = (
        e["eventTime"] // bin_width
    ) * bin_width

    per_seed = (
        e.groupby(
            ["seed", "timeBin"],
            as_index=False,
        )
        .agg(
            qCurrent=(
                "currentHopConfidence",
                "median",
            ),
            qRoute=(
                "routeConfidence",
                "median",
            ),
        )
    )

    return (
        per_seed.groupby(
            "timeBin",
            as_index=False,
        )
        .agg(
            qCurrentMedian=(
                "qCurrent",
                "median",
            ),
            qCurrentQ25=(
                "qCurrent",
                lambda x:
                    np.nanpercentile(
                        x,
                        25,
                    ),
            ),
            qCurrentQ75=(
                "qCurrent",
                lambda x:
                    np.nanpercentile(
                        x,
                        75,
                    ),
            ),
            qRouteMedian=(
                "qRoute",
                "median",
            ),
            qRouteQ25=(
                "qRoute",
                lambda x:
                    np.nanpercentile(
                        x,
                        25,
                    ),
            ),
            qRouteQ75=(
                "qRoute",
                lambda x:
                    np.nanpercentile(
                        x,
                        75,
                    ),
            ),
            seeds=(
                "seed",
                "nunique",
            ),
        )
    )


def _phase_share_matrix(
    phase_summary: pd.DataFrame,
) -> tuple[
    np.ndarray,
    list[str],
    list[str],
]:

    stages = [
        ("highShare", "HIGH"),
        ("m1Share", "M1"),
        ("m2Share", "M2"),
        ("m3Share", "M3"),
        ("lowShare", "LOW"),
    ]

    matrix = []

    for phase in range(1, 8):

        row_values = []

        for metric, _ in stages:

            hit = phase_summary[
                (phase_summary["phaseIndex"] == phase)
                & (phase_summary["metric"] == metric)
            ]

            if len(hit) != 1:
                raise ValueError(
                    f"Expected one phase-summary row for "
                    f"phase={phase}, metric={metric}."
                )

            row_values.append(
                float(
                    hit.iloc[0]["mean"]
                )
            )

        matrix.append(row_values)

    labels = [
        f"P{phase}\n{PHASE_SUCCESS[phase]:.2f}"
        for phase in range(1, 8)
    ]

    stage_labels = [
        label
        for _, label in stages
    ]

    return (
        np.asarray(
            matrix,
            dtype=float,
        ),
        labels,
        stage_labels,
    )


def figure_lifecycle_mechanism(
    events: pd.DataFrame,
    lifecycle: pd.DataFrame,
    phase_summary: pd.DataFrame,
) -> tuple[Path, Path]:

    agg = _binned_confidence(
        events
    )

    fig, axes = plt.subplots(
        2,
        2,
        figsize=(7.16, 5.75),
        constrained_layout=True,
    )

    # -------------------------------------------------------------------------
    # (a) Qcurrent — all local regime thresholds
    # -------------------------------------------------------------------------

    ax = axes[0, 0]

    ax.fill_between(
        agg["timeBin"],
        agg["qCurrentQ25"],
        agg["qCurrentQ75"],
        alpha=0.14,
        linewidth=0,
    )

    ax.plot(
        agg["timeBin"],
        agg["qCurrentMedian"],
        marker="o",
        markevery=2,
        label=r"$Q_{\mathrm{current}}$",
    )

    for threshold in [
        0.75,
        0.65,
        0.55,
        0.45,
    ]:
        ax.axhline(
            threshold,
            linestyle="--",
            linewidth=0.7,
        )

    ax.axvline(
        600,
        linestyle=":",
        linewidth=0.8,
    )
    ax.axvline(
        750,
        linestyle=":",
        linewidth=0.8,
    )

    ax.set_ylim(
        0.35,
        1.01,
    )
    ax.set_xlabel("Simulation time")
    ax.set_ylabel(r"$Q_{\mathrm{current}}$")
    panel_label(ax, "(a)")
    clean_axes(ax)

    # -------------------------------------------------------------------------
    # (b) Qroute — only downstream-warning threshold
    # -------------------------------------------------------------------------

    ax = axes[0, 1]

    ax.fill_between(
        agg["timeBin"],
        agg["qRouteQ25"],
        agg["qRouteQ75"],
        alpha=0.14,
        linewidth=0,
    )

    ax.plot(
        agg["timeBin"],
        agg["qRouteMedian"],
        marker="s",
        markevery=2,
        label=r"$Q_{\mathrm{route}}$",
    )

    ax.axhline(
        0.75,
        linestyle="--",
        linewidth=0.8,
    )

    ax.axvline(
        600,
        linestyle=":",
        linewidth=0.8,
    )
    ax.axvline(
        750,
        linestyle=":",
        linewidth=0.8,
    )

    ax.set_ylim(
        0.35,
        1.01,
    )
    ax.set_xlabel("Simulation time")
    ax.set_ylabel(r"$Q_{\mathrm{route}}$")
    panel_label(ax, "(b)")
    clean_axes(ax)

    # -------------------------------------------------------------------------
    # (c) Phase-wise decision shares
    # -------------------------------------------------------------------------

    ax = axes[1, 0]

    matrix, phase_labels, stage_labels = (
        _phase_share_matrix(
            phase_summary
        )
    )

    x = np.arange(
        matrix.shape[0],
        dtype=float,
    )

    bottom = np.zeros(
        matrix.shape[0],
        dtype=float,
    )

    hatches = [
        "",
        "///",
        "\\\\\\",
        "...",
        "xx",
    ]

    for stage_index, stage_label in enumerate(
        stage_labels
    ):
        values = (
            matrix[
                :,
                stage_index
            ] * 100.0
        )

        ax.bar(
            x,
            values,
            bottom=bottom,
            width=0.72,
            label=stage_label,
            hatch=hatches[stage_index],
            linewidth=0.55,
            edgecolor="black",
        )

        bottom += values

    ax.set_xticks(x)
    ax.set_xticklabels(
        phase_labels
    )
    ax.set_ylim(
        0,
        100,
    )
    ax.set_ylabel("Decision share (%)")
    ax.set_xlabel(
        "Phase / link success probability"
    )
    ax.legend(
        ncol=5,
        loc="upper center",
        bbox_to_anchor=(0.5, 1.16),
        columnspacing=0.65,
        handlelength=1.1,
        handletextpad=0.3,
    )
    panel_label(ax, "(c)")
    clean_axes(ax)

    # -------------------------------------------------------------------------
    # (d) Mean first-entry timing
    # -------------------------------------------------------------------------

    ax = axes[1, 1]

    wanted = [
        ("firstM1Time", "M1"),
        ("firstM2Time", "M2"),
        ("firstM3Time", "M3"),
        ("firstLowTime", "LOW"),
    ]

    rows = []

    for metric, label in wanted:

        hit = lifecycle[
            lifecycle["mechanismMetric"]
            == metric
        ]

        if len(hit) != 1:
            raise ValueError(
                f"Expected one lifecycle row for {metric}."
            )

        row = hit.iloc[0]

        rows.append(
            (
                label,
                float(
                    row["meanOrRate"]
                ),
                float(
                    row["ci95Low_BCa"]
                ),
                float(
                    row["ci95High_BCa"]
                ),
            )
        )

    y = np.arange(
        len(rows),
        dtype=float,
    )

    means = np.asarray(
        [r[1] for r in rows],
        dtype=float,
    )

    lower = means - np.asarray(
        [r[2] for r in rows],
        dtype=float,
    )

    upper = np.asarray(
        [r[3] for r in rows],
        dtype=float,
    ) - means

    ax.errorbar(
        means,
        y,
        xerr=np.vstack(
            [lower, upper]
        ),
        fmt="o",
        linestyle="none",
        capsize=2.5,
        elinewidth=0.8,
    )

    ax.axvline(
        600,
        linestyle=":",
        linewidth=0.8,
    )
    ax.axvline(
        750,
        linestyle=":",
        linewidth=0.8,
    )

    ax.set_yticks(y)
    ax.set_yticklabels(
        [r[0] for r in rows]
    )
    ax.invert_yaxis()
    ax.set_xlabel("First-entry time")
    ax.set_ylabel("Adaptive stage")
    panel_label(ax, "(d)")
    clean_axes(ax)

    return _save(
        fig,
        "fig03_carble_lifecycle_mechanism",
    )


# =============================================================================
# FIGURE 4 — FULL-TRANSITION TRADE-OFF
# =============================================================================

def _single_metric(
    df: pd.DataFrame,
    metric: str,
) -> pd.DataFrame:

    sub = df[
        df["metric"] == metric
    ].copy()

    sub["protocol"] = pd.Categorical(
        sub["protocol"],
        categories=PROTOCOL_ORDER,
        ordered=True,
    )

    return sub.sort_values(
        "protocol"
    )


def figure_full_transition_tradeoff(
    full_desc: pd.DataFrame,
    resource_desc: pd.DataFrame,
) -> tuple[Path, Path]:

    pdr = (
        _single_metric(
            full_desc,
            "pdr",
        )
        .set_index("protocol")
    )

    attempts = (
        _single_metric(
            resource_desc,
            "attemptsPerDelivered",
        )
        .set_index("protocol")
    )

    fig, ax = plt.subplots(
        figsize=(3.5, 2.8),
        constrained_layout=True,
    )

    annotation_offsets = {
        "B0": (-13, -12),
        "MM": (6, -10),
        "2RH": (6, 5),
        "CARBLE": (-38, 6),
    }

    for protocol in PROTOCOL_ORDER:

        x = float(
            attempts.loc[
                protocol,
                "mean",
            ]
        )

        y = (
            float(
                pdr.loc[
                    protocol,
                    "mean",
                ]
            )
            * 100.0
        )

        xerr = np.array(
            [
                [
                    x
                    - float(
                        attempts.loc[
                            protocol,
                            "meanCi95Low_BCa",
                        ]
                    )
                ],
                [
                    float(
                        attempts.loc[
                            protocol,
                            "meanCi95High_BCa",
                        ]
                    )
                    - x
                ],
            ]
        )

        yerr = np.array(
            [
                [
                    y
                    - float(
                        pdr.loc[
                            protocol,
                            "meanCi95Low_BCa",
                        ]
                    )
                    * 100.0
                ],
                [
                    float(
                        pdr.loc[
                            protocol,
                            "meanCi95High_BCa",
                        ]
                    )
                    * 100.0
                    - y
                ],
            ]
        )

        ax.errorbar(
            x,
            y,
            xerr=xerr,
            yerr=yerr,
            fmt=PROTOCOL_MARKERS[protocol],
            linestyle="none",
            capsize=2.2,
            elinewidth=0.8,
        )

        dx, dy = annotation_offsets[
            protocol
        ]

        ax.annotate(
            DISPLAY_PROTOCOL[
                protocol
            ],
            xy=(x, y),
            xytext=(dx, dy),
            textcoords="offset points",
            fontsize=7.2,
        )

    ax.set_xlabel(
        "Physical attempts per delivered packet"
    )
    ax.set_ylabel(
        "Packet delivery ratio (%)"
    )

    clean_axes(ax)

    return _save(
        fig,
        "fig04_full_transition_tradeoff",
    )


# =============================================================================
# FIGURE 5 — RELAY BURDEN
# =============================================================================

def _protocol_metric_axis(
    ax: plt.Axes,
    resource_desc: pd.DataFrame,
    metric: str,
    percent: bool,
) -> None:

    sub = (
        _single_metric(
            resource_desc,
            metric,
        )
        .set_index("protocol")
        .loc[PROTOCOL_ORDER]
    )

    x = np.arange(
        len(PROTOCOL_ORDER),
        dtype=float,
    )

    means = sub[
        "mean"
    ].to_numpy(
        dtype=float,
        copy=True
    )

    lows = sub[
        "meanCi95Low_BCa"
    ].to_numpy(
        dtype=float,
        copy=True
    )

    highs = sub[
        "meanCi95High_BCa"
    ].to_numpy(
        dtype=float,
        copy=True
    )

    if percent:
        means *= 100.0
        lows *= 100.0
        highs *= 100.0

    for i, protocol in enumerate(
        PROTOCOL_ORDER
    ):

        ax.errorbar(
            x[i],
            means[i],
            yerr=np.array(
                [
                    [
                        means[i]
                        - lows[i]
                    ],
                    [
                        highs[i]
                        - means[i]
                    ],
                ]
            ),
            fmt=PROTOCOL_MARKERS[
                protocol
            ],
            linestyle="none",
            capsize=2.2,
            elinewidth=0.8,
        )

    ax.set_xticks(x)
    ax.set_xticklabels(
        [
            DISPLAY_PROTOCOL[p]
            for p in PROTOCOL_ORDER
        ]
    )

    clean_axes(ax)


def figure_relay_burden(
    resource_desc: pd.DataFrame,
) -> tuple[Path, Path]:

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(7.16, 2.55),
        constrained_layout=True,
    )

    ax = axes[0]
    _protocol_metric_axis(
        ax,
        resource_desc,
        metric="maxRelayAttemptShare",
        percent=True,
    )
    ax.set_ylabel(
        "Maximum relay-attempt share (%)"
    )
    ax.set_xlabel("Protocol")
    panel_label(ax, "(a)")

    ax = axes[1]
    _protocol_metric_axis(
        ax,
        resource_desc,
        metric="jainRelayAttemptFairness",
        percent=False,
    )
    ax.set_ylabel(
        "Jain relay-attempt index"
    )
    ax.set_xlabel("Protocol")
    panel_label(ax, "(b)")

    return _save(
        fig,
        "fig05_relay_burden",
    )


# =============================================================================
# FIGURE 6 — THRESHOLD ROBUSTNESS
# =============================================================================

def figure_threshold_robustness(
    threshold_desc: pd.DataFrame,
) -> tuple[Path, Path]:

    config_order = [
        "EARLY",
        "NOMINAL",
        "LATE",
    ]

    display = {
        "EARLY": "Early",
        "NOMINAL": "Nominal",
        "LATE": "Late",
    }

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(7.16, 2.55),
        constrained_layout=True,
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

        sub = (
            threshold_desc[
                threshold_desc["metric"]
                == metric
            ]
            .set_index(
                "thresholdConfig"
            )
            .loc[
                config_order
            ]
        )

        x = np.arange(
            len(config_order),
            dtype=float,
        )

        means = sub[
            "mean"
        ].to_numpy(dtype=float, copy=True)

        lows = sub[
            "meanCi95Low_BCa"
        ].to_numpy(dtype=float, copy=True)

        highs = sub[
            "meanCi95High_BCa"
        ].to_numpy(dtype=float, copy=True)

        if percent:
            means *= 100.0
            lows *= 100.0
            highs *= 100.0

        ax.errorbar(
            x,
            means,
            yerr=np.vstack(
                [
                    means - lows,
                    highs - means,
                ]
            ),
            fmt="o",
            linestyle="none",
            capsize=2.5,
            elinewidth=0.8,
        )

        ax.set_xticks(x)
        ax.set_xticklabels(
            [
                display[c]
                for c in config_order
            ]
        )
        ax.set_xlabel(
            "Threshold configuration"
        )
        ax.set_ylabel(
            ylabel
        )
        clean_axes(ax)

    panel_label(
        axes[0],
        "(a)",
    )
    panel_label(
        axes[1],
        "(b)",
    )

    return _save(
        fig,
        "fig06_threshold_robustness",
    )


# =============================================================================
# FIGURE 7 — COMPUTATIONAL SCALABILITY
# =============================================================================

def figure_computational_scalability(
    scalability_desc: pd.DataFrame,
) -> tuple[Path, Path]:

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(7.16, 2.8),
        constrained_layout=True,
        sharey=True,
    )

    for ax, topology, panel in [
        (
            axes[0],
            "SPARSE",
            "(a)",
        ),
        (
            axes[1],
            "MODERATE",
            "(b)",
        ),
    ]:

        sub = scalability_desc[
            (scalability_desc["topology"] == topology)
            & (
                scalability_desc["metric"]
                == "medianLatencyUs"
            )
        ].copy()

        for protocol in SCALABILITY_PROTOCOL_ORDER:

            p = (
                sub[
                    sub["protocol"]
                    == protocol
                ]
                .sort_values(
                    "nodeCount"
                )
            )

            x = p[
                "nodeCount"
            ].to_numpy(dtype=float, copy=True)

            mean = p[
                "mean"
            ].to_numpy(dtype=float, copy=True)

            low = p[
                "meanCi95Low_BCa"
            ].to_numpy(dtype=float, copy=True)

            high = p[
                "meanCi95High_BCa"
            ].to_numpy(dtype=float, copy=True)

            ax.errorbar(
                x,
                mean,
                yerr=np.vstack(
                    [
                        mean - low,
                        high - mean,
                    ]
                ),
                marker=PROTOCOL_MARKERS[
                    protocol
                ],
                linestyle=PROTOCOL_LINESTYLES[
                    protocol
                ],
                capsize=1.8,
                elinewidth=0.65,
                label=DISPLAY_PROTOCOL[
                    protocol
                ],
            )

        ax.set_xscale(
            "log"
        )
        ax.set_yscale(
            "log"
        )

        ax.set_xticks(
            [
                10,
                25,
                50,
                100,
                200,
            ]
        )

        ax.set_xticklabels(
            [
                "10",
                "25",
                "50",
                "100",
                "200",
            ]
        )

        ax.set_xlabel(
            "Number of nodes"
        )

        ax.text(
            0.04,
            0.94,
            "Sparse"
            if topology == "SPARSE"
            else "Moderate",
            transform=ax.transAxes,
            ha="left",
            va="top",
            fontsize=7.5,
        )

        panel_label(
            ax,
            panel,
        )

        clean_axes(ax)

    axes[0].set_ylabel(
        "Median decision latency (µs)"
    )

    handles, labels = (
        axes[0]
        .get_legend_handles_labels()
    )

    fig.legend(
        handles,
        labels,
        loc="upper center",
        ncol=4,
        bbox_to_anchor=(0.5, 1.03),
        handletextpad=0.45,
        columnspacing=1.1,
    )

    return _save(
        fig,
        "fig07_computational_scalability",
    )


# =============================================================================
# MANIFEST / CAPTION DRAFTS
# =============================================================================

def write_manifest(
    generated:
    list[
        tuple[
            str,
            str,
            str,
            Path,
            Path,
        ]
    ],
) -> Path:

    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    manifest = (
        OUT
        / "publication_figure_manifest.csv"
    )

    with manifest.open(
        "w",
        encoding="utf-8",
        newline="",
    ) as f:

        writer = csv.writer(f)

        writer.writerow(
            [
                "figure",
                "paperRole",
                "captionDraft",
                "png",
                "pdf",
            ]
        )

        for (
            figure,
            role,
            caption,
            png,
            pdf,
        ) in generated:

            writer.writerow(
                [
                    figure,
                    role,
                    caption,
                    png.name,
                    pdf.name,
                ]
            )

    return manifest


def main() -> None:

    apply_publication_style()

    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    print(
        f"Research root: "
        f"{RESEARCH.resolve()}"
    )

    print(
        f"Publication figures: "
        f"{OUT.resolve()}"
    )

    # Frozen established results
    main_desc = _read(
        STATS
        / "main_descriptive.csv"
    )

    pref_desc = _read(
        STATS
        / "prefailure_descriptive.csv"
    )

    full_desc = _read(
        STATS
        / "full_transition_descriptive.csv"
    )

    lifecycle = _read(
        STATS
        / "carble_lifecycle_summary.csv"
    )

    resource_desc = _read(
        STATS
        / "full_resource_descriptive.csv"
    )

    events = _read(
        FULL
        / "full_carble_transition_events.csv"
    )

    # New frozen evidence additions
    phase_summary = _read(
        STATS
        / "carble_phase_decision_summary.csv"
    )

    threshold_desc = _read(
        STATS
        / "threshold_robustness_descriptive.csv"
    )

    scalability_desc = _read(
        STATS
        / "scalability_descriptive.csv"
    )

    generated = []

    png, pdf = (
        figure_standard_performance(
            main_desc
        )
    )

    generated.append(
        (
            "Fig. 1",
            "RQ1 standard-condition behavior",
            "Performance under the five standard scenarios. "
            "(a) Packet delivery ratio and (b) conditional mean latency among delivered packets. "
            "Points show means across 30 independent seeds; error bars show 95% BCa bootstrap confidence intervals.",
            png,
            pdf,
        )
    )

    png, pdf = (
        figure_controlled_regime_validation(
            pref_desc
        )
    )

    generated.append(
        (
            "Fig. 2",
            "RQ2-RQ4 controlled M1/M2/M3/LOW validation",
            "Packet delivery ratio under the four controlled regime-validation conditions. "
            "The conditions are independent experiments and are therefore shown without connecting lines. "
            "Points show means across 30 paired seeds; error bars show 95% BCa bootstrap confidence intervals.",
            png,
            pdf,
        )
    )

    png, pdf = (
        figure_lifecycle_mechanism(
            events =
                events,
            lifecycle =
                lifecycle,
            phase_summary =
                phase_summary,
        )
    )

    generated.append(
        (
            "Fig. 3",
            "RQ2/RQ5 CARBLE mechanism and staged escalation",
            "CARBLE behavior during the full-transition experiment. "
            "(a) Current-hop confidence with local regime thresholds at 0.75, 0.65, 0.55, and 0.45; "
            "(b) remaining-route confidence with the 0.75 downstream-warning threshold; "
            "(c) phase-wise controller decision shares; and "
            "(d) mean first-entry times into M1, M2, M3, and LOW. "
            "Confidence curves show the cross-seed median of within-seed time-bin medians; shaded regions show interquartile ranges. "
            "Vertical dotted lines mark the staged instability-evidence injections at simulation times 600 and 750.",
            png,
            pdf,
        )
    )

    png, pdf = (
        figure_full_transition_tradeoff(
            full_desc =
                full_desc,
            resource_desc =
                resource_desc,
        )
    )

    generated.append(
        (
            "Fig. 4",
            "RQ5 reliability-resource trade-off",
            "Reliability-resource operating points in the full-transition experiment. "
            "Horizontal error bars show 95% BCa confidence intervals for physical attempts per delivered packet; "
            "vertical error bars show 95% BCa confidence intervals for packet delivery ratio. "
            "Physical attempts are a simulation resource proxy and are not measured energy consumption.",
            png,
            pdf,
        )
    )

    png, pdf = (
        figure_relay_burden(
            resource_desc
        )
    )

    generated.append(
        (
            "Fig. 5",
            "Secondary sustainability/relay-burden analysis",
            "Relay-attempt burden during the full-transition experiment. "
            "(a) Maximum relay share of relay attempts and (b) Jain index for relay-attempt distribution. "
            "These metrics describe burden concentration/distribution in the structurally asymmetric topology and should not be interpreted as measured energy or as a normative equal-load optimum.",
            png,
            pdf,
        )
    )

    png, pdf = (
        figure_threshold_robustness(
            threshold_desc
        )
    )

    generated.append(
        (
            "Fig. 6",
            "Threshold robustness",
            "Sensitivity of the full-transition operating point to pre-specified threshold perturbations. "
            "(a) Packet delivery ratio and (b) physical attempts per delivered packet for earlier, nominal, and later escalation thresholds. "
            "The nominal thresholds remain the CARBLE-v1.0 configuration; the alternatives are robustness perturbations rather than tuned replacements.",
            png,
            pdf,
        )
    )

    png, pdf = (
        figure_computational_scalability(
            scalability_desc
        )
    )

    generated.append(
        (
            "Fig. 7",
            "Computational scalability",
            "JVM-side routing/controller decision latency as graph size increases for (a) sparse and (b) moderately connected graphs. "
            "Each point is the mean across 30 graph seeds of the within-seed median over 500 measured decisions; error bars show 95% BCa confidence intervals. "
            "Both axes are logarithmic. The benchmark measures synchronous computation on the benchmark host, not BLE transmission latency or Android-device energy.",
            png,
            pdf,
        )
    )

    manifest = write_manifest(
        generated
    )

    print()
    print(
        "============================================================"
    )
    print(
        "PUBLICATION FIGURE SET COMPLETE"
    )
    print(
        f"Figure groups generated: "
        f"{len(generated)}"
    )
    print(
        f"Manifest: "
        f"{manifest.resolve()}"
    )
    print(
        "Each figure exists as vector PDF and 600-dpi PNG."
    )
    print(
        "============================================================"
    )


if __name__ == "__main__":
    main()
