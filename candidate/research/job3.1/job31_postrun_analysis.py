#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean

SENDERS = ("PYTKR001", "QUOTE001", "HEDGE001")
BBO_SUBJECT = "ex.bbo.AAH6"
RISK_SUBJECT = "desk.risk.AAH6"
HORIZONS_MS = (50, 250, 1000, 5000)


def parse_args():
    p = argparse.ArgumentParser(
        description="Aggregate Job 3.1A Seed 1-3 evidence and profitability attribution."
    )
    p.add_argument(
        "--evidence-root",
        type=Path,
        default=Path(r"D:\TRV_General_AI_Challenge_2026_evidence\job3.1A"),
    )
    p.add_argument(
        "--candidate",
        type=Path,
        default=Path(r"D:\TRV_General_AI_Challenge_2026\candidate"),
    )
    p.add_argument("--out", type=Path, default=None)
    return p.parse_args()


def load_ndjson(path: Path):
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            text = line.strip()
            if not text:
                continue
            r = json.loads(text)
            r["_line"] = line_no
            rows.append(r)
    return rows


def control_mono(rows, event):
    for r in rows:
        if r.get("kind") == "CONTROL" and r.get("event") == event:
            return int(r["recv_mono_ns"])
    raise RuntimeError(f"Missing CONTROL {event}")


def public_sender(order_id):
    if not order_id or ":" not in str(order_id):
        return None
    return str(order_id).split(":", 1)[0]


def md_sender(subject):
    prefix = "ex.md.AAH6."
    if str(subject).startswith(prefix):
        return str(subject)[len(prefix):]
    return None


def parse_bbo(row):
    p = str(row.get("payload_text", "")).split()
    if len(p) != 6:
        return None
    try:
        bid = None if p[2] == "-" else int(p[2])
        ask = None if p[4] == "-" else int(p[4])
        bv = int(p[3])
        av = int(p[5])
    except ValueError:
        return None
    if bid is None or ask is None or bv <= 0 or av <= 0 or bid > ask:
        return None
    return {
        "mono": int(row["recv_mono_ns"]),
        "mid": (bid + ask) / 2.0,
        "bid": bid,
        "ask": ask,
    }


def previous_bbo(bbos, times, mono):
    i = bisect.bisect_right(times, mono) - 1
    return bbos[i] if i >= 0 else None


def future_bbo(bbos, times, mono):
    i = bisect.bisect_left(times, mono)
    return bbos[i] if i < len(bbos) else None


def signed_edge(side, price, mid):
    # Positive = favorable execution versus reference mid.
    return (mid - price) if side == "B" else (price - mid)


def fmt(v, digits=2):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:.{digits}f}"
    return str(v)


def parse_execution(accounting_cls, row):
    sender = md_sender(row.get("subject"))
    if sender not in SENDERS:
        return None

    ev = accounting_cls.parse_event(row.get("payload_text", ""))
    if not ev or ev["eventType"] not in {"T", "E"}:
        return None

    side = accounting_cls.effective_side(ev)
    incoming_sender = public_sender(ev["incomingOrderId"])
    resting_sender = public_sender(ev["restingOrderId"])

    # Subject/feed identity validation, same semantics as production accounting.
    if ev["eventType"] == "T" and incoming_sender != sender:
        raise ValueError(
            f"T sender mismatch line={row['_line']} subject={sender} incoming={incoming_sender}"
        )
    if ev["eventType"] == "E" and resting_sender != sender:
        raise ValueError(
            f"E sender mismatch line={row['_line']} subject={sender} resting={resting_sender}"
        )

    qty = int(ev["qty"])
    price = int(ev["price"])

    sender_key = (
        sender,
        ev["eventType"],
        ev["eventTimestamp"],
        ev["matchId"],
        ev["incomingOrderId"],
        ev["restingOrderId"],
        qty,
        price,
        ev["aggressorSide"],
    )

    match_key = (
        ev["eventTimestamp"],
        ev["matchId"],
        ev["incomingOrderId"],
        ev["restingOrderId"],
        qty,
        price,
        ev["aggressorSide"],
    )

    internal = incoming_sender in SENDERS and resting_sender in SENDERS

    return {
        "sender": sender,
        "event_type": ev["eventType"],
        "side": side,
        "qty": qty,
        "price": price,
        "mono": int(row["recv_mono_ns"]),
        "line": row["_line"],
        "sender_key": sender_key,
        "match_key": match_key,
        "incoming_sender": incoming_sender,
        "resting_sender": resting_sender,
        "internal": internal,
    }


