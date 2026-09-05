#!/usr/bin/env python3
"""
CARBLE — FINAL PROFESSIONAL RESEARCH FIGURE SUITE
=================================================

This script generates the final simulation figures for the CARBLE study from
the frozen datasets and statistical outputs. It does NOT rerun simulations and
does NOT modify protocol results.

Run from project root:
    python analysis/final_research_figures.py

Outputs:
    app/build/research/FINAL-PRO-FIGURES/

Each figure is exported as:
    - vector PDF
    - 600 dpi PNG

Figure set
----------
MAIN
F01  Standard-condition performance
F02  Controlled regime-validation performance
F03  CARBLE full-transition mechanism
F04  Full-transition protocol outcomes
F05  Reliability–resource trade-off
F06  Threshold robustness
F07  Computational scalability
F08  Relay-burden analysis

SUPPLEMENTARY
S01  Standard-scenario resource efficiency
S02  Threshold stage-entry sensitivity
S03  CARBLE computational overhead relative to 2RH
S04  Full-transition retransmission burden

Scientific conventions
----------------------
- Statistical unit remains the independent seed/run.
- Error bars use the already-computed 95% BCa bootstrap CIs.
- Controlled PF-A/PF-B1/PF-B2/PF-C conditions are independent experiments:
  they are shown as grouped point estimates without connecting lines.
- Qcurrent is interpreted using .75/.65/.55/.45 local regime boundaries.
- Qroute is interpreted only using the .75 downstream-warning boundary.
- Physical attempts/retransmissions are simulation resource proxies, not
  measured BLE energy.
- Scalability timings are JVM-side synchronous computation on the benchmark
  host, not BLE or end-to-end packet latency.
"""

from __future__ import annotations

from pathlib import Path
import csv
import math
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
OUT = RESEARCH / "FINAL-PRO-FIGURES"

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

THRESHOLD_ORDER = ["EARLY", "NOMINAL", "LATE"]
THRESHOLD_DISPLAY = {
    "EARLY": "Early\n.80/.70/.60/.50",
    "NOMINAL": "Nominal\n.75/.65/.55/.45",
    "LATE": "Late\n.70/.60/.50/.40",
}

PHASE_SUCCESS = [0.90, 0.75, 0.60, 0.45, 0.30, 0.15, 0.05]

PROTOCOL_COLORS = {
    "B0": "#4D4D4D",
    "MM": "#0072B2",
    "2RH": "#E69F00",
    "TWO_RH": "#E69F00",
    "CARBLE": "#009E73",
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
    "CARBLE": "-",
}

STAGE_COLORS = {
    "HIGH": "#4D4D4D",
    "M1": "#56B4E9",
    "M2": "#E69F00",
    "M3": "#D55E00",
    "LOW": "#CC79A7",
}

GRID_COLOR = "#D9D9D9"
TEXT_COLOR = "#222222"


def apply_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "sans-serif",
            "font.sans-serif": [
                "Arial",
                "Helvetica",
                "Liberation Sans",
                "DejaVu Sans",
            ],
            "font.size": 9.0,
            "axes.labelsize": 9.5,
            "axes.titlesize": 10.0,
            "axes.titleweight": "semibold",
            "xtick.labelsize": 8.5,
            "ytick.labelsize": 8.5,
            "legend.fontsize": 8.2,
            "axes.linewidth": 0.8,
            "lines.linewidth": 1.5,
            "lines.markersize": 5.2,
            "xtick.major.width": 0.75,
            "ytick.major.width": 0.75,
            "xtick.major.size": 3.2,
            "ytick.major.size": 3.2,
            "figure.dpi": 150,
            "savefig.dpi": 600,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
            "text.color": TEXT_COLOR,
            "axes.labelcolor": TEXT_COLOR,
            "axes.edgecolor": TEXT_COLOR,
            "xtick.color": TEXT_COLOR,
            "ytick.color": TEXT_COLOR,
        }
    )


def style_axis(ax: plt.Axes, grid_axis: str = "y") -> None:
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.grid(
        True,
        axis=grid_axis,
        color=GRID_COLOR,
        linewidth=0.7,
        alpha=0.7,
        zorder=0,
    )
    ax.set_axisbelow(True)
    ax.tick_params(direction="out")


def panel_label(ax: plt.Axes, label: str) -> None:
    ax.text(
        0.0,
        1.025,
        label,
        transform=ax.transAxes,
        ha="left",
        va="bottom",
        fontsize=10,
        fontweight="bold",
    )


def add_protocol_legend(ax: plt.Axes, ncol: int = 4, loc: str = "best") -> None:
    ax.legend(
        ncol=ncol,
        loc=loc,
        frameon=False,
        handletextpad=0.45,
        columnspacing=1.0,
    )


