#!/usr/bin/env python3
"""
CARBLE final research tables
============================

Builds publication-ready PNG and CSV tables from the frozen
FINAL-STATISTICS outputs. No simulation is rerun and no raw evidence is changed.

Run from project root:
    python analysis/final_research_tables.py

Output:
    app/build/research/FINAL-TABLES/
"""

from __future__ import annotations

from pathlib import Path
import math
import pandas as pd
import numpy as np

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
OUT = RESEARCH / "FINAL-TABLES"

PROTOCOLS = ["B0", "MM", "2RH", "CARBLE"]
MAIN_SCENARIOS = ["S01", "S02", "S03", "S04", "S05"]
PREF_SCENARIOS = ["PF_A_M1", "PF_B1_M2", "PF_B2_M3", "PF_C_LOW"]

DISPLAY_PREF = {
    "PF_A_M1": "PF-A / M1",
    "PF_B1_M2": "PF-B1 / M2",
    "PF_B2_M3": "PF-B2 / M3",
    "PF_C_LOW": "PF-C / LOW",
}

THRESHOLD_ORDER = ["EARLY", "NOMINAL", "LATE"]


def read(name: str) -> pd.DataFrame:
    path = STATS / name
    if not path.exists():
        raise FileNotFoundError(f"Missing required statistics file: {path}")
    return pd.read_csv(path)


def fmt_num(x, digits=3):
    if pd.isna(x):
        return "—"
    return f"{float(x):.{digits}f}"


def fmt_p(x):
    if pd.isna(x):
        return "—"
    x = float(x)
    if x < 0.001:
        return "<0.001"
    return f"{x:.3f}"


def fmt_mean_ci(row, percent=False, digits=2):
    m = float(row["mean"])
    lo = float(row["meanCi95Low_BCa"])
    hi = float(row["meanCi95High_BCa"])
    if percent:
        m *= 100
        lo *= 100
        hi *= 100
    return f"{m:.{digits}f} [{lo:.{digits}f}, {hi:.{digits}f}]"


def get_desc_row(df, *, scenario=None, protocol=None, metric=None,
                 threshold=None, topology=None, node_count=None):
    q = df.copy()

    if scenario is not None and "scenario" in q.columns:
        q = q[q["scenario"] == scenario]
    if protocol is not None and "protocol" in q.columns:
        q = q[q["protocol"] == protocol]
    if metric is not None and "metric" in q.columns:
        q = q[q["metric"] == metric]
    if threshold is not None and "thresholdConfig" in q.columns:
        q = q[q["thresholdConfig"] == threshold]
    if topology is not None and "topology" in q.columns:
        q = q[q["topology"] == topology]
    if node_count is not None and "nodeCount" in q.columns:
        q = q[q["nodeCount"] == node_count]

    if len(q) != 1:
        raise ValueError(
            "Expected exactly one descriptive row, found "
            f"{len(q)} for filters scenario={scenario}, protocol={protocol}, "
            f"metric={metric}, threshold={threshold}, topology={topology}, "
            f"nodeCount={node_count}"
        )
    return q.iloc[0]


