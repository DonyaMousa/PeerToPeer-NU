#!/usr/bin/env python3
"""
CARBLE final research table generator.

Outputs BOTH:
  - Markdown tables (.md)
  - PNG table images (.png)

Headless-safe: uses Matplotlib Agg backend.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib
matplotlib.use("Agg")  # must be before pyplot

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


PROTOCOL_ORDER = ["B0", "MM", "TWO_RH", "CARBLE"]
SCENARIO_ORDER = ["PF_A", "PF_B1", "PF_B2", "PF_C", "FULL_DEGRADATION"]


def ci_cell(row, scale=1.0, digits=2):
    return (
        f"{row['mean']*scale:.{digits}f} "
        f"[{row['ci95Low']*scale:.{digits}f}, {row['ci95High']*scale:.{digits}f}]"
    )


def save_markdown(df: pd.DataFrame, path: Path, title: str, note: str = ""):
    text = f"# {title}\n\n"
    text += df.to_markdown(index=False)
    if note:
        text += f"\n\n{note}\n"
    path.write_text(text, encoding="utf-8")


def save_png_table(
    df: pd.DataFrame,
    path: Path,
    title: str,
    note: str = "",
    font_size: int = 8,
):
    if df.empty:
        return

    rows = len(df)
    cols = len(df.columns)

    fig_width = max(8.0, cols * 2.05)
    fig_height = max(2.8, 1.2 + rows * 0.38 + (0.6 if note else 0))

    fig, ax = plt.subplots(figsize=(fig_width, fig_height))
    ax.axis("off")

    table = ax.table(
        cellText=df.astype(str).values,
        colLabels=df.columns.tolist(),
        loc="center",
        cellLoc="center",
    )

    table.auto_set_font_size(False)
    table.set_fontsize(font_size)
    table.scale(1, 1.25)

    for (r, c), cell in table.get_celld().items():
        if r == 0:
            cell.set_text_props(weight="bold")

    ax.set_title(title, pad=14, fontsize=12, weight="bold")

    if note:
        fig.text(
            0.5,
            0.02,
            note,
            ha="center",
            va="bottom",
            fontsize=8,
            wrap=True,
        )

    fig.tight_layout(rect=[0.01, 0.05 if note else 0.01, 0.99, 0.95])
    fig.savefig(path, dpi=300, bbox_inches="tight")
    plt.close(fig)


def export_table(df, outdir, stem, title, note=""):
    save_markdown(df, outdir / f"{stem}.md", title, note)
    save_png_table(df, outdir / f"{stem}.png", title, note)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stats-dir", default="./outputs/statistics")
    ap.add_argument("--outdir", default="./outputs/tables")
    args = ap.parse_args()

    stats = Path(args.stats_dir)
    out = Path(args.outdir)
    out.mkdir(parents=True, exist_ok=True)

    perf = pd.read_csv(stats / "protocol_performance_summary.csv")
    paired = pd.read_csv(stats / "paired_protocol_comparisons.csv")
    mech = pd.read_csv(stats / "mechanism_summary.csv")
    timing = pd.read_csv(stats / "transition_timing_summary.csv")
    order = pd.read_csv(stats / "transition_order_summary.csv")
    resource = pd.read_csv(stats / "resource_cost_summary.csv")

    # T1
    rows = []
    for scenario in SCENARIO_ORDER:
        for protocol in PROTOCOL_ORDER:
            g = perf[
                (perf.scenario == scenario)
                & (perf.protocol == protocol)
            ]
            if g.empty:
                continue

            vals = {r.metric: r for _, r in g.iterrows()}
            rows.append({
                "Condition": scenario,
                "Protocol": "2RH" if protocol == "TWO_RH" else protocol,
                "PDR % [95% CI]": (
                    ci_cell(vals["pdr"], 100.0)
                    if "pdr" in vals else "—"
                ),
                "Median latency [95% CI]": (
                    ci_cell(vals["conditionalMedianLatency"])
                    if "conditionalMedianLatency" in vals else "—"
                ),
                "Attempts/generated [95% CI]": (
                    ci_cell(vals["attemptsPerGenerated"])
                    if "attemptsPerGenerated" in vals else "—"
                ),
            })

    export_table(
        pd.DataFrame(rows),
        out,
        "T1_protocol_performance",
        "Protocol performance under controlled degradation",
        "Latency is conditional on successful delivery. One seed/run is one independent observation.",
    )

    # T2
    pdr = paired[paired.metric == "pdr"].copy()
    rows = []

    for scenario in SCENARIO_ORDER:
        for comparison in [
            "CARBLE_vs_TWO_RH",
            "CARBLE_vs_MM",
            "CARBLE_vs_B0",
        ]:
            g = pdr[
                (pdr.scenario == scenario)
                & (pdr.comparison == comparison)
            ]
            if g.empty:
                continue

            r = g.iloc[0]
            rows.append({
                "Condition": scenario,
                "Comparison": comparison.replace("TWO_RH", "2RH"),
                "CARBLE PDR %": f"{100*r.carbleMean:.2f}",
                "Comparator PDR %": f"{100*r.comparatorMean:.2f}",
                "ΔPDR pp": f"{100*r.meanDifference:.2f}",
                "95% BCa CI pp": f"[{100*r.ci95Low:.2f}, {100*r.ci95High:.2f}]",
                "Holm p": f"{r.holmAdjustedP_pdrFamily:.4g}",
                "Rank-biserial": f"{r.rankBiserial:.3f}",
            })

    export_table(
        pd.DataFrame(rows),
        out,
        "T2_paired_pdr_effects",
        "Paired PDR effects",
        "Primary comparison is CARBLE vs 2RH; MM and B0 are secondary/reference comparisons.",
    )

    # T3
    rows = []

    for scenario in SCENARIO_ORDER:
        for protocol in ["TWO_RH", "CARBLE"]:
            states = (
                ["HIGH", "LOW"]
                if protocol == "TWO_RH"
                else ["HIGH", "M1", "M2", "M3", "LOW"]
            )

            for state in states:
                g = mech[
                    (mech.scenario == scenario)
                    & (mech.protocol == protocol)
                    & (mech.state == state)
                ]
                if g.empty:
                    continue

                r = g.iloc[0]
                rows.append({
                    "Condition": scenario,
                    "Controller": "2RH" if protocol == "TWO_RH" else "CARBLE",
                    "State": state,
                    "Mean share %": f"{100*r.share_mean:.2f}",
                    "95% BCa CI %": (
                        f"[{100*r.share_ci95Low:.2f}, "
                        f"{100*r.share_ci95High:.2f}]"
                    ),
                    "Mean decisions/run": f"{r.meanDecisionCount:.1f}",
                })

    export_table(
        pd.DataFrame(rows),
        out,
        "T3_mechanism_occupancy",
        "Controller-state occupancy",
        "M1/M2/M3 are CARBLE-only states; 2RH uses only HIGH/LOW.",
    )

    # T4
    rows = []

    for metric in [
        "firstM1Time",
        "firstM2Time",
        "firstM3Time",
        "firstLowTime",
        "m1ToLowLeadTime",
    ]:
        g = timing[timing.metric == metric]
        if g.empty:
            continue

        r = g.iloc[0]
        rows.append({
            "Metric": metric,
            "Mean [95% BCa CI]": ci_cell(r),
            "Median": f"{r['median']:.2f}",
            "SD": f"{r['sd']:.2f}",
        })

    transition_note = (
        f"All-stage activation: "
        f"{int(order.iloc[0].allStagesCount)}/{int(order.iloc[0].nSeeds)} "
        f"({100*order.iloc[0].allStagesRate:.1f}%). "
        f"Strict M1<M2<M3<LOW: "
        f"{int(order.iloc[0].strictOrderCount)}/{int(order.iloc[0].nSeeds)} "
        f"({100*order.iloc[0].strictOrderRate:.1f}%)."
    )

    export_table(
        pd.DataFrame(rows),
        out,
        "T4_transition_timing",
        "CARBLE transition timing",
        transition_note,
    )

    # T5
    wanted = [
        "attemptsPerGenerated",
        "attemptsPerDelivered",
        "retransmissionsPerDelivered",
        "maxRelayAttemptShare",
        "jainRelayAttemptFairness",
    ]

    rows = []

    for protocol in PROTOCOL_ORDER:
        g = resource[resource.protocol == protocol]
        if g.empty:
            continue

        row = {
            "Protocol": "2RH" if protocol == "TWO_RH" else protocol
        }

        for metric in wanted:
            x = g[g.metric == metric]
            if x.empty:
                row[metric] = "—"
            else:
                r = x.iloc[0]
                scale = 100.0 if metric == "maxRelayAttemptShare" else 1.0
                row[metric] = ci_cell(r, scale=scale)

        rows.append(row)

    export_table(
        pd.DataFrame(rows),
        out,
        "T5_resource_cost",
        "Full-degradation forwarding cost and relay fairness",
        "Higher Jain fairness means relay work is distributed more evenly.",
    )

    print("Generated tables:")
    for p in sorted(out.iterdir()):
        if p.suffix.lower() in {".md", ".png"}:
            print(p)


if __name__ == "__main__":
    main()