def read_csv(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(
            f"Required frozen research file not found:\n  {path}"
        )
    return pd.read_csv(path)


def require_columns(df: pd.DataFrame, columns: list[str], dataset_name: str) -> None:
    missing = [col for col in columns if col not in df.columns]
    if missing:
        raise ValueError(
            f"{dataset_name} missing columns: {missing}"
        )


def save_figure(fig: plt.Figure, stem: str) -> tuple[Path, Path]:
    OUT.mkdir(parents=True, exist_ok=True)

    png = OUT / f"{stem}.png"
    pdf = OUT / f"{stem}.pdf"

    fig.savefig(
        png,
        dpi=600,
        bbox_inches="tight",
        pad_inches=0.05,
        facecolor="white",
    )

    fig.savefig(
        pdf,
        bbox_inches="tight",
        pad_inches=0.05,
        facecolor="white",
    )

    plt.close(fig)
    return png, pdf


def select_metric(df: pd.DataFrame, metric: str) -> pd.DataFrame:
    out = df[df["metric"] == metric].copy()

    if out.empty:
        raise ValueError(
            f"Metric '{metric}' was not found."
        )

    return out


def ci_values(
    rows: pd.DataFrame,
    percent: bool = False,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:

    mean = rows["mean"].to_numpy(dtype=float, copy=True)
    low = rows["meanCi95Low_BCa"].to_numpy(dtype=float, copy=True)
    high = rows["meanCi95High_BCa"].to_numpy(dtype=float, copy=True)

    if percent:
        mean *= 100.0
        low *= 100.0
        high *= 100.0

    return mean, mean - low, high - mean


def grouped_protocol_errorbars(
    ax: plt.Axes,
    df: pd.DataFrame,
    metric: str,
    scenario_order: list[str],
    scenario_labels: list[str],
    percent: bool = False,
    ylabel: str = "",
    xlabel: str = "",
    legend: bool = True,
) -> None:

    sub = select_metric(df, metric)

    x = np.arange(len(scenario_order), dtype=float)
    offsets = np.linspace(-0.24, 0.24, len(PROTOCOL_ORDER))

    for offset, protocol in zip(offsets, PROTOCOL_ORDER):

        rows = (
            sub[sub["protocol"] == protocol]
            .set_index("scenario")
        )

        missing = [s for s in scenario_order if s not in rows.index]

        if missing:
            raise ValueError(
                f"{metric}/{protocol} missing scenarios: {missing}"
            )

        rows = rows.loc[scenario_order].reset_index()

        mean, lower, upper = ci_values(rows, percent=percent)

        ax.errorbar(
            x + offset,
            mean,
            yerr=np.vstack([lower, upper]),
            fmt=PROTOCOL_MARKERS[protocol],
            color=PROTOCOL_COLORS[protocol],
            markeredgecolor="white",
            markeredgewidth=0.55,
            linestyle="none",
            capsize=2.8,
            capthick=0.9,
            elinewidth=0.9,
            label=DISPLAY_PROTOCOL[protocol],
            zorder=3,
        )

    ax.set_xticks(x)
    ax.set_xticklabels(scenario_labels)
    ax.set_ylabel(ylabel)
    ax.set_xlabel(xlabel)

    style_axis(ax)

    if legend:
        add_protocol_legend(ax, ncol=4, loc="best")


def protocol_point_estimates(
    ax: plt.Axes,
    df: pd.DataFrame,
    metric: str,
    percent: bool,
    ylabel: str,
) -> None:

    sub = select_metric(df, metric).set_index("protocol")

    x = np.arange(len(PROTOCOL_ORDER))

    for idx, protocol in enumerate(PROTOCOL_ORDER):

        row = sub.loc[protocol]

        mean = float(row["mean"])
        low = float(row["meanCi95Low_BCa"])
        high = float(row["meanCi95High_BCa"])

        if percent:
            mean *= 100.0
            low *= 100.0
            high *= 100.0

        ax.errorbar(
            idx,
            mean,
            yerr=np.array([[mean - low], [high - mean]]),
            fmt=PROTOCOL_MARKERS[protocol],
            color=PROTOCOL_COLORS[protocol],
            markeredgecolor="white",
            markeredgewidth=0.55,
            capsize=3,
            elinewidth=0.9,
            zorder=3,
        )

    ax.set_xticks(x)
    ax.set_xticklabels([DISPLAY_PROTOCOL[p] for p in PROTOCOL_ORDER])
    ax.set_ylabel(ylabel)
    style_axis(ax, grid_axis="y")


def figure_f01(main_desc: pd.DataFrame):
    fig, axes = plt.subplots(
        1,
        2,
        figsize=(10.2, 4.0),
        constrained_layout=True,
    )

    grouped_protocol_errorbars(
        axes[0],
        main_desc,
        metric="pdr",
        scenario_order=MAIN_ORDER,
        scenario_labels=MAIN_ORDER,
        percent=True,
        ylabel="Packet delivery ratio (%)",
        xlabel="Standard scenario",
        legend=True,
    )
    axes[0].set_title("Delivery reliability")
    panel_label(axes[0], "(a)")

    grouped_protocol_errorbars(
        axes[1],
        main_desc,
        metric="conditionalMeanLatency",
        scenario_order=MAIN_ORDER,
        scenario_labels=MAIN_ORDER,
        percent=False,
        ylabel="Conditional latency (time units)",
        xlabel="Standard scenario",
        legend=False,
    )
    axes[1].set_title("Latency among delivered packets")
    panel_label(axes[1], "(b)")

    return save_figure(fig, "F01_standard_condition_performance")


def figure_f02(pref_desc: pd.DataFrame):
    fig, ax = plt.subplots(
        figsize=(8.2, 4.8),
        constrained_layout=True,
    )

    grouped_protocol_errorbars(
        ax,
        pref_desc,
        metric="pdr",
        scenario_order=PREF_ORDER,
        scenario_labels=[DISPLAY_PREF[s] for s in PREF_ORDER],
        percent=True,
        ylabel="Packet delivery ratio (%)",
        xlabel="Controlled regime-validation condition",
        legend=True,
    )

    ax.set_title("Controlled degradation: delivery reliability")

    return save_figure(fig, "F02_controlled_regime_validation")


def binned_confidence(events: pd.DataFrame, bin_width: int = 25) -> pd.DataFrame:

    require_columns(
        events,
        [
            "seed",
            "eventTime",
            "currentHopConfidence",
            "routeConfidence",
        ],
        "full_carble_transition_events.csv",
    )

    e = events.copy()

    for col in [
        "eventTime",
        "currentHopConfidence",
        "routeConfidence",
    ]:
        e[col] = pd.to_numeric(e[col], errors="coerce")

    e = e.dropna(subset=["seed", "eventTime"])

    e["timeBin"] = (e["eventTime"] // bin_width) * bin_width

    per_seed = (
        e.groupby(["seed", "timeBin"], as_index=False)
        .agg(
            qCurrent=("currentHopConfidence", "median"),
            qRoute=("routeConfidence", "median"),
        )
    )

    return (
        per_seed
        .groupby("timeBin", as_index=False)
        .agg(
            qCurrentMedian=("qCurrent", "median"),
            qCurrentQ25=("qCurrent", lambda x: np.nanpercentile(x, 25)),
            qCurrentQ75=("qCurrent", lambda x: np.nanpercentile(x, 75)),
            qRouteMedian=("qRoute", "median"),
            qRouteQ25=("qRoute", lambda x: np.nanpercentile(x, 25)),
            qRouteQ75=("qRoute", lambda x: np.nanpercentile(x, 75)),
        )
    )


def lifecycle_metric(
    lifecycle: pd.DataFrame,
    metric: str,
) -> tuple[float, float, float]:

    row = lifecycle[
        lifecycle["mechanismMetric"] == metric
    ]

    if len(row) != 1:
        raise ValueError(
            f"Expected exactly one lifecycle row for {metric}."
        )

    r = row.iloc[0]

    return (
        float(r["meanOrRate"]),
        float(r["ci95Low_BCa"]),
        float(r["ci95High_BCa"]),
    )


def phase_share_values(
    phase_summary: pd.DataFrame,
    metric: str,
) -> np.ndarray:

    values = []

    for phase in range(1, 8):
        row = phase_summary[
            (phase_summary["phaseIndex"] == phase)
            & (phase_summary["metric"] == metric)
        ]

        if len(row) != 1:
            raise ValueError(
                f"Expected one phase row for phase={phase}, metric={metric}."
            )

        values.append(
            float(row.iloc[0]["mean"]) * 100.0
        )

    return np.asarray(values, dtype=float)


def figure_f03(
    events: pd.DataFrame,
    lifecycle: pd.DataFrame,
    phase_summary: pd.DataFrame,
):

    agg = binned_confidence(events)

    fig = plt.figure(
        figsize=(10.4, 8.0),
        constrained_layout=True,
    )

    gs = fig.add_gridspec(
        2,
        2,
        height_ratios=[1.0, 1.05],
    )

    # (a) Qcurrent
    ax = fig.add_subplot(gs[0, 0])

    ax.fill_between(
        agg["timeBin"],
        agg["qCurrentQ25"],
        agg["qCurrentQ75"],
        color="#56B4E9",
        alpha=0.18,
        linewidth=0,
        label="IQR",
    )

    ax.plot(
        agg["timeBin"],
        agg["qCurrentMedian"],
        color="#0072B2",
        marker="o",
        markersize=3.6,
        label=r"Median $Q_{\mathrm{current}}$",
    )

    threshold_labels = [
        (0.75, "HIGH / M1"),
        (0.65, "M1 / M2"),
        (0.55, "M2 / M3"),
        (0.45, "M3 / LOW"),
    ]

    for threshold, _ in threshold_labels:
        ax.axhline(
            threshold,
            color="#777777",
            linestyle="--",
            linewidth=0.85,
        )

    for threshold, label in threshold_labels:
        ax.text(
            1.005,
            threshold,
            label,
            transform=ax.get_yaxis_transform(),
            ha="left",
            va="center",
            fontsize=7.5,
            color="#555555",
        )

    for time_value in [600, 750]:
        ax.axvline(
            time_value,
            color="#777777",
            linestyle=":",
            linewidth=1.0,
        )

    ax.set_ylim(0.35, 1.01)
    ax.set_xlabel("Simulation time")
    ax.set_ylabel(r"$Q_{\mathrm{current}}$")
    ax.set_title("Current-hop confidence")
    panel_label(ax, "(a)")
    style_axis(ax, grid_axis="both")
    ax.legend(frameon=False, loc="lower left")

    # (b) Qroute
    ax = fig.add_subplot(gs[0, 1])

    ax.fill_between(
        agg["timeBin"],
        agg["qRouteQ25"],
        agg["qRouteQ75"],
        color="#E69F00",
        alpha=0.18,
        linewidth=0,
        label="IQR",
    )

    ax.plot(
        agg["timeBin"],
        agg["qRouteMedian"],
        color="#D55E00",
        marker="s",
        markersize=3.6,
        label=r"Median $Q_{\mathrm{route}}$",
    )

    ax.axhline(
        0.75,
        color="#777777",
        linestyle="--",
        linewidth=0.9,
        label="Downstream warning threshold",
    )

    for time_value in [600, 750]:
        ax.axvline(
            time_value,
            color="#777777",
            linestyle=":",
            linewidth=1.0,
        )

    ax.set_ylim(0.35, 1.01)
    ax.set_xlabel("Simulation time")
    ax.set_ylabel(r"$Q_{\mathrm{route}}$")
    ax.set_title("Remaining-route confidence")
    panel_label(ax, "(b)")
    style_axis(ax, grid_axis="both")
    ax.legend(frameon=False, loc="lower left")

    # (c) phase shares
    ax = fig.add_subplot(gs[1, 0])

    phase_x = np.arange(1, 8)
    bottom = np.zeros(7, dtype=float)

    stage_metrics = [
        ("highShare", "HIGH"),
        ("m1Share", "M1"),
        ("m2Share", "M2"),
        ("m3Share", "M3"),
        ("lowShare", "LOW"),
    ]

    for metric, label in stage_metrics:
        values = phase_share_values(
            phase_summary,
            metric,
        )

        ax.bar(
            phase_x,
            values,
            bottom=bottom,
            width=0.72,
            color=STAGE_COLORS[label],
            edgecolor="white",
            linewidth=0.6,
            label=label,
        )

        bottom += values

    ax.set_ylim(0, 100)
    ax.set_xticks(phase_x)
    ax.set_xticklabels(
        [
            f"P{i}\n{prob:.2f}"
            for i, prob in enumerate(
                PHASE_SUCCESS,
                start=1,
            )
        ]
    )
    ax.set_xlabel("Degradation phase / link success probability")
    ax.set_ylabel("Controller decision share (%)")
    ax.set_title("Progressive regime use")
    panel_label(ax, "(c)")
    style_axis(ax, grid_axis="y")
    ax.legend(
        ncol=5,
        frameon=False,
        loc="upper center",
        bbox_to_anchor=(0.5, 1.03),
        columnspacing=0.9,
        handletextpad=0.4,
    )

    # (d) first entry
    ax = fig.add_subplot(gs[1, 1])

    stage_rows = [
        ("firstM1Time", "M1"),
        ("firstM2Time", "M2"),
        ("firstM3Time", "M3"),
        ("firstLowTime", "LOW"),
    ]

    y = np.arange(len(stage_rows))

    means = []
    lows = []
    highs = []

    for metric, _ in stage_rows:
        mean, low, high = lifecycle_metric(lifecycle, metric)
        means.append(mean)
        lows.append(low)
        highs.append(high)

    means = np.asarray(means, dtype=float)
    lows = np.asarray(lows, dtype=float)
    highs = np.asarray(highs, dtype=float)

    ax.errorbar(
        means,
        y,
        xerr=np.vstack([means - lows, highs - means]),
        fmt="o",
        color=PROTOCOL_COLORS["CARBLE"],
        capsize=3,
        elinewidth=1.0,
        markersize=5.5,
        zorder=3,
    )

    for time_value, label in [
        (600, "t=600: +2 instability"),
        (750, "t=750: +3 instability"),
    ]:
        ax.axvline(
            time_value,
            color="#777777",
            linestyle=":",
            linewidth=1.0,
        )

    ax.annotate(
        label,
        xy=(time_value, 0.03),
        xycoords=("data", "axes fraction"),
        xytext=(5, 0),
        textcoords="offset points",
        rotation=90,
        ha="left",
        va="bottom",
        fontsize=7.0,
        color="#555555",
    )

    ax.set_yticks(y)
    ax.set_yticklabels(
        [label for _, label in stage_rows]
    )
    ax.invert_yaxis()
    ax.set_xlabel("First-entry time (simulation units)")
    ax.set_ylabel("Adaptive stage")
    ax.set_title("First entry into adaptive stages")
    panel_label(ax, "(d)")
    style_axis(ax, grid_axis="x")

    return save_figure(
        fig,
        "F03_carble_full_transition_mechanism",
    )


def figure_f04(
    full_desc: pd.DataFrame,
    resource_desc: pd.DataFrame,
):

    fig, axes = plt.subplots(
        1,
        3,
        figsize=(11.0, 3.9),
        constrained_layout=True,
    )

    protocol_point_estimates(
        axes[0],
        full_desc,
        metric="pdr",
        percent=True,
        ylabel="Packet delivery ratio (%)",
    )
    axes[0].set_title("Reliability")
    panel_label(axes[0], "(a)")

    protocol_point_estimates(
        axes[1],
        full_desc,
        metric="conditionalMeanLatency",
        percent=False,
        ylabel="Conditional latency",
    )
    axes[1].set_title("Delivered-packet latency")
    panel_label(axes[1], "(b)")

    protocol_point_estimates(
        axes[2],
        resource_desc,
        metric="attemptsPerDelivered",
        percent=False,
        ylabel="Physical attempts / delivered packet",
    )
    axes[2].set_title("Resource effort")
    panel_label(axes[2], "(c)")

    for ax in axes:
        ax.set_xlabel("Protocol")

    return save_figure(
        fig,
        "F04_full_transition_protocol_outcomes",
    )


def figure_f05(
    full_desc: pd.DataFrame,
    resource_desc: pd.DataFrame,
):

    pdr = (
        select_metric(full_desc, "pdr")
        .set_index("protocol")
    )

    attempts = (
        select_metric(resource_desc, "attemptsPerDelivered")
        .set_index("protocol")
    )

    fig, ax = plt.subplots(
        figsize=(7.2, 5.3),
        constrained_layout=True,
    )

    offsets = {
        "B0": (7, -12),
        "MM": (7, 8),
        "2RH": (8, 7),
        "CARBLE": (-52, 8),
    }

    for protocol in PROTOCOL_ORDER:

        x = float(attempts.loc[protocol, "mean"])
        x_low = float(attempts.loc[protocol, "meanCi95Low_BCa"])
        x_high = float(attempts.loc[protocol, "meanCi95High_BCa"])

        y = float(pdr.loc[protocol, "mean"]) * 100.0
        y_low = float(pdr.loc[protocol, "meanCi95Low_BCa"]) * 100.0
        y_high = float(pdr.loc[protocol, "meanCi95High_BCa"]) * 100.0

        ax.errorbar(
            x,
            y,
            xerr=np.array([[x - x_low], [x_high - x]]),
            yerr=np.array([[y - y_low], [y_high - y]]),
            fmt=PROTOCOL_MARKERS[protocol],
            color=PROTOCOL_COLORS[protocol],
            markeredgecolor="white",
            markeredgewidth=0.55,
            capsize=3,
            elinewidth=0.9,
            markersize=7.0,
            zorder=3,
        )

        dx, dy = offsets[protocol]

        ax.annotate(
            DISPLAY_PROTOCOL[protocol],
            xy=(x, y),
            xytext=(dx, dy),
            textcoords="offset points",
            fontsize=9,
            fontweight="semibold" if protocol == "CARBLE" else "normal",
        )

    ax.set_xlabel("Physical attempts per delivered packet")
    ax.set_ylabel("Packet delivery ratio (%)")
    ax.set_title("Full-transition reliability–resource operating points")
    style_axis(ax, grid_axis="both")

    return save_figure(
        fig,
        "F05_reliability_resource_tradeoff",
    )


def threshold_rows(
    threshold_desc: pd.DataFrame,
    metric: str,
) -> pd.DataFrame:

    sub = select_metric(
        threshold_desc,
        metric,
    )

    return (
        sub
        .set_index("thresholdConfig")
        .loc[THRESHOLD_ORDER]
        .reset_index()
    )


def threshold_panel(
    ax: plt.Axes,
    threshold_desc: pd.DataFrame,
    metric: str,
    ylabel: str,
    percent: bool,
) -> None:

    rows = threshold_rows(
        threshold_desc,
        metric,
    )

    mean, low, high = ci_values(
        rows,
        percent=percent,
    )

    x = np.arange(len(THRESHOLD_ORDER))

    ax.errorbar(
        x,
        mean,
        yerr=np.vstack([low, high]),
        fmt="o",
        color=PROTOCOL_COLORS["CARBLE"],
        markerfacecolor=PROTOCOL_COLORS["CARBLE"],
        markeredgecolor="white",
        markeredgewidth=0.55,
        capsize=3,
        elinewidth=1.0,
        markersize=6.0,
        zorder=3,
    )

    ax.set_xticks(x)
    ax.set_xticklabels(
        [THRESHOLD_DISPLAY[c] for c in THRESHOLD_ORDER]
    )
    ax.set_ylabel(ylabel)
    style_axis(ax, grid_axis="y")


def figure_f06(
    threshold_desc: pd.DataFrame,
):

    fig, axes = plt.subplots(
        1,
        3,
        figsize=(11.0, 4.0),
        constrained_layout=True,
    )

    threshold_panel(
        axes[0],
        threshold_desc,
        metric="pdr",
        ylabel="Packet delivery ratio (%)",
        percent=True,
    )
    axes[0].set_title("Reliability")
    panel_label(axes[0], "(a)")

    threshold_panel(
        axes[1],
        threshold_desc,
        metric="conditionalMeanLatency",
        ylabel="Conditional latency",
        percent=False,
    )
    axes[1].set_title("Delivered-packet latency")
    panel_label(axes[1], "(b)")

    threshold_panel(
        axes[2],
        threshold_desc,
        metric="attemptsPerDelivered",
        ylabel="Physical attempts / delivered packet",
        percent=False,
    )
    axes[2].set_title("Resource effort")
    panel_label(axes[2], "(c)")

    return save_figure(
        fig,
        "F06_threshold_robustness",
    )


def scalability_rows(
    scalability_desc: pd.DataFrame,
    topology: str,
    protocol: str,
) -> pd.DataFrame:

    rows = scalability_desc[
        (scalability_desc["topology"] == topology)
        & (scalability_desc["protocol"] == protocol)
        & (scalability_desc["metric"] == "medianLatencyUs")
    ].copy()

    return rows.sort_values("nodeCount")


def figure_f07(
    scalability_desc: pd.DataFrame,
):

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(10.5, 4.4),
        constrained_layout=True,
        sharey=True,
    )

    for ax, topology, panel, title in [
        (axes[0], "SPARSE", "(a)", "Sparse topology"),
        (axes[1], "MODERATE", "(b)", "Moderately connected topology"),
    ]:

        for protocol in SCALABILITY_PROTOCOL_ORDER:

            rows = scalability_rows(
                scalability_desc,
                topology,
                protocol,
            )

            x = rows["nodeCount"].to_numpy(
                dtype=float,
                copy=True,
            )

            mean, lower, upper = ci_values(rows)

            ax.errorbar(
                x,
                mean,
                yerr=np.vstack([lower, upper]),
                color=PROTOCOL_COLORS[protocol],
                marker=PROTOCOL_MARKERS[protocol],
                linestyle=PROTOCOL_LINESTYLES[protocol],
                capsize=2.5,
                elinewidth=0.8,
                markeredgecolor="white",
                markeredgewidth=0.45,
                label=DISPLAY_PROTOCOL[protocol],
                zorder=3,
            )

        ax.set_xscale("log")
        ax.set_yscale("log")

        ax.set_xticks([10, 25, 50, 100, 200])
        ax.set_xticklabels(["10", "25", "50", "100", "200"])

        ax.set_xlabel("Number of nodes")
        ax.set_title(title)
        panel_label(ax, panel)
        style_axis(ax, grid_axis="both")

    axes[0].set_ylabel("Median decision latency (µs)")
    add_protocol_legend(axes[0], ncol=2, loc="upper left")

    return save_figure(
        fig,
        "F07_computational_scalability",
    )


def figure_f08(
    resource_desc: pd.DataFrame,
):

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(8.8, 4.0),
        constrained_layout=True,
    )

    protocol_point_estimates(
        axes[0],
        resource_desc,
        metric="maxRelayAttemptShare",
        percent=True,
        ylabel="Maximum relay-attempt share (%)",
    )
    axes[0].set_title("Relay-attempt concentration")
    axes[0].set_xlabel("Protocol")
    panel_label(axes[0], "(a)")

    protocol_point_estimates(
        axes[1],
        resource_desc,
        metric="jainRelayAttemptFairness",
        percent=False,
        ylabel="Jain relay-attempt index",
    )
    axes[1].set_title("Relay-burden distribution")
    axes[1].set_xlabel("Protocol")
    panel_label(axes[1], "(b)")

    return save_figure(
        fig,
        "F08_relay_burden",
    )


def figure_s01(
    main_resource_desc: pd.DataFrame,
):

    fig, axes = plt.subplots(
        1,
        2,
        figsize=(10.0, 4.0),
        constrained_layout=True,
    )

    grouped_protocol_errorbars(
        axes[0],
        main_resource_desc,
        metric="attemptsPerDelivered",
        scenario_order=MAIN_ORDER,
        scenario_labels=MAIN_ORDER,
        percent=False,
        ylabel="Physical attempts / delivered packet",
        xlabel="Standard scenario",
        legend=True,
    )
    axes[0].set_title("Transmission effort")
    panel_label(axes[0], "(a)")

    grouped_protocol_errorbars(
        axes[1],
        main_resource_desc,
        metric="retransmissionsPerDelivered",
        scenario_order=MAIN_ORDER,
        scenario_labels=MAIN_ORDER,
        percent=False,
        ylabel="Retransmissions / delivered packet",
        xlabel="Standard scenario",
        legend=False,
    )
    axes[1].set_title("Retransmission effort")
    panel_label(axes[1], "(b)")

    return save_figure(
        fig,
        "S01_standard_resource_efficiency",
    )


def figure_s02(
    threshold_desc: pd.DataFrame,
):

    stage_metrics = [
        ("firstM1Time", "M1"),
        ("firstM2Time", "M2"),
        ("firstM3Time", "M3"),
        ("firstLowTime", "LOW"),
    ]

    fig, ax = plt.subplots(
        figsize=(8.3, 5.0),
        constrained_layout=True,
    )

    config_x = np.arange(len(THRESHOLD_ORDER))

    stage_offsets = np.linspace(
        -0.24,
        0.24,
        len(stage_metrics),
    )

    for offset, (metric, stage_label) in zip(
        stage_offsets,
        stage_metrics,
    ):

        sub = select_metric(
            threshold_desc,
            metric,
        )

        means = []
        lows = []
        highs = []
        xs = []

        for idx, config in enumerate(THRESHOLD_ORDER):

            row = sub[
                sub["thresholdConfig"] == config
            ]

            if len(row) != 1:
                continue

            r = row.iloc[0]

            mean = float(r["mean"])
            low = float(r["meanCi95Low_BCa"])
            high = float(r["meanCi95High_BCa"])

            if not all(
                math.isfinite(v)
                for v in [mean, low, high]
            ):
                continue

            xs.append(config_x[idx] + offset)
            means.append(mean)
            lows.append(low)
            highs.append(high)

        if not means:
            continue

        means = np.asarray(means, dtype=float)
        lows = np.asarray(lows, dtype=float)
        highs = np.asarray(highs, dtype=float)

        ax.errorbar(
            xs,
            means,
            yerr=np.vstack(
                [
                    means - lows,
                    highs - means,
                ]
            ),
            fmt="o",
            capsize=3,
            label=stage_label,
        )

    ax.set_xticks(config_x)
    ax.set_xticklabels(
        [THRESHOLD_DISPLAY[c] for c in THRESHOLD_ORDER]
    )

    ax.set_xlabel("Threshold configuration")
    ax.set_ylabel("First-entry time (simulation units)")
    ax.set_title("Threshold sensitivity of stage-entry timing")
    style_axis(ax, grid_axis="y")
    ax.legend(frameon=False, ncol=4)

    return save_figure(
        fig,
        "S02_threshold_stage_entry_sensitivity",
    )


def figure_s03(
    scalability_pairwise: pd.DataFrame,
):

    require_columns(
        scalability_pairwise,
        [
            "topology",
            "nodeCount",
            "metric",
            "comparison",
            "meanPairedRatio_CARBLEoverBaseline",
            "ratioCi95Low_BCa",
            "ratioCi95High_BCa",
        ],
        "scalability_pairwise_carble.csv",
    )

    sub = scalability_pairwise[
        (scalability_pairwise["metric"] == "medianLatencyUs")
        & (
            scalability_pairwise["comparison"]
            == "CARBLE_vs_TWO_RH"
        )
    ].copy()

    fig, ax = plt.subplots(
        figsize=(7.7, 4.7),
        constrained_layout=True,
    )

    for topology, marker, linestyle in [
        ("SPARSE", "o", "-"),
        ("MODERATE", "s", "--"),
    ]:

        rows = (
            sub[
                sub["topology"] == topology
            ]
            .sort_values("nodeCount")
        )

        x = rows["nodeCount"].to_numpy(
            dtype=float,
            copy=True,
        )

        ratio = rows[
            "meanPairedRatio_CARBLEoverBaseline"
        ].to_numpy(
            dtype=float,
            copy=True,
        )

        low = rows["ratioCi95Low_BCa"].to_numpy(
            dtype=float,
            copy=True,
        )

        high = rows["ratioCi95High_BCa"].to_numpy(
            dtype=float,
            copy=True,
        )

        overhead = (ratio - 1.0) * 100.0
        lower = (ratio - low) * 100.0
        upper = (high - ratio) * 100.0

        ax.errorbar(
            x,
            overhead,
            yerr=np.vstack([lower, upper]),
            marker=marker,
            linestyle=linestyle,
            capsize=3,
            label="Sparse" if topology == "SPARSE" else "Moderate",
        )

    ax.axhline(
        0.0,
        color="#777777",
        linestyle=":",
        linewidth=1.0,
    )

    ax.set_xscale("log")
    ax.set_xticks([10, 25, 50, 100, 200])
    ax.set_xticklabels(["10", "25", "50", "100", "200"])

    ax.set_xlabel("Number of nodes")
    ax.set_ylabel("CARBLE overhead vs 2RH (%)")
    ax.set_title("Incremental controller-computation overhead")
    style_axis(ax, grid_axis="both")
    ax.legend(frameon=False)

    return save_figure(
        fig,
        "S03_carble_overhead_vs_2RH",
    )


def figure_s04(
    resource_desc: pd.DataFrame,
):

    fig, ax = plt.subplots(
        figsize=(7.2, 4.7),
        constrained_layout=True,
    )

    protocol_point_estimates(
        ax,
        resource_desc,
        metric="retransmissionsPerDelivered",
        percent=False,
        ylabel="Retransmissions per delivered packet",
    )

    ax.set_xlabel("Protocol")
    ax.set_title("Full-transition retransmission burden")

    return save_figure(
        fig,
        "S04_full_transition_retransmissions",
    )


def write_manifest(
    records: list[
        tuple[
            str,
            str,
            str,
            Path,
            Path,
        ]
    ],
) -> Path:

    OUT.mkdir(parents=True, exist_ok=True)

    manifest = OUT / "final_figure_manifest.csv"

    with manifest.open(
        "w",
        encoding="utf-8",
        newline="",
    ) as f:

        writer = csv.writer(f)

        writer.writerow(
            [
                "figure",
                "role",
                "captionDraft",
                "png",
                "pdf",
            ]
        )

        writer.writerows(records)

    return manifest


def main() -> None:

    apply_style()

    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    print(
        f"Research root: {RESEARCH.resolve()}"
    )

    print(
        f"Final professional figures: {OUT.resolve()}"
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

    main_resource_desc = read_csv(
        STATS / "main_resource_descriptive.csv"
    )

    resource_desc = read_csv(
        STATS / "full_resource_descriptive.csv"
    )

    lifecycle = read_csv(
        STATS / "carble_lifecycle_summary.csv"
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

    scalability_pairwise = read_csv(
        STATS / "scalability_pairwise_carble.csv"
    )

    records = []

    def add(
        figure_id: str,
        role: str,
        caption: str,
        function,
    ) -> None:

        png, pdf = function()

        records.append(
            (
                figure_id,
                role,
                caption,
                png,
                pdf,
            )
        )

        print(
            f"Generated {figure_id}: {png.name}"
        )

    add(
        "F01",
        "Main — standard-condition behavior",
        "Performance under the five standard scenarios. "
        "(a) Packet delivery ratio and (b) conditional latency among delivered packets. "
        "Points show means across 30 independent seeds; error bars show 95% BCa bootstrap confidence intervals.",
        lambda: figure_f01(main_desc),
    )

    add(
        "F02",
        "Main — controlled regime validation",
        "Packet delivery ratio under controlled M1, M2, M3, and LOW validation conditions. "
        "Conditions are independent experiments and are therefore shown without connecting lines. "
        "Points show means across 30 paired seeds; error bars show 95% BCa bootstrap confidence intervals.",
        lambda: figure_f02(pref_desc),
    )

    add(
        "F03",
        "Main — CARBLE mechanism",
        "CARBLE behavior during the full-transition experiment. "
        "(a) Current-hop confidence with local regime boundaries; "
        "(b) remaining-route confidence with the 0.75 downstream-warning boundary; "
        "(c) phase-wise controller decision shares; and "
        "(d) first-entry timing into M1, M2, M3, and LOW. "
        "Confidence trajectories use cross-seed medians of within-seed time-bin medians with interquartile ranges.",
        lambda: figure_f03(
            events,
            lifecycle,
            phase_summary,
        ),
    )

    add(
        "F04",
        "Main — full-transition outcomes",
        "Full-transition reliability, conditional latency, and physical-attempt expenditure across B0, MM, 2RH, and CARBLE. "
        "Error bars show 95% BCa bootstrap confidence intervals across 30 paired seeds.",
        lambda: figure_f04(
            full_desc,
            resource_desc,
        ),
    )

    add(
        "F05",
        "Main — reliability/resource trade-off",
        "Reliability-resource operating points in the full-transition experiment. "
        "Physical attempts per delivered packet are a simulation resource proxy rather than measured energy consumption.",
        lambda: figure_f05(
            full_desc,
            resource_desc,
        ),
    )

    add(
        "F06",
        "Main — threshold robustness",
        "Sensitivity of reliability, conditional latency, and physical-attempt expenditure to pre-specified earlier and later escalation thresholds. "
        "The nominal configuration remains CARBLE-v1.0; alternatives are robustness perturbations rather than tuned replacements.",
        lambda: figure_f06(
            threshold_desc
        ),
    )

    add(
        "F07",
        "Main — computational scalability",
        "JVM-side routing/controller decision latency for sparse and moderately connected graphs from 10 to 200 nodes. "
        "Each point is the mean across 30 graph seeds of the within-seed median over 500 measured decisions; error bars show 95% BCa bootstrap confidence intervals.",
        lambda: figure_f07(
            scalability_desc
        ),
    )

    add(
        "F08",
        "Main — relay burden",
        "Relay-attempt burden in the full-transition experiment. "
        "(a) Maximum relay-attempt share and (b) Jain index of relay-attempt distribution. "
        "These are burden-distribution measures in a structurally asymmetric topology, not measured energy.",
        lambda: figure_f08(
            resource_desc
        ),
    )

    add(
        "S01",
        "Supplementary — standard resource efficiency",
        "Physical attempts and retransmissions per delivered packet under the five standard scenarios.",
        lambda: figure_s01(
            main_resource_desc
        ),
    )

    add(
        "S02",
        "Supplementary — threshold stage timing",
        "First-entry timing into CARBLE adaptive stages under early, nominal, and late threshold perturbations. "
        "A missing LOW estimate for the late configuration reflects that LOW was not reached in those runs.",
        lambda: figure_s02(
            threshold_desc
        ),
    )

    add(
        "S03",
        "Supplementary — CARBLE incremental compute overhead",
        "CARBLE median routing/controller decision-time overhead relative to 2RH across graph sizes. "
        "Zero indicates no incremental overhead; error bars are 95% BCa intervals for paired latency ratios.",
        lambda: figure_s03(
            scalability_pairwise
        ),
    )

    add(
        "S04",
        "Supplementary — severe-condition retransmission burden",
        "Retransmissions per delivered packet during the full-transition experiment, reported as a simulation resource proxy.",
        lambda: figure_s04(
            resource_desc
        ),
    )

    manifest = write_manifest(records)

    print()
    print(
        "======================================================================"
    )
    print(
        "FINAL PROFESSIONAL FIGURE SUITE COMPLETE"
    )
    print(
        f"Figure groups: {len(records)} (8 main + 4 supplementary)"
    )
    print(
        f"Manifest: {manifest.resolve()}"
    )
    print(
        "Each figure exists as vector PDF and 600-dpi PNG."
    )
    print(
        "======================================================================"
    )


if __name__ == "__main__":
    main()