def render_table_png(
    df: pd.DataFrame,
    path: Path,
    title: str,
    note: str | None = None,
) -> None:
    """
    Render a publication-ready PNG table using Matplotlib.
    CSV remains the machine-readable source; PNG is the visual export.
    """

    # Dynamic width/height based on table dimensions.
    n_rows, n_cols = df.shape

    col_width_chars = []
    for col in df.columns:
        values = [str(col)] + [str(v) for v in df[col].tolist()]
        col_width_chars.append(
            min(
                max(len(v) for v in values),
                34
            )
        )

    width = max(
        9.0,
        min(
            20.0,
            0.11 * sum(col_width_chars) + 1.5
        )
    )

    height = max(
        2.8,
        min(
            18.0,
            0.42 * (n_rows + 1) + 1.8
        )
    )

    fig, ax = plt.subplots(
        figsize=(width, height)
    )

    ax.axis("off")

    table = ax.table(
        cellText=df.astype(str).values,
        colLabels=[str(c) for c in df.columns],
        loc="center",
        cellLoc="center",
        colLoc="center",
    )

    table.auto_set_font_size(False)
    table.set_fontsize(8.5)
    table.scale(1.0, 1.35)

    # Clean academic styling.
    for (row, col), cell in table.get_celld().items():
        cell.set_edgecolor("#B8B8B8")
        cell.set_linewidth(0.6)

        if row == 0:
            cell.set_facecolor("#EDEDED")
            cell.set_text_props(
                weight="bold",
                color="#222222"
            )
        else:
            cell.set_facecolor(
                "#FFFFFF" if row % 2 else "#F8F8F8"
            )
            cell.set_text_props(
                color="#222222"
            )

    # Let Matplotlib estimate useful widths from contents.
    try:
        table.auto_set_column_width(
            col=list(range(n_cols))
        )
    except Exception:
        pass

    fig.suptitle(
        title,
        fontsize=12,
        fontweight="bold",
        y=0.985,
    )

    if note:
        fig.text(
            0.5,
            0.018,
            f"Note. {note}",
            ha="center",
            va="bottom",
            fontsize=8,
            wrap=True,
        )

    fig.savefig(
        path,
        dpi=300,
        bbox_inches="tight",
        pad_inches=0.18,
        facecolor="white",
    )

    plt.close(fig)


def save_table(
    df: pd.DataFrame,
    stem: str,
    title: str,
    note: str | None = None,
):
    OUT.mkdir(
        parents=True,
        exist_ok=True,
    )

    # Keep CSV for exact numeric data.
    df.to_csv(
        OUT / f"{stem}.csv",
        index=False,
    )

    # Visual table for paper/review.
    render_table_png(
        df=df,
        path=OUT / f"{stem}.png",
        title=title,
        note=note,
    )

def table_t01_design():
    rows = [
        ["S01", "Standard", "Reliability line", "B0/MM/2RH/CARBLE", 30,
         "p=.80 line; 100 packets", "PDR, latency, attempts, retransmissions"],
        ["S02", "Standard", "Topology failure", "B0/MM/2RH/CARBLE", 30,
         "Dual path; deterministic links; one topology failure", "PDR, latency, topology behavior"],
        ["S03", "Standard", "Congestion", "B0/MM/2RH/CARBLE", 30,
         "Static healthy links; burst traffic; queue capacity 5", "PDR, latency, queue/resource cost"],
        ["S04", "Standard", "Reliability + topology", "B0/MM/2RH/CARBLE", 30,
         "Dual path; p=.80; topology change", "PDR, latency, attempts"],
        ["S05", "Standard", "Combined stress", "B0/MM/2RH/CARBLE", 30,
         "Reliability + topology + burst congestion", "PDR, latency, attempts"],
        ["PF-A", "Controlled", "M1 validation", "B0/MM/2RH/CARBLE", 30,
         "Gradual degradation; predominantly M1", "Stage value before deeper fallback"],
        ["PF-B1", "Controlled", "M2 validation", "B0/MM/2RH/CARBLE", 30,
         "Dual-path degradation", "M2 value vs binary 2RH"],
        ["PF-B2", "Controlled", "M3 validation", "B0/MM/2RH/CARBLE", 30,
         "PF-B1 + moderate instability evidence", "M3 value vs binary 2RH"],
        ["PF-C", "Controlled", "LOW validation", "B0/MM/2RH/CARBLE", 30,
         "Severe degradation; instability evidence=5", "LOW bounded fallback"],
        ["FULL", "Lifecycle", "HIGH→M1→M2→M3→LOW", "B0/MM/2RH/CARBLE", 30,
         "Seven reliability phases; staged instability at t=600/750", "End-to-end trade-off and transition timing"],
        ["THRESH", "Robustness", "Threshold sensitivity", "CARBLE only", 30,
         "Early / nominal / late threshold sets", "Performance sensitivity and stage reach"],
        ["SCALE", "Compute", "Routing/controller scalability", "B0/MM/2RH/CARBLE", 30,
         "10/25/50/100/200 nodes; sparse + moderate", "Median decision latency"],
    ]
    return pd.DataFrame(
        rows,
        columns=[
            "ID", "Family", "Purpose", "Protocols", "Seeds",
            "Condition", "Primary use"
        ],
    )


