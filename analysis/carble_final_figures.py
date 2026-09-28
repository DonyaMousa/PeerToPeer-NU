#!/usr/bin/env python3
"""
CARBLE final paper figure generator.

Design goals
------------
- Proportions suitable for IEEE-style two-column papers.
- No oversized titles or legends.
- Compact, readable labels at print scale.
- High-resolution PNG output.
- Headless-safe Matplotlib backend.
- One plot per figure.
- Neutral scenario labels.
- No custom color palette; Matplotlib defaults are used.

Expected input directory:
    ./outputs/statistics

Expected output directory:
    ./outputs/figures
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib
matplotlib.use("Agg")

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


PROTOCOL_ORDER = ["B0", "MM", "TWO_RH", "CARBLE"]
PROTOCOL_LABEL = {
    "B0": "B0",
    "MM": "MM",
    "TWO_RH": "2RH",
    "CARBLE": "CARBLE",
}

PF_ORDER = ["PF_A", "PF_B1", "PF_B2", "PF_C"]
PF_LABEL = {
    "PF_A": "PF-A",
    "PF_B1": "PF-B1",
    "PF_B2": "PF-B2",
    "PF_C": "PF-C",
    "FULL_DEGRADATION": "Full",
}

# Roughly suitable for a single-column IEEE figure.
SINGLE_COL = (3.45, 2.55)

# Suitable when a figure may span both columns.
DOUBLE_COL = (7.0, 3.4)


def set_paper_rc():
    plt.rcParams.update({
        "font.size": 8.5,
        "axes.titlesize": 9.5,
        "axes.labelsize": 8.5,
        "xtick.labelsize": 8,
        "ytick.labelsize": 8,
        "legend.fontsize": 7.5,
        "figure.dpi": 120,
        "savefig.dpi": 600,
        "axes.linewidth": 0.8,
        "lines.linewidth": 1.2,
        "lines.markersize": 4.5,
        "xtick.major.width": 0.8,
        "ytick.major.width": 0.8,
        "xtick.major.size": 3,
        "ytick.major.size": 3,
    })


def polish(ax):
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)


def save(fig, path: Path):
    fig.savefig(
        path,
        dpi=600,
        bbox_inches="tight",
        pad_inches=0.03,
    )
    plt.close(fig)


def fig1_pdr(perf: pd.DataFrame, out: Path):
    d = perf[
        (perf["metric"] == "pdr")
        & (perf["scenario"].isin(PF_ORDER))
    ].copy()

    x = np.arange(len(PF_ORDER), dtype=float)
    offsets = [-0.18, -0.06, 0.06, 0.18]

    fig, ax = plt.subplots(figsize=DOUBLE_COL)

    for offset, protocol in zip(offsets, PROTOCOL_ORDER):
        g = (
            d[d["protocol"] == protocol]
            .set_index("scenario")
            .reindex(PF_ORDER)
        )

        mean = 100 * g["mean"].to_numpy(float)
        low = 100 * (g["mean"] - g["ci95Low"]).to_numpy(float)
        high = 100 * (g["ci95High"] - g["mean"]).to_numpy(float)

        ax.errorbar(
            x + offset,
            mean,
            yerr=np.vstack([low, high]),
            fmt="o",
            capsize=2.2,
            label=PROTOCOL_LABEL[protocol],
        )

    ax.set_xticks(x)
    ax.set_xticklabels([PF_LABEL[s] for s in PF_ORDER])
    ax.set_ylabel("PDR (%)")
    ax.set_xlabel("Controlled degradation condition")
    ax.set_ylim(60, 100)
    ax.set_title("Delivery reliability under controlled degradation")

    ax.legend(
        loc="upper center",
        bbox_to_anchor=(0.5, 1.02),
        ncol=4,
        frameon=False,
        columnspacing=1.1,
        handlelength=1.2,
    )

    polish(ax)
    fig.tight_layout(pad=0.5)
    save(fig, out / "F1_controlled_degradation_pdr.png")

def fig2_delta_pdr(paired: pd.DataFrame, out: Path):
    d = paired[
        (paired["metric"] == "pdr")
        & (paired["comparison"] == "CARBLE_vs_TWO_RH")
    ].copy()

    order = PF_ORDER + ["FULL_DEGRADATION"]
    d["sort"] = d["scenario"].map({s: i for i, s in enumerate(order)})
    d = d.sort_values("sort")

    y = np.arange(len(d))
    delta = 100 * d["meanDifference"].to_numpy(float)
    low = 100 * (d["meanDifference"] - d["ci95Low"]).to_numpy(float)
    high = 100 * (d["ci95High"] - d["meanDifference"]).to_numpy(float)

    fig, ax = plt.subplots(figsize=SINGLE_COL)

    ax.errorbar(
        delta,
        y,
        xerr=np.vstack([low, high]),
        fmt="o",
        capsize=2.5,
    )

    ax.axvline(0, linestyle="--", linewidth=0.8)

    ax.set_yticks(y)
    ax.set_yticklabels([PF_LABEL[s] for s in d["scenario"]])
    ax.invert_yaxis()

    ax.set_xlabel("CARBLE − 2RH PDR (pp)")
    ax.set_title("Paired PDR advantage of CARBLE")

    for yy, value in zip(y, delta):
        ax.annotate(
            f"{value:.1f}",
            (value, yy),
            xytext=(4, 0),
            textcoords="offset points",
            va="center",
            fontsize=7.2,
        )

    polish(ax)
    fig.tight_layout(pad=0.5)
    save(fig, out / "F2_carble_vs_2rh_delta_pdr.png")


def fig3_carble_occupancy(mech: pd.DataFrame, out: Path):
    states = ["HIGH", "M1", "M2", "M3", "LOW"]
    x = np.arange(len(PF_ORDER))
    bottoms = np.zeros(len(PF_ORDER))

    fig, ax = plt.subplots(figsize=DOUBLE_COL)

    for state in states:
        g = (
            mech[
                (mech["protocol"] == "CARBLE")
                & (mech["state"] == state)
            ]
            .set_index("scenario")
            .reindex(PF_ORDER)
        )

        vals = 100 * g["share_mean"].to_numpy(float)

        ax.bar(
            x,
            vals,
            bottom=bottoms,
            width=0.62,
            label=state,
            linewidth=0.3,
        )

        bottoms += np.nan_to_num(vals)

    ax.set_xticks(x)
    ax.set_xticklabels([PF_LABEL[s] for s in PF_ORDER])
    ax.set_ylabel("Decision share (%)")
    ax.set_xlabel("Controlled degradation condition")
    ax.set_ylim(0, 100)
    ax.set_title("CARBLE controller-state occupancy")

    ax.legend(
        loc="upper center",
        bbox_to_anchor=(0.5, 1.02),
        ncol=5,
        frameon=False,
        columnspacing=0.9,
        handlelength=1.2,
    )

    polish(ax)
    fig.tight_layout(pad=0.5)
    save(fig, out / "F3_carble_stage_occupancy.png")


def fig4_2rh_occupancy(mech: pd.DataFrame, out: Path):
    x = np.arange(len(PF_ORDER))

    high = (
        mech[
            (mech["protocol"] == "TWO_RH")
            & (mech["state"] == "HIGH")
        ]
        .set_index("scenario")
        .reindex(PF_ORDER)
    )

    low = (
        mech[
            (mech["protocol"] == "TWO_RH")
            & (mech["state"] == "LOW")
        ]
        .set_index("scenario")
        .reindex(PF_ORDER)
    )

    high_vals = 100 * high["share_mean"].to_numpy(float)
    low_vals = 100 * low["share_mean"].to_numpy(float)

    fig, ax = plt.subplots(figsize=SINGLE_COL)

    ax.bar(
        x,
        high_vals,
        width=0.62,
        label="HIGH",
        linewidth=0.3,
    )

    ax.bar(
        x,
        low_vals,
        bottom=high_vals,
        width=0.62,
        label="LOW",
        linewidth=0.3,
    )

    ax.set_xticks(x)
    ax.set_xticklabels([PF_LABEL[s] for s in PF_ORDER])
    ax.set_ylabel("Decision share (%)")
    ax.set_xlabel("Condition")
    ax.set_ylim(0, 100)
    ax.set_title("2RH binary controller occupancy")

    ax.legend(
        loc="upper center",
        bbox_to_anchor=(0.5, 1.02),
        ncol=2,
        frameon=False,
    )

    polish(ax)
    fig.tight_layout(pad=0.5)
    save(fig, out / "F4_2rh_binary_occupancy.png")


def fig5_reliability_cost(perf: pd.DataFrame, out: Path):
    pdr = perf[
        (perf["scenario"] == "FULL_DEGRADATION")
        & (perf["metric"] == "pdr")
    ].set_index("protocol")

    cost = perf[
        (perf["scenario"] == "FULL_DEGRADATION")
        & (perf["metric"] == "attemptsPerGenerated")
    ].set_index("protocol")

    fig, ax = plt.subplots(figsize=SINGLE_COL)

    points = {}

    for protocol in PROTOCOL_ORDER:
        if protocol not in pdr.index or protocol not in cost.index:
            continue

        x = float(cost.loc[protocol, "mean"])
        y = 100 * float(pdr.loc[protocol, "mean"])
        points[protocol] = (x, y)

        ax.scatter([x], [y], s=28)

    offsets = {
        "B0": (4, -5),
        "MM": (4, 5),
        "TWO_RH": (4, 5),
        "CARBLE": (-37, 5),
    }

    for protocol, (x, y) in points.items():
        ax.annotate(
            PROTOCOL_LABEL[protocol],
            (x, y),
            xytext=offsets[protocol],
            textcoords="offset points",
            fontsize=7.5,
        )

    xs = [v[0] for v in points.values()]
    ys = [v[1] for v in points.values()]

    if xs:
        ax.set_xlim(min(xs) - 0.12, max(xs) + 0.12)
    if ys:
        ax.set_ylim(min(ys) - 1.2, max(ys) + 1.2)

    ax.set_xlabel("Physical attempts / generated packet")
    ax.set_ylabel("PDR (%)")
    ax.set_title("Reliability–cost tradeoff")

    polish(ax)
    fig.tight_layout(pad=0.5)
    save(fig, out / "F5_reliability_cost_tradeoff.png")


def fig6_transition_timing(timing: pd.DataFrame, out: Path):
    order = ["firstM1Time", "firstM2Time", "firstM3Time", "firstLowTime"]
    labels = ["M1", "M2", "M3", "LOW"]

    g = timing[timing["metric"].isin(order)].copy()
    g["sort"] = g["metric"].map({m: i for i, m in enumerate(order)})
    g = g.sort_values("sort")

    x = np.arange(len(g))
    mean = g["mean"].to_numpy(float)
    low = (g["mean"] - g["ci95Low"]).to_numpy(float)
    high = (g["ci95High"] - g["mean"]).to_numpy(float)

    fig, ax = plt.subplots(figsize=SINGLE_COL)

    ax.errorbar(
        x,
        mean,
        yerr=np.vstack([low, high]),
        fmt="o",
        capsize=2.5,
    )

    for xx, yy in zip(x, mean):
        ax.annotate(
            f"{yy:.0f}",
            (xx, yy),
            xytext=(0, 5),
            textcoords="offset points",
            ha="center",
            fontsize=7.2,
        )

    ax.set_xticks(x)
    ax.set_xticklabels(labels[:len(g)])
    ax.set_xlabel("CARBLE stage")
    ax.set_ylabel("First-entry time")
    ax.set_title("CARBLE stage first-entry timing")

    polish(ax)
    fig.tight_layout(pad=0.5)
    save(fig, out / "F6_carble_transition_timing.png")


def main():
    set_paper_rc()

    parser = argparse.ArgumentParser()
    parser.add_argument("--stats-dir", default="./outputs/statistics")
    parser.add_argument("--outdir", default="./outputs/figures")
    args = parser.parse_args()

    stats = Path(args.stats_dir)
    out = Path(args.outdir)
    out.mkdir(parents=True, exist_ok=True)

    perf = pd.read_csv(stats / "protocol_performance_summary.csv")
    paired = pd.read_csv(stats / "paired_protocol_comparisons.csv")
    mech = pd.read_csv(stats / "mechanism_summary.csv")
    timing = pd.read_csv(stats / "transition_timing_summary.csv")

    fig1_pdr(perf, out)
    fig2_delta_pdr(paired, out)
    fig3_carble_occupancy(mech, out)
    fig4_2rh_occupancy(mech, out)
    fig5_reliability_cost(perf, out)
    fig6_transition_timing(timing, out)

    print("Generated final paper-proportioned figures:")
    for p in sorted(out.glob("*.png")):
        print(p)


if __name__ == "__main__":
    main()
