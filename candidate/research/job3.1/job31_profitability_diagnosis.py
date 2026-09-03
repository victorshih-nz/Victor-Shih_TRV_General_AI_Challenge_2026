#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import json
import sys
from collections import defaultdict
from pathlib import Path

SENDERS = ("PYTKR001", "QUOTE001", "HEDGE001")
HORIZONS_MS = (50, 250, 1000, 5000)


def args():
    p = argparse.ArgumentParser(description="Job 3.1B profitability diagnosis; read-only, no tuning.")
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
    p.add_argument("--json-out", type=Path, default=None)
    return p.parse_args()


def load_rows(path):
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            if not line.strip():
                continue
            r = json.loads(line)
            r["_line"] = n
            rows.append(r)
    return rows


def control(rows, name):
    for r in rows:
        if r.get("kind") == "CONTROL" and r.get("event") == name:
            return int(r["recv_mono_ns"])
    raise RuntimeError(f"missing CONTROL {name}")


def md_sender(subject):
    p = "ex.md.AAH6."
    s = str(subject or "")
    return s[len(p):] if s.startswith(p) else None


def public_sender(order_id):
    text = str(order_id or "")
    return text.split(":", 1)[0] if ":" in text else None


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
        "bid": bid,
        "ask": ask,
        "mid": (bid + ask) / 2.0,
        "spread": ask - bid,
    }


def prev_idx(times, t):
    return bisect.bisect_right(times, t) - 1


def next_idx(times, t):
    return bisect.bisect_left(times, t)


def side_sign(side):
    return 1 if side == "B" else -1


def weighted(values):
    qty = sum(q for _, q in values)
    return (sum(v * q for v, q in values) / qty) if qty else None


def f(v, n=3):
    return "n/a" if v is None else f"{v:.{n}f}"


def add_metric(bucket, key, value, qty):
    bucket[key].append((value, qty))


def parse_execution(Accounting, row):
    sender = md_sender(row.get("subject"))
    if sender not in SENDERS:
        return None
    ev = Accounting.parse_event(row.get("payload_text", ""))
    if not ev or ev["eventType"] not in {"T", "E"}:
        return None

    inc = public_sender(ev["incomingOrderId"])
    rest = public_sender(ev["restingOrderId"])
    if ev["eventType"] == "T" and inc != sender:
        raise ValueError(f"T sender mismatch line={row['_line']}")
    if ev["eventType"] == "E" and rest != sender:
        raise ValueError(f"E sender mismatch line={row['_line']}")

    side = Accounting.effective_side(ev)
    qty = int(ev["qty"])
    return {
        "sender": sender,
        "event_type": ev["eventType"],
        "side": side,
        "qty": qty,
        "price": int(ev["price"]),
        "mono": int(row["recv_mono_ns"]),
        "match_id": ev["matchId"],
        "event_ts": ev["eventTimestamp"],
        "incoming": ev["incomingOrderId"],
        "resting": ev["restingOrderId"],
        "internal": inc in SENDERS and rest in SENDERS,
        "key": (
            sender, ev["eventType"], ev["eventTimestamp"], ev["matchId"],
            ev["incomingOrderId"], ev["restingOrderId"], qty, int(ev["price"]),
            ev["aggressorSide"],
        ),
    }


def analyze_run(run_dir, Accounting):
    rows = load_rows(run_dir / "raw.ndjson")
    summary = json.loads((run_dir / "analysis.json").read_text(encoding="utf-8"))
    start = control(rows, "MEASUREMENT_START")
    end = control(rows, "MEASUREMENT_END")

    bbos = []
    for r in rows:
        if r.get("subject") == "ex.bbo.AAH6":
            b = parse_bbo(r)
            if b:
                bbos.append(b)
    bbos.sort(key=lambda x: x["mono"])
    times = [b["mono"] for b in bbos]

    seat = {s: defaultdict(list) for s in SENDERS}
    side_counts = {s: defaultdict(lambda: {"count": 0, "qty": 0}) for s in SENDERS}
    intervals = {s: [] for s in SENDERS}
    last_exec_mono = {s: None for s in SENDERS}
    seen = set()
    parse_errors = []
    external_execs = {s: 0 for s in SENDERS}
    internal_execs = {s: 0 for s in SENDERS}

    for r in rows:
        if md_sender(r.get("subject")) not in SENDERS:
            continue
        try:
            ex = parse_execution(Accounting, r)
            if not ex or ex["key"] in seen:
                continue
            seen.add(ex["key"])
            if not (start <= ex["mono"] <= end):
                continue

            s = ex["sender"]
            if ex["internal"]:
                internal_execs[s] += 1
                continue
            external_execs[s] += 1

            side_counts[s][ex["side"]]["count"] += 1
            side_counts[s][ex["side"]]["qty"] += ex["qty"]

            if last_exec_mono[s] is not None:
                intervals[s].append((ex["mono"] - last_exec_mono[s]) / 1e6)
            last_exec_mono[s] = ex["mono"]

            pi = prev_idx(times, ex["mono"])
            if pi < 0:
                continue
            pre = bbos[pi]
            sign = side_sign(ex["side"])

            crossing = sign * (pre["mid"] - ex["price"])
            # crossing is normally <= 0 for aggressive orders and >= 0 for passive fills.
            add_metric(seat[s], "execution_edge0", crossing, ex["qty"])
            add_metric(seat[s], "spread_at_fill", pre["spread"], ex["qty"])

            for h in HORIZONS_MS:
                ni = next_idx(times, ex["mono"] + h * 1_000_000)
                if ni >= len(bbos):
                    continue
                future = bbos[ni]
                signal_move = sign * (future["mid"] - pre["mid"])
                total_markout = sign * (future["mid"] - ex["price"])
                add_metric(seat[s], f"signal_move_{h}", signal_move, ex["qty"])
                add_metric(seat[s], f"markout_{h}", total_markout, ex["qty"])

        except Exception as exc:
            parse_errors.append(str(exc))

    seed = run_dir.name.split("seed-")[-1]
    pnl = summary.get("pnl_proxy", {})
    risk = summary.get("risk", {})
    exe = summary.get("execution", {})

    result = {
        "run": run_dir.name,
        "seed": seed,
        "desk_pnl": pnl.get("window_pnl_proxy"),
        "seat_pnl": pnl.get("per_sender_pnl_proxy", {}),
        "start_mid": pnl.get("start_mid"),
        "end_mid": pnl.get("end_mid"),
        "auth_max_abs": exe.get("authoritative_max_abs_net"),
        "hard_ms": exe.get("total_hard_exposure_ms"),
        "false_safe": summary.get("alignment", {}).get("false_safe_candidates"),
        "parse_errors": parse_errors,
        "external_execs": external_execs,
        "internal_execs": internal_execs,
        "side_counts": {
            s: {side: dict(vals) for side, vals in side_counts[s].items()}
            for s in SENDERS
        },
        "interval_ms_mean": {
            s: (sum(intervals[s]) / len(intervals[s]) if intervals[s] else None)
            for s in SENDERS
        },
        "metrics": {},
    }

    for s in SENDERS:
        result["metrics"][s] = {}
        for key, vals in seat[s].items():
            result["metrics"][s][key] = weighted(vals)

    return result