def run_analysis(run_dir: Path, accounting_cls):
    raw_path = run_dir / "raw.ndjson"
    summary_path = run_dir / "analysis.json"
    if not raw_path.exists() or not summary_path.exists():
        return None

    rows = load_ndjson(raw_path)
    prior = json.loads(summary_path.read_text(encoding="utf-8"))
    start = control_mono(rows, "MEASUREMENT_START")
    end = control_mono(rows, "MEASUREMENT_END")

    bbos = []
    for r in rows:
        if r.get("subject") == BBO_SUBJECT:
            b = parse_bbo(r)
            if b:
                bbos.append(b)
    bbos.sort(key=lambda x: x["mono"])
    bbo_times = [b["mono"] for b in bbos]

    executions = []
    seen_sender_keys = set()
    parse_errors = []

    for r in rows:
        if md_sender(r.get("subject")) not in SENDERS:
            continue
        try:
            ex = parse_execution(accounting_cls, r)
            if not ex:
                continue
            if ex["sender_key"] in seen_sender_keys:
                continue
            seen_sender_keys.add(ex["sender_key"])
            if start <= ex["mono"] <= end:
                executions.append(ex)
        except Exception as exc:
            parse_errors.append({"line": r["_line"], "error": str(exc)})

    seat = {
        s: {
            "exec_count": 0,
            "qty": 0,
            "T": 0,
            "E": 0,
            "internal_exec_count": 0,
            "internal_qty": 0,
            "external_exec_count": 0,
            "external_qty": 0,
            "pre_mid_edge_total": 0.0,
            "pre_mid_edge_qty": 0,
            "markout": {h: 0.0 for h in HORIZONS_MS},
            "markout_qty": {h: 0 for h in HORIZONS_MS},
        }
        for s in SENDERS
    }

    unique_matches = {}
    for ex in executions:
        unique_matches.setdefault(ex["match_key"], ex)

        m = seat[ex["sender"]]
        m["exec_count"] += 1
        m["qty"] += ex["qty"]
        m[ex["event_type"]] += 1

        if ex["internal"]:
            m["internal_exec_count"] += 1
            m["internal_qty"] += ex["qty"]
            continue

        m["external_exec_count"] += 1
        m["external_qty"] += ex["qty"]

        prev = previous_bbo(bbos, bbo_times, ex["mono"])
        if prev:
            edge = signed_edge(ex["side"], ex["price"], prev["mid"])
            m["pre_mid_edge_total"] += edge * ex["qty"]
            m["pre_mid_edge_qty"] += ex["qty"]

        for h in HORIZONS_MS:
            future = future_bbo(bbos, bbo_times, ex["mono"] + h * 1_000_000)
            if future:
                edge = signed_edge(ex["side"], ex["price"], future["mid"])
                m["markout"][h] += edge * ex["qty"]
                m["markout_qty"][h] += ex["qty"]

    internal_matches = [x for x in unique_matches.values() if x["internal"]]
    external_matches = [x for x in unique_matches.values() if not x["internal"]]

    # Quoter accepted lifecycle counts on authoritative MD.
    quote_events = Counter()
    for r in rows:
        if not (start <= int(r.get("recv_mono_ns", -1)) <= end):
            continue
        if r.get("subject") != "ex.md.AAH6.QUOTE001":
            continue
        p = str(r.get("payload_text", "")).split()
        if len(p) >= 2:
            quote_events[p[1]] += 1

    seed = None
    seed_file = run_dir / "sim-seed.txt"
    if seed_file.exists():
        text = seed_file.read_text(encoding="utf-8", errors="replace").strip()
        if "=" in text:
            seed = text.split("=", 1)[1].strip()

    hard_gate = None
    hard_file = run_dir / "hard-gate-scan.txt"
    if hard_file.exists():
        hard_gate = hard_file.read_text(encoding="utf-8", errors="replace").strip()

    market = prior.get("pnl_proxy", {})
    start_mid = market.get("start_mid")
    end_mid = market.get("end_mid")
    market_return_pct = None
    if start_mid not in (None, 0) and end_mid is not None:
        market_return_pct = (end_mid / start_mid - 1.0) * 100.0

    for s in SENDERS:
        m = seat[s]
        m["pre_mid_edge_per_unit"] = (
            m["pre_mid_edge_total"] / m["pre_mid_edge_qty"]
            if m["pre_mid_edge_qty"]
            else None
        )
        for h in HORIZONS_MS:
            m[f"markout_{h}ms_per_unit"] = (
                m["markout"][h] / m["markout_qty"][h]
                if m["markout_qty"][h]
                else None
            )

    return {
        "run": run_dir.name,
        "seed": seed,
        "duration_s": prior.get("duration_s"),
        "market_return_pct": market_return_pct,
        "start_mid": start_mid,
        "end_mid": end_mid,
        "desk_pnl_proxy": market.get("window_pnl_proxy"),
        "per_sender_pnl_proxy": market.get("per_sender_pnl_proxy", {}),
        "risk_states": prior.get("risk", {}).get("states", {}),
        "auth_max_abs": prior.get("execution", {}).get("authoritative_max_abs_net"),
        "hard_ms": prior.get("execution", {}).get("total_hard_exposure_ms"),
        "false_safe_candidates": prior.get("alignment", {}).get("false_safe_candidates"),
        "divergence_candidates": prior.get("alignment", {}).get("position_divergence_candidates"),
        "unknown_or_stale_add_candidates": prior.get("exposure_dispatch", {}).get(
            "unknown_or_stale_probe_order_candidates"
        ),
        "execution_parse_errors": parse_errors,
        "unique_match_count": len(unique_matches),
        "internal_match_count": len(internal_matches),
        "internal_match_qty": sum(x["qty"] for x in internal_matches),
        "external_match_count": len(external_matches),
        "quote_md_events": dict(quote_events),
        "seat": seat,
        "hard_gate_scan": hard_gate,
    }