def table_t02_standard(main_desc, main_resource):
    rows = []
    for scenario in MAIN_SCENARIOS:
        for protocol in PROTOCOLS:
            pdr = get_desc_row(main_desc, scenario=scenario, protocol=protocol, metric="pdr")
            lat = get_desc_row(main_desc, scenario=scenario, protocol=protocol, metric="conditionalMeanLatency")
            att = get_desc_row(main_resource, scenario=scenario, protocol=protocol, metric="attemptsPerDelivered")
            rows.append([
                scenario,
                protocol,
                fmt_mean_ci(pdr, percent=True, digits=2),
                fmt_mean_ci(lat, digits=2),
                fmt_mean_ci(att, digits=3),
            ])
    return pd.DataFrame(
        rows,
        columns=[
            "Scenario", "Protocol", "PDR % [95% BCa CI]",
            "Conditional latency [95% BCa CI]",
            "Attempts/delivered [95% BCa CI]"
        ],
    )


def table_t03_pref(pref_desc):
    rows = []
    for scenario in PREF_SCENARIOS:
        for protocol in PROTOCOLS:
            pdr = get_desc_row(pref_desc, scenario=scenario, protocol=protocol, metric="pdr")
            lat = get_desc_row(pref_desc, scenario=scenario, protocol=protocol, metric="conditionalMeanLatency")
            att = get_desc_row(pref_desc, scenario=scenario, protocol=protocol, metric="attemptsPerDelivered")
            rows.append([
                DISPLAY_PREF[scenario],
                protocol,
                fmt_mean_ci(pdr, percent=True, digits=2),
                fmt_mean_ci(lat, digits=2),
                fmt_mean_ci(att, digits=3),
            ])
    return pd.DataFrame(
        rows,
        columns=[
            "Condition", "Protocol", "PDR % [95% BCa CI]",
            "Conditional latency [95% BCa CI]",
            "Attempts/delivered [95% BCa CI]"
        ],
    )


def table_t04_full(full_desc):
    rows = []
    for protocol in PROTOCOLS:
        pdr = get_desc_row(full_desc, scenario="FULL_HIGH_M1_M2_M3_LOW", protocol=protocol, metric="pdr")
        lat = get_desc_row(full_desc, scenario="FULL_HIGH_M1_M2_M3_LOW", protocol=protocol, metric="conditionalMeanLatency")
        att = get_desc_row(full_desc, scenario="FULL_HIGH_M1_M2_M3_LOW", protocol=protocol, metric="attemptsPerDelivered")
        ret = get_desc_row(full_desc, scenario="FULL_HIGH_M1_M2_M3_LOW", protocol=protocol, metric="retransmissionsPerDelivered")
        rows.append([
            protocol,
            fmt_mean_ci(pdr, percent=True, digits=2),
            fmt_mean_ci(lat, digits=2),
            fmt_mean_ci(att, digits=3),
            fmt_mean_ci(ret, digits=3),
        ])
    return pd.DataFrame(
        rows,
        columns=[
            "Protocol", "PDR % [95% BCa CI]",
            "Conditional latency [95% BCa CI]",
            "Attempts/delivered [95% BCa CI]",
            "Retransmissions/delivered [95% BCa CI]"
        ],
    )


def _pair_row(pair_df, scenario, metric):
    q = pair_df[
        (pair_df["scenario"] == scenario)
        & (pair_df["metric"] == metric)
        & (pair_df["comparison"] == "CARBLE_vs_2RH")
    ]
    if len(q) != 1:
        raise ValueError(
            f"Expected CARBLE_vs_2RH row for scenario={scenario}, metric={metric}; found {len(q)}"
        )
    return q.iloc[0]