def main():
    a = args()
    root = a.evidence_root.resolve()
    candidate = a.candidate.resolve()
    sys.path.insert(0, str(candidate))
    from hedger.accounting import DeskPositionAccounting

    runs = []
    for run_dir in sorted(root.glob("run-*-seed-*")):
        if (run_dir / "raw.ndjson").exists() and (run_dir / "analysis.json").exists():
            runs.append(analyze_run(run_dir, DeskPositionAccounting))

    if not runs:
        raise SystemExit("No completed runs found")

    print("JOB31_PROFITABILITY_DIAGNOSIS")
    print("READ_ONLY=true")
    print()

    print("CROSS_RUN_PNL")
    for r in runs:
        p = r["seat_pnl"]
        ret = None
        if r["start_mid"]:
            ret = (r["end_mid"] / r["start_mid"] - 1) * 100
        print(
            f"{r['run']} market_return_pct={f(ret,2)} "
            f"desk={f(r['desk_pnl'],2)} "
            f"taker={f(p.get('PYTKR001'),2)} "
            f"quoter={f(p.get('QUOTE001'),2)} "
            f"hedger={f(p.get('HEDGE001'),2)} "
            f"max_abs={r['auth_max_abs']} hard_ms={f(r['hard_ms'],3)} "
            f"false_safe_candidates={r['false_safe']}"
        )

    print()
    print("PER_RUN_CAUSE_DECOMPOSITION")
    for r in runs:
        print(f"RUN {r['run']}")
        for s in SENDERS:
            m = r["metrics"][s]
            sides = r["side_counts"][s]
            print(
                f"  {s}: external_exec={r['external_execs'][s]} "
                f"internal_exec={r['internal_execs'][s]} "
                f"buy={sides.get('B',{}).get('count',0)} "
                f"sell={sides.get('S',{}).get('count',0)} "
                f"mean_inter_exec_ms={f(r['interval_ms_mean'][s],1)} "
                f"spread={f(m.get('spread_at_fill'))} "
                f"edge0={f(m.get('execution_edge0'))}"
            )
            print(
                f"    signal_move: "
                f"50ms={f(m.get('signal_move_50'))} "
                f"250ms={f(m.get('signal_move_250'))} "
                f"1000ms={f(m.get('signal_move_1000'))} "
                f"5000ms={f(m.get('signal_move_5000'))}"
            )
            print(
                f"    total_markout: "
                f"50ms={f(m.get('markout_50'))} "
                f"250ms={f(m.get('markout_250'))} "
                f"1000ms={f(m.get('markout_1000'))} "
                f"5000ms={f(m.get('markout_5000'))}"
            )

    print()
    print("AGGREGATE_WEIGHTED_BY_RUN_MEAN")
    for s in SENDERS:
        keys = [
            "spread_at_fill", "execution_edge0",
            "signal_move_50", "signal_move_250", "signal_move_1000", "signal_move_5000",
            "markout_50", "markout_250", "markout_1000", "markout_5000",
        ]
        print(s)
        for key in keys:
            vals = [
                r["metrics"][s].get(key)
                for r in runs
                if r["metrics"][s].get(key) is not None
            ]
            v = sum(vals) / len(vals) if vals else None
            print(f"  {key}={f(v)}")

    print()
    print("INTERPRETATION_HINTS")
    print("execution_edge0 isolates price paid/earned versus the pre-fill mid.")
    print("signal_move_h isolates post-fill directional movement from the spread/crossing cost.")
    print("total_markout_h = execution_edge0 + signal_move_h.")
    print("Positive values favor the seat; negative values hurt the seat.")
    print("No parameter or production code is changed by this script.")

    output = {"runs": runs}
    out = a.json_out or (root / "profitability-diagnosis.json")
    out.write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
    print()
    print(f"json_out={out}")
    print("NO_TUNING_PERFORMED")


if __name__ == "__main__":
    main()
