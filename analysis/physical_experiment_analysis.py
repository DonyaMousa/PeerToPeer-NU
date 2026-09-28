#!/usr/bin/env python3
"""Peer2Peer NU v12 physical experiment analysis.

Usage:
    python physical_experiment_analysis.py <folder-with-exported-csvs> [output.csv]

The folder may contain one block CSV per phone. Rows are merged by shared
sessionId, so A/B/C/D files can be analyzed together without manual alignment.
"""

from __future__ import annotations

import sys
from pathlib import Path
import pandas as pd


def _clean(s: pd.Series) -> pd.Series:
    return s.fillna("").astype(str).str.strip()


def _first_per_message(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df
    sort_cols = [c for c in ["epochMs", "elapsedMs"] if c in df.columns]
    ordered = df.sort_values(sort_cols) if sort_cols else df
    return ordered.drop_duplicates("messageId", keep="first")


def _counts_text(series: pd.Series) -> str:
    values = _clean(series)
    values = values[values != ""]
    if values.empty:
        return ""
    counts = values.value_counts().sort_index()
    return "|".join(f"{k}:{int(v)}" for k, v in counts.items())


def load_folder(folder: Path) -> pd.DataFrame:
    files = sorted(folder.glob("*.csv"))
    if not files:
        raise SystemExit(f"No CSV files found in {folder}")
    frames = []
    for f in files:
        try:
            df = pd.read_csv(f)
        except Exception as exc:
            print(f"Skipping {f.name}: {exc}", file=sys.stderr)
            continue
        if "event" not in df.columns or "sessionId" not in df.columns:
            continue  # Ignore prior summary outputs in the same folder.
        df["sourceFile"] = f.name
        frames.append(df)
    if not frames:
        raise SystemExit("No readable CSV files")
    return pd.concat(frames, ignore_index=True, sort=False)


def summarize(data: pd.DataFrame) -> pd.DataFrame:
    required = {"sessionId", "event", "messageId", "protocol", "runLabel", "conditionLabel"}
    missing = required - set(data.columns)
    if missing:
        raise SystemExit(f"Missing required columns: {sorted(missing)}")

    data = data.copy()
    # v11 event IDs survive repeated exports of the same durable log.
    if "eventId" in data.columns:
        identified = data.eventId.notna() & (_clean(data.eventId) != "")
        data = pd.concat([data[identified].drop_duplicates("eventId"), data[~identified]], ignore_index=True)
    else:
        data = data.drop_duplicates([c for c in data.columns if c != "sourceFile"])
    for col in ["sessionId", "event", "messageId", "protocol", "runLabel", "conditionLabel",
                "packetType", "sourceNode", "currentNode", "nextHop", "backupHop", "stage", "route"]:
        if col not in data.columns:
            data[col] = ""
        data[col] = _clean(data[col])

    # The Kotlin enum stays TWO_RH on the wire for compatibility; research
    # exports and comparisons use the participant-facing name 2BRH.
    data["protocol"] = data["protocol"].replace({"TWO_RH": "2BRH", "2RH": "2BRH"})

    data = data[(data["sessionId"] != "") & (data["sessionId"] != "manual")]
    rows = []

    for session_id, g in data.groupby("sessionId", sort=False):
        created = g[g.event == "EXPERIMENT_PACKET_CREATED"]
        if created.empty:
            # Some folders may omit source A. Keep sessions only when source evidence exists.
            continue

        protocol = created.protocol.iloc[0]
        run_label = created.runLabel.iloc[0]
        condition = created.conditionLabel.iloc[0]
        source_node = created.currentNode.iloc[0]
        destination_values = _clean(created.get("destination", pd.Series(dtype=str)))
        destination = destination_values[destination_values != ""].iloc[0] if (destination_values != "").any() else ""

        sent_ids = set(created.messageId)
        delivered = g[(g.event == "EXPERIMENT_DELIVERED_AT_DESTINATION") & (g.messageId.isin(sent_ids))]
        ack = g[(g.event == "EXPERIMENT_END_TO_END_ACK") & (g.messageId.isin(sent_ids))]

        sent = len(sent_ids)
        delivered_n = delivered.messageId.nunique()
        late_acks = 0
        completion = g[(g.event == "FORMAL_RUN_COMPLETE") & (g.currentNode == source_node)]
        if not completion.empty and "elapsedMs" in completion.columns:
            cutoff = pd.to_numeric(completion.elapsedMs, errors="coerce").min()
            if pd.notna(cutoff) and "elapsedMs" in ack.columns:
                late = pd.to_numeric(ack.elapsedMs, errors="coerce") > cutoff
                late_acks = ack[late].messageId.nunique()
                ack = ack[~late]
        acked_n = ack.messageId.nunique()
        destination_observed = bool(((g.currentNode == destination) & (g.event == "RUN_NODE_PRESENT")).any())
        destination_observed = destination_observed or not delivered.empty
        pdr = delivered_n / sent if sent and destination_observed else float("nan")
        ack = _first_per_message(ack)

        latency = pd.to_numeric(ack.get("latencyMs", pd.Series(dtype=float)), errors="coerce").dropna()

        decisions = g[
            (g.event == "ROUTE_DECISION") &
            (g.packetType == "EXPERIMENT") &
            (g.sourceNode == source_node) &
            (g.currentNode == source_node) &
            (g.messageId.isin(sent_ids))
        ]
        first_decisions = _first_per_message(decisions)
        first_with_hop = _first_per_message(decisions[decisions.nextHop != ""])

        attempts = g[(g.event == "FORWARD_ATTEMPT") & (g.packetType == "EXPERIMENT") & (g.messageId.isin(sent_ids))]
        failures = g[(g.event.isin(["FORWARD_FAILURE", "FORWARD_REJECTED"])) &
                     (g.packetType == "EXPERIMENT") & (g.messageId.isin(sent_ids))]

        candidates = g[
            (g.event == "ROUTE_CANDIDATE") &
            (g.sourceNode == source_node) &
            (g.currentNode == source_node) &
            (g.messageId.isin(sent_ids))
        ].copy()

        candidate_costs = ""
        if not candidates.empty and "routeCost" in candidates.columns:
            candidates["routeCostNum"] = pd.to_numeric(candidates["routeCost"], errors="coerce")
            means = candidates.dropna(subset=["routeCostNum"]).groupby("nextHop")["routeCostNum"].mean().sort_index()
            candidate_costs = "|".join(f"{hop}:{cost:.4f}" for hop, cost in means.items() if hop)

        q_values = pd.to_numeric(first_decisions.get("confidence", pd.Series(dtype=float)), errors="coerce").dropna()
        backup_messages = decisions[decisions.backupHop != ""].messageId.nunique()

        rows.append({
            "sessionId": session_id,
            "runLabel": run_label,
            "condition": condition,
            "protocol": protocol,
            "sourceNode": source_node,
            "destination": destination,
            "sentPackets": sent,
            "deliveredPackets": delivered_n if destination_observed else float("nan"),
            "destinationEvidenceAvailable": destination_observed,
            "runComplete": bool((g.event == "FORMAL_RUN_COMPLETE").any()),
            "runAborted": bool(g.event.isin(["FORMAL_RUN_STOPPED", "FORMAL_RUN_ABORTED"]).any()),
            "PDR": pdr,
            "ackConfirmedRatio": acked_n / sent if sent else float("nan"),
            "ackedPackets": acked_n,
            "lateAckPackets": late_acks,
            "appBleAcceptedFragmentsAvailableNodes": int(g.event.isin(["BLE_FRAGMENT_WRITE", "BLE_FRAGMENT_INDICATION"]).sum()),
            "bleConnectionAttemptsAvailableNodes": int((g.event == "BLE_CONNECT_ATTEMPT").sum()),
            "bleSessionFailuresAvailableNodes": int((g.event == "BLE_SESSION_FAILURE").sum()),
            "medianAckLatencyMs": latency.median() if not latency.empty else float("nan"),
            "meanAckLatencyMs": latency.mean() if not latency.empty else float("nan"),
            "forwardAttemptsAllNodes": len(attempts),
            "forwardFailuresAllNodes": len(failures),
            "firstHopCounts": _counts_text(first_with_hop.nextHop),
            "firstStageCounts": _counts_text(first_decisions.stage),
            "backupDecisionMessages": backup_messages,
            "backupActivations": int(((g.event == "BACKUP_ACTIVATED") & g.messageId.isin(sent_ids)).sum()),
            "lowProbes": int(((g.event == "LOW_PROBE") & g.messageId.isin(sent_ids)).sum()),
            "carryDecisions": int(((g.event == "CARRY") & g.messageId.isin(sent_ids)).sum()),
            "destinationDuplicates": int(((g.event == "EXPERIMENT_DUPLICATE_AT_DESTINATION") & g.messageId.isin(sent_ids)).sum()),
            "meanSourceQ": q_values.mean() if not q_values.empty else float("nan"),
            "candidateMeanCosts": candidate_costs,
        })

    result = pd.DataFrame(rows)
    if not result.empty:
        result = result.sort_values(["condition", "runLabel", "protocol", "sessionId"]).reset_index(drop=True)
    return result


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python physical_experiment_analysis.py <csv-folder> [output.csv]")
    folder = Path(sys.argv[1])
    output = Path(sys.argv[2]) if len(sys.argv) >= 3 else folder / "physical_run_summary.csv"
    data = load_folder(folder)
    result = summarize(data)
    result.to_csv(output, index=False)
    print(f"Loaded {len(data):,} event rows")
    print(f"Summarized {len(result):,} formal runs")
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