def table_t05_key_contrasts(pref_pair, full_pair):
    rows = []

    for scenario in PREF_SCENARIOS:
        pdr = _pair_row(pref_pair, scenario, "pdr")
        lat = _pair_row(pref_pair, scenario, "conditionalMeanLatency")
        att = _pair_row(pref_pair, scenario, "attemptsPerDelivered")

        rows.append([
            DISPLAY_PREF[scenario],
            f"{float(pdr['pdrDifferencePercentagePoints']):+.2f}",
            f"[{float(pdr['differenceCi95Low_BCa'])*100:+.2f}, {float(pdr['differenceCi95High_BCa'])*100:+.2f}]",
            f"{float(lat['relativeMeanChangePercent']):+.2f}",
            f"{float(att['relativeMeanChangePercent']):+.2f}",
            fmt_p(pdr["wilcoxonP_Holm"]),
            fmt_num(pdr["rankBiserial"], 3),
        ])

    scenario = "FULL_HIGH_M1_M2_M3_LOW"
    pdr = _pair_row(full_pair, scenario, "pdr")
    lat = _pair_row(full_pair, scenario, "conditionalMeanLatency")
    att = _pair_row(full_pair, scenario, "attemptsPerDelivered")

    rows.append([
        "Full transition",
        f"{float(pdr['pdrDifferencePercentagePoints']):+.2f}",
        f"[{float(pdr['differenceCi95Low_BCa'])*100:+.2f}, {float(pdr['differenceCi95High_BCa'])*100:+.2f}]",
        f"{float(lat['relativeMeanChangePercent']):+.2f}",
        f"{float(att['relativeMeanChangePercent']):+.2f}",
        fmt_p(pdr["wilcoxonP_Holm"]),
        fmt_num(pdr["rankBiserial"], 3),
    ])

    return pd.DataFrame(
        rows,
        columns=[
            "Condition",
            "ΔPDR CARBLE−2RH (pp)",
            "95% BCa CI for ΔPDR (pp)",
            "Δ conditional latency (%)",
            "Δ attempts/delivered (%)",
            "Holm p (PDR)",
            "Rank-biserial (PDR)",
        ],
    )


def table_t06_phase(phase_summary):
    metrics = {
        "highShare": "HIGH %",
        "m1Share": "M1 %",
        "m2Share": "M2 %",
        "m3Share": "M3 %",
        "lowShare": "LOW %",
        "meanEscalationLevel": "Escalation index",
    }

    phase_meta = {
        1: (0.90, 0),
        2: (0.75, 0),
        3: (0.60, 0),
        4: (0.45, 0),
        5: (0.30, 2),
        6: (0.15, 5),
        7: (0.05, 5),
    }

    rows = []
    for phase in range(1, 8):
        values = {}
        for metric, label in metrics.items():
            q = phase_summary[
                (phase_summary["phaseIndex"] == phase)
                & (phase_summary["metric"] == metric)
            ]
            if len(q) != 1:
                raise ValueError(f"Missing phase summary: P{phase}, {metric}")
            v = float(q.iloc[0]["mean"])
            values[label] = v * 100 if metric.endswith("Share") else v

        p, inst = phase_meta[phase]
        rows.append([
            f"P{phase}", f"{p:.2f}", inst,
            f"{values['HIGH %']:.2f}",
            f"{values['M1 %']:.2f}",
            f"{values['M2 %']:.2f}",
            f"{values['M3 %']:.2f}",
            f"{values['LOW %']:.2f}",
            f"{values['Escalation index']:.3f}",
        ])

    return pd.DataFrame(
        rows,
        columns=[
            "Phase", "Link success p", "Instability evidence",
            "HIGH %", "M1 %", "M2 %", "M3 %", "LOW %",
            "Mean escalation index"
        ],
    )