def main():
    args = parse_args()
    candidate = args.candidate.resolve()
    root = args.evidence_root.resolve()
    sys.path.insert(0, str(candidate))

    from hedger.accounting import DeskPositionAccounting

    run_dirs = sorted(p for p in root.glob("run-*-seed-*") if p.is_dir())
    runs = []

    for run_dir in run_dirs:
        result = run_analysis(run_dir, DeskPositionAccounting)
        if result:
            runs.append(result)

    if not runs:
        raise SystemExit(f"No completed run evidence found under {root}")

    print("JOB31_POSTRUN_ANALYSIS")
    print()
    print("RUN_SUMMARY")
    print(
        "run seed market_return_pct desk_pnl taker_pnl quoter_pnl hedger_pnl "
        "max_abs hard_ms divergence false_safe"
    )

    for r in runs:
        p = r["per_sender_pnl_proxy"]
        print(
            f"{r['run']} {r['seed']} "
            f"{fmt(r['market_return_pct'])} "
            f"{fmt(r['desk_pnl_proxy'])} "
            f"{fmt(p.get('PYTKR001'))} "
            f"{fmt(p.get('QUOTE001'))} "
            f"{fmt(p.get('HEDGE001'))} "
            f"{r['auth_max_abs']} "
            f"{fmt(r['hard_ms'], 3)} "
            f"{r['divergence_candidates']} "
            f"{r['false_safe_candidates']}"
        )

    totals = defaultdict(float)
    seat_totals = defaultdict(float)
    for r in runs:
        if r["desk_pnl_proxy"] is not None:
            totals["desk"] += r["desk_pnl_proxy"]
        for s, v in r["per_sender_pnl_proxy"].items():
            if v is not None:
                seat_totals[s] += v

    print()
    print("AGGREGATE_PNL_PROXY")
    print(f"desk_total={fmt(totals['desk'])} desk_mean={fmt(totals['desk']/len(runs))}")
    for s in SENDERS:
        print(
            f"{s}_total={fmt(seat_totals[s])} "
            f"{s}_mean={fmt(seat_totals[s]/len(runs))}"
        )

    print()
    print("EXECUTION_ATTRIBUTION_EXTERNAL_ONLY")
    for r in runs:
        print(f"RUN {r['run']} seed={r['seed']}")
        print(
            f"  unique_matches={r['unique_match_count']} "
            f"internal_matches={r['internal_match_count']} "
            f"internal_qty={r['internal_match_qty']} "
            f"external_matches={r['external_match_count']}"
        )
        q = r["quote_md_events"]
        print(
            f"  quoter_md A={q.get('A',0)} E={q.get('E',0)} "
            f"C={q.get('C',0)} T={q.get('T',0)}"
        )
        for s in SENDERS:
            m = r["seat"][s]
            print(
                f"  {s}: exec={m['exec_count']} qty={m['qty']} "
                f"T={m['T']} E={m['E']} "
                f"internal_exec={m['internal_exec_count']} "
                f"external_exec={m['external_exec_count']} "
                f"external_qty={m['external_qty']} "
                f"edge0_per_unit={fmt(m['pre_mid_edge_per_unit'],3)} "
                f"mark50={fmt(m['markout_50ms_per_unit'],3)} "
                f"mark250={fmt(m['markout_250ms_per_unit'],3)} "
                f"mark1000={fmt(m['markout_1000ms_per_unit'],3)} "
                f"mark5000={fmt(m['markout_5000ms_per_unit'],3)}"
            )

    print()
    print("CROSS_RUN_SEAT_MARKOUT_MEANS")
    for s in SENDERS:
        for metric in (
            "pre_mid_edge_per_unit",
            "markout_50ms_per_unit",
            "markout_250ms_per_unit",
            "markout_1000ms_per_unit",
            "markout_5000ms_per_unit",
        ):
            vals = [r["seat"][s][metric] for r in runs if r["seat"][s][metric] is not None]
            value = mean(vals) if vals else None
            print(f"{s} {metric}={fmt(value,3)}")

    print()
    print("HARD_GATE_FILES")
    for r in runs:
        scan = r["hard_gate_scan"]
        if scan is None:
            status = "NOT_SAVED"
        elif scan.strip() == "NO_MATCHES":
            status = "NO_MATCHES"
        else:
            status = "HAS_MATCHES"
        print(f"{r['run']} hard_gate_scan={status}")

    output = {
        "runs": runs,
        "aggregate": {
            "desk_total": totals["desk"],
            "desk_mean": totals["desk"] / len(runs),
            "seat_total": dict(seat_totals),
        },
    }

    out = args.out or (root / "postrun-analysis.json")
    out.write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
    print()
    print(f"json_out={out}")
    print("NO_TUNING_PERFORMED")


if __name__ == "__main__":
    main()