def table_t07_threshold(threshold_desc, stage_reach):
    rows = []

    for config in THRESHOLD_ORDER:
        pdr = get_desc_row(threshold_desc, threshold=config, metric="pdr")
        lat = get_desc_row(threshold_desc, threshold=config, metric="conditionalMeanLatency")
        att = get_desc_row(threshold_desc, threshold=config, metric="attemptsPerDelivered")

        def reach(metric):
            q = stage_reach[
                (stage_reach["thresholdConfig"] == config)
                & (stage_reach["metric"] == metric)
            ]
            if len(q) != 1:
                return "—"
            return f"{int(q.iloc[0]['countTrue'])}/30"

        rows.append([
            config,
            {
                "EARLY": ".80/.70/.60/.50",
                "NOMINAL": ".75/.65/.55/.45",
                "LATE": ".70/.60/.50/.40",
            }[config],
            fmt_mean_ci(pdr, percent=True, digits=2),
            fmt_mean_ci(lat, digits=3),
            fmt_mean_ci(att, digits=3),
            reach("hasAllStages"),
            reach("strictFirstEntryOrder"),
            reach("firstLowTime_observed"),
        ])

    return pd.DataFrame(
        rows,
        columns=[
            "Config", "Thresholds", "PDR % [95% BCa CI]",
            "Conditional latency [95% BCa CI]",
            "Attempts/delivered [95% BCa CI]",
            "All stages reached", "Strict order", "LOW reached"
        ],
    )


def table_t08_scalability(scale_desc, scale_pair):
    rows = []

    for topology in ["SPARSE", "MODERATE"]:
        for n in [10, 25, 50, 100, 200]:
            vals = {}
            for protocol in ["B0", "MM", "TWO_RH", "CARBLE"]:
                row = get_desc_row(
                    scale_desc,
                    topology=topology,
                    node_count=n,
                    protocol=protocol,
                    metric="medianLatencyUs",
                )
                vals[protocol] = float(row["mean"])

            pair = scale_pair[
                (scale_pair["topology"] == topology)
                & (scale_pair["nodeCount"] == n)
                & (scale_pair["metric"] == "medianLatencyUs")
                & (scale_pair["comparison"] == "CARBLE_vs_TWO_RH")
            ]
            if len(pair) != 1:
                raise ValueError(f"Missing scalability pair row {topology} N={n}")
            pair = pair.iloc[0]

            rows.append([
                "Sparse" if topology == "SPARSE" else "Moderate",
                n,
                f"{vals['B0']:.2f}",
                f"{vals['MM']:.2f}",
                f"{vals['TWO_RH']:.2f}",
                f"{vals['CARBLE']:.2f}",
                f"{float(pair['relativeOverheadPercentFromMeanRatio']):+.2f}",
                fmt_p(pair["wilcoxonP_Holm"]),
            ])

    return pd.DataFrame(
        rows,
        columns=[
            "Topology",
            "Nodes",
            "B0 mean seed-median µs",
            "MM mean seed-median µs",
            "2RH mean seed-median µs",
            "CARBLE mean seed-median µs",
            "CARBLE overhead vs 2RH (%)",
            "Holm p"
        ],
    )


def table_t09_relay(full_resource):
    metrics = [
        ("totalRelayAttempts", "Total relay attempts"),
        ("maxRelayAttemptShare", "Max relay-attempt share"),
        ("jainRelayAttemptFairness", "Jain relay-attempt index"),
    ]

    rows = []
    for protocol in PROTOCOLS:
        out = [protocol]
        for metric, _ in metrics:
            row = get_desc_row(
                full_resource,
                scenario="FULL_HIGH_M1_M2_M3_LOW",
                protocol=protocol,
                metric=metric,
            )
            if metric == "maxRelayAttemptShare":
                out.append(fmt_mean_ci(row, percent=True, digits=2))
            else:
                out.append(fmt_mean_ci(row, digits=3))
        rows.append(out)

    return pd.DataFrame(
        rows,
        columns=[
            "Protocol",
            "Total relay attempts [95% BCa CI]",
            "Max relay-attempt share % [95% BCa CI]",
            "Jain relay-attempt index [95% BCa CI]",
        ],
    )


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    main_desc = read("main_descriptive.csv")
    main_resource = read("main_resource_descriptive.csv")
    pref_desc = read("prefailure_descriptive.csv")
    full_desc = read("full_transition_descriptive.csv")

    pref_pair = read("prefailure_pairwise_carble.csv")
    full_pair = read("full_transition_pairwise_carble.csv")

    phase_summary = read("carble_phase_decision_summary.csv")

    threshold_desc = read("threshold_robustness_descriptive.csv")
    threshold_reach = read("threshold_robustness_stage_reach.csv")

    scale_desc = read("scalability_descriptive.csv")
    scale_pair = read("scalability_pairwise_carble.csv")

    full_resource = read("full_resource_descriptive.csv")

    tables = [
        ("T01_experiment_design", "Table I. Simulation campaign", table_t01_design(),
         "All primary protocol-comparison experiments use 30 independent or paired seeds. Calibration runs are excluded from inference."),
        ("T02_standard_performance", "Table II. Standard-condition performance", table_t02_standard(main_desc, main_resource),
         "Values are run-level means with 95% BCa bootstrap confidence intervals. Conditional latency is computed only among delivered packets."),
        ("T03_controlled_regime_validation", "Table III. Controlled M1/M2/M3/LOW validation", table_t03_pref(pref_desc),
         "PF-A, PF-B1, PF-B2, and PF-C are separate controlled experiments, not consecutive phases."),
        ("T04_full_transition", "Table IV. Full-transition protocol outcomes", table_t04_full(full_desc),
         "Attempts and retransmissions are simulation resource proxies, not measured energy."),
        ("T05_carble_vs_2rh", "Table V. Key architectural contrast: CARBLE vs 2RH", table_t05_key_contrasts(pref_pair, full_pair),
         "Positive ΔPDR favors CARBLE. Negative latency change means lower conditional latency for CARBLE. Holm-adjusted p-values are shown for PDR."),
        ("T06_phase_progression", "Table VI. CARBLE phase-wise controller decisions", table_t06_phase(phase_summary),
         "Shares refer to controller evaluation/decision events, not wall-clock time occupancy. "
         "The escalation index maps HIGH=0, M1=1, M2=2, M3=3, and LOW=4; "
         "the reported value is the mean controller escalation level within the phase."),
        ("T07_threshold_robustness", "Table VII. Threshold robustness", table_t07_threshold(threshold_desc, threshold_reach),
         "Nominal thresholds remain CARBLE-v1.0; early/late configurations are robustness perturbations, not tuned replacements."),
        ("T08_scalability", "Table VIII. Routing/controller computational scalability", table_t08_scalability(scale_desc, scale_pair),
         "Each reported protocol latency is the mean across 30 graph seeds of the within-seed median over 500 measured decisions. Timings are JVM-side synchronous computation on the benchmark host."),
        ("T09_relay_burden", "Table IX. Full-transition relay burden", table_t09_relay(full_resource),
         "Jain values describe relay-attempt distribution in an asymmetric topology; they do not imply equal load is the optimal objective."),
    ]

    manifest_rows = []

    for stem, title, df, note in tables:
        save_table(
            df,
            stem,
            title,
            note,
        )

        manifest_rows.append(
            {
                "table": stem,
                "title": title,
                "csv": f"{stem}.csv",
                "png": f"{stem}.png",
                "note": note or "",
            }
        )

    manifest = pd.DataFrame(
        manifest_rows
    )

    manifest_path = (
        OUT
        / "table_manifest.csv"
    )

    manifest.to_csv(
        manifest_path,
        index=False,
    )

    print("============================================================")
    print("FINAL RESEARCH TABLES COMPLETE")
    print(f"Output: {OUT.resolve()}")
    print(f"Tables generated: {len(tables)}")
    print(f"Manifest: {manifest_path.resolve()}")
    print("============================================================")


if __name__ == "__main__":
    main()
