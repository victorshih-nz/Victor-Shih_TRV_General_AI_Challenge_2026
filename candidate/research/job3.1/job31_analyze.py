#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import json
import math
import sys
from collections import Counter
from pathlib import Path
from statistics import mean

SENDERS = ("PYTKR001", "QUOTE001", "HEDGE001")
RISK_SUBJECT = "desk.risk.AAH6"
BBO_SUBJECT = "ex.bbo.AAH6"
STALE_NS = 1_000_000_000


def parse_args():
    p = argparse.ArgumentParser(description="Analyze Job 3.1A passive-probe NDJSON.")
    p.add_argument("raw", type=Path, help="raw.ndjson")
    p.add_argument("--candidate", type=Path, default=Path.cwd(),
                   help="candidate directory (default: current directory)")
    p.add_argument("--json-out", type=Path, default=None,
                   help="optional JSON summary output")
    return p.parse_args()


def load_rows(path: Path):
    rows = []
    errors = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
                row["_line"] = line_no
                rows.append(row)
            except Exception as exc:
                errors.append({"line": line_no, "error": str(exc)})
    return rows, errors


def find_control(rows, event):
    matches = [r for r in rows if r.get("kind") == "CONTROL" and r.get("event") == event]
    if not matches:
        raise RuntimeError(f"missing CONTROL {event}")
    return matches[0]


def parse_risk(row):
    parts = str(row.get("payload_text", "")).strip().split()
    if len(parts) != 8:
        raise ValueError(f"risk expected 8 fields, got {len(parts)}")
    return {
        "ts": int(parts[0]),
        "seq": int(parts[1]),
        "feed": parts[2],
        "net": int(parts[3]),
        "soft": int(parts[4]),
        "hard": int(parts[5]),
        "state": parts[6],
        "direction": parts[7],
        "recv_mono_ns": int(row["recv_mono_ns"]),
        "recv_wall_ns": int(row.get("recv_wall_ns", 0)),
        "line": row["_line"],
    }


def parse_bbo(row):
    parts = str(row.get("payload_text", "")).strip().split()
    if len(parts) != 6:
        raise ValueError(f"BBO expected 6 fields, got {len(parts)}")

    def px(v):
        return None if v == "-" else int(v)

    bid = px(parts[2])
    ask = px(parts[4])
    bid_vol = int(parts[3])
    ask_vol = int(parts[5])
    valid = bid is not None and ask is not None and bid_vol > 0 and ask_vol > 0 and bid <= ask
    mid = (bid + ask) / 2.0 if valid else None
    return {
        "ts": int(parts[0]),
        "feed": parts[1],
        "bid": bid,
        "bid_vol": bid_vol,
        "ask": ask,
        "ask_vol": ask_vol,
        "valid": valid,
        "mid": mid,
        "recv_mono_ns": int(row["recv_mono_ns"]),
        "line": row["_line"],
    }


def sender_from_subject(subject):
    prefix = "ex.md.AAH6."
    return subject[len(prefix):] if subject.startswith(prefix) else None


def parse_exec_with_production(accounting_cls, row):
    sender = sender_from_subject(str(row.get("subject", "")))
    if sender not in SENDERS:
        return None

    event = accounting_cls.parse_event(row.get("payload_text", ""))
    if event is None or event.get("eventType") not in {"T", "E"}:
        return None

    if event["eventType"] == "T":
        incoming_sender, _ = accounting_cls._validate_public_order_identity(
            event["incomingOrderId"], "incomingOrderId"
        )
        if incoming_sender != sender:
            raise ValueError(
                f"T incoming sender mismatch subject={sender} payload={incoming_sender}"
            )
    else:
        resting_sender, _ = accounting_cls._validate_public_order_identity(
            event["restingOrderId"], "restingOrderId"
        )
        if resting_sender != sender:
            raise ValueError(
                f"E resting sender mismatch subject={sender} payload={resting_sender}"
            )

    side = accounting_cls.effective_side(event)
    qty = int(event["qty"])
    price = int(event["price"])
    delta = qty if side == "B" else -qty
    cash = -qty * price if side == "B" else qty * price

    key = accounting_cls.dedup_key(
        sender,
        event["eventType"],
        event["eventTimestamp"],
        event["matchId"],
        event["incomingOrderId"],
        event["restingOrderId"],
        qty,
        price,
        event["aggressorSide"],
    )

    return {
        "sender": sender,
        "event_type": event["eventType"],
        "event_ts": int(event["eventTimestamp"]),
        "match_id": event["matchId"],
        "side": side,
        "qty": qty,
        "price": price,
        "delta": delta,
        "cash": cash,
        "key": key,
        "recv_mono_ns": int(row["recv_mono_ns"]),
        "recv_wall_ns": int(row.get("recv_wall_ns", 0)),
        "line": row["_line"],
    }


def latest_valid_bbo(bbos, mono_ns, prefer_after=False):
    valid = [b for b in bbos if b["valid"]]
    if not valid:
        return None

    times = [b["recv_mono_ns"] for b in valid]
    i = bisect.bisect_right(times, mono_ns) - 1

    if i >= 0:
        return valid[i]

    if prefer_after:
        return valid[0]

    return None


def fmt_num(v):
    if v is None:
        return "n/a"
    if isinstance(v, float) and math.isfinite(v):
        return f"{v:.3f}"
    return str(v)


def main():
    args = parse_args()
    candidate = args.candidate.resolve()
    raw = args.raw.resolve()

    sys.path.insert(0, str(candidate))

    try:
        from hedger.accounting import DeskPositionAccounting, AccountingUncertainty
    except Exception as exc:
        raise SystemExit(
            f"Cannot import candidate/hedger/accounting.py from {candidate}: {exc}"
        )

    rows, json_errors = load_rows(raw)

    start_row = find_control(rows, "MEASUREMENT_START")
    end_row = find_control(rows, "MEASUREMENT_END")

    start_mono = int(start_row["recv_mono_ns"])
    end_mono = int(end_row["recv_mono_ns"])
    duration_s = (end_mono - start_mono) / 1e9

    window = [
        r for r in rows
        if start_mono <= int(r.get("recv_mono_ns", -1)) <= end_mono
    ]

    subject_counts = Counter(
        r.get("subject")
        for r in window
        if r.get("kind") == "NATS" and r.get("subject")
    )

    risk_rows = []
    risk_errors = []

    for r in window:
        if r.get("subject") != RISK_SUBJECT:
            continue
        try:
            risk_rows.append(parse_risk(r))
        except Exception as exc:
            risk_errors.append({"line": r["_line"], "error": str(exc)})

    bbo_rows = []
    bbo_errors = []

    for r in rows:
        if r.get("subject") != BBO_SUBJECT:
            continue
        try:
            bbo_rows.append(parse_bbo(r))
        except Exception as exc:
            bbo_errors.append({"line": r["_line"], "error": str(exc)})

    bbo_rows.sort(key=lambda x: x["recv_mono_ns"])

    execs = []
    exec_errors = []
    seen = set()
    duplicate_execs = 0

    for r in rows:
        sender = sender_from_subject(str(r.get("subject", "")))
        if sender not in SENDERS:
            continue

        try:
            ex = parse_exec_with_production(DeskPositionAccounting, r)

            if ex is None:
                continue

            if ex["key"] in seen:
                duplicate_execs += 1
                continue

            seen.add(ex["key"])
            execs.append(ex)

        except (AccountingUncertainty, ValueError, TypeError) as exc:
            exec_errors.append(
                {
                    "line": r["_line"],
                    "subject": r.get("subject"),
                    "error": str(exc),
                }
            )

    execs.sort(key=lambda x: (x["event_ts"], x["recv_mono_ns"], x["line"]))

    # Independent position reconstructed from authoritative T/E.
    # Source timestamps are used to compare against desk.risk publication timestamps.
    source_positions = {s: 0 for s in SENDERS}
    source_net_points = []

    for ex in execs:
        source_positions[ex["sender"]] += ex["delta"]
        source_net_points.append(
            (
                ex["event_ts"],
                sum(source_positions.values()),
                dict(source_positions),
                ex,
            )
        )

    source_times = [p[0] for p in source_net_points]

    divergence = []
    false_safe = []

    for rr in risk_rows:
        idx = bisect.bisect_right(source_times, rr["ts"]) - 1
        auth_net = source_net_points[idx][1] if idx >= 0 else 0

        if auth_net != rr["net"]:
            divergence.append(
                {
                    "risk_seq": rr["seq"],
                    "risk_ts": rr["ts"],
                    "risk_net": rr["net"],
                    "auth_net": auth_net,
                    "state": rr["state"],
                    "line": rr["line"],
                }
            )

        if rr["state"] == "SAFE" and abs(auth_net) >= rr["soft"]:
            false_safe.append(
                {
                    "risk_seq": rr["seq"],
                    "risk_ts": rr["ts"],
                    "risk_net": rr["net"],
                    "auth_net": auth_net,
                    "soft": rr["soft"],
                    "line": rr["line"],
                }
            )

    # Measurement-window positions/cashflow use the frozen receive-time boundary.
    opening_pos = {s: 0 for s in SENDERS}
    ending_pos = {s: 0 for s in SENDERS}
    window_cash = {s: 0 for s in SENDERS}
    window_qty = {s: 0 for s in SENDERS}
    window_exec_count = {s: 0 for s in SENDERS}

    prewindow_exec_count = 0

    for ex in execs:
        if ex["recv_mono_ns"] < start_mono:
            opening_pos[ex["sender"]] += ex["delta"]
            ending_pos[ex["sender"]] += ex["delta"]
            prewindow_exec_count += 1

        elif ex["recv_mono_ns"] <= end_mono:
            ending_pos[ex["sender"]] += ex["delta"]
            window_cash[ex["sender"]] += ex["cash"]
            window_qty[ex["sender"]] += ex["qty"]
            window_exec_count[ex["sender"]] += 1

    opening_net = sum(opening_pos.values())
    ending_net = sum(ending_pos.values())

    start_bbo = latest_valid_bbo(bbo_rows, start_mono, prefer_after=True)
    end_bbo = latest_valid_bbo(bbo_rows, end_mono, prefer_after=False)

    start_mid = start_bbo["mid"] if start_bbo else None
    end_mid = end_bbo["mid"] if end_bbo else None

    total_cash = sum(window_cash.values())
    pnl_proxy = None

    if start_mid is not None and end_mid is not None:
        pnl_proxy = (
            total_cash
            + ending_net * end_mid
            - opening_net * start_mid
        )

    per_sender_pnl = {}

    if start_mid is not None and end_mid is not None:
        for s in SENDERS:
            per_sender_pnl[s] = (
                window_cash[s]
                + ending_pos[s] * end_mid
                - opening_pos[s] * start_mid
            )

    # Authoritative net path by passive-probe receive order.
    recv_execs = sorted(
        [e for e in execs if e["recv_mono_ns"] <= end_mono],
        key=lambda e: (e["recv_mono_ns"], e["line"]),
    )

    recv_positions = {s: 0 for s in SENDERS}
    max_abs_auth = 0
    max_auth = 0
    min_auth = 0

    hard = risk_rows[0]["hard"] if risk_rows else None
    hard_intervals = []
    hard_start = None

    for ex in recv_execs:
        recv_positions[ex["sender"]] += ex["delta"]
        net = sum(recv_positions.values())

        if ex["recv_mono_ns"] < start_mono:
            continue

        max_abs_auth = max(max_abs_auth, abs(net))
        max_auth = max(max_auth, net)
        min_auth = min(min_auth, net)

        if hard is not None:
            if hard_start is None and abs(net) >= hard:
                hard_start = ex["recv_mono_ns"]

            elif hard_start is not None and abs(net) < hard:
                hard_intervals.append((hard_start, ex["recv_mono_ns"]))
                hard_start = None

    if hard_start is not None:
        hard_intervals.append((hard_start, end_mono))

    total_hard_ms = sum((b - a) / 1e6 for a, b in hard_intervals)

    risk_monos = sorted(r["recv_mono_ns"] for r in risk_rows)
    gaps_ms = [(b - a) / 1e6 for a, b in zip(risk_monos, risk_monos[1:])]

    max_gap_ms = max(gaps_ms) if gaps_ms else None
    mean_gap_ms = mean(gaps_ms) if gaps_ms else None
    stale_gaps = [g for g in gaps_ms if g >= 1000.0]

    # Conservative receive-order detector only. Any hit needs manual causal review.
    risk_by_recv = sorted(risk_rows, key=lambda r: r["recv_mono_ns"])
    risk_recv_times = [r["recv_mono_ns"] for r in risk_by_recv]

    add_candidates = []
    add_count = 0

    for r in window:
        subj = str(r.get("subject", ""))

        if subj not in {"ex.req.PYTKR001", "ex.req.QUOTE001"}:
            continue

        parts = str(r.get("payload_text", "")).strip().split()

        if len(parts) < 2 or parts[1] != "A":
            continue

        add_count += 1
        t = int(r["recv_mono_ns"])

        i = bisect.bisect_right(risk_recv_times, t) - 1

        if i < 0:
            add_candidates.append(
                {
                    "line": r["_line"],
                    "subject": subj,
                    "reason": "no prior risk observed",
                }
            )
            continue

        rr = risk_by_recv[i]
        age = t - rr["recv_mono_ns"]

        if rr["state"] == "UNKNOWN" or age >= STALE_NS:
            add_candidates.append(
                {
                    "line": r["_line"],
                    "subject": subj,
                    "reason": "UNKNOWN" if rr["state"] == "UNKNOWN" else "stale",
                    "risk_seq": rr["seq"],
                    "risk_state": rr["state"],
                    "risk_age_ms": age / 1e6,
                }
            )

    risk_states = Counter(r["state"] for r in risk_rows)
    risk_min = min((r["net"] for r in risk_rows), default=None)
    risk_max = max((r["net"] for r in risk_rows), default=None)

    summary = {
        "duration_s": duration_s,
        "json_errors": json_errors,
        "subject_counts": dict(sorted(subject_counts.items())),
        "risk": {
            "count": len(risk_rows),
            "parse_errors": risk_errors,
            "states": dict(risk_states),
            "min_net": risk_min,
            "max_net": risk_max,
            "soft": risk_rows[0]["soft"] if risk_rows else None,
            "hard": hard,
            "max_receive_gap_ms": max_gap_ms,
            "mean_receive_gap_ms": mean_gap_ms,
            "stale_gap_count_ge_1000ms": len(stale_gaps),
        },
        "execution": {
            "unique_exec_events": len(execs),
            "duplicates_ignored": duplicate_execs,
            "parse_errors": exec_errors,
            "prewindow_exec_count": prewindow_exec_count,
            "window_exec_count": window_exec_count,
            "window_qty": window_qty,
            "opening_positions": opening_pos,
            "ending_positions": ending_pos,
            "opening_net": opening_net,
            "ending_net": ending_net,
            "authoritative_min_net": min_auth,
            "authoritative_max_net": max_auth,
            "authoritative_max_abs_net": max_abs_auth,
            "hard_interval_count": len(hard_intervals),
            "total_hard_exposure_ms": total_hard_ms,
        },
        "alignment": {
            "position_divergence_candidates": len(divergence),
            "false_safe_candidates": len(false_safe),
            "first_divergence_candidates": divergence[:10],
            "first_false_safe_candidates": false_safe[:10],
            "note": (
                "Source-time alignment uses Exchange event timestamps versus "
                "desk.risk source timestamps; any non-zero candidate requires "
                "causal review before FAIL."
            ),
        },
        "exposure_dispatch": {
            "taker_quoter_add_count": add_count,
            "unknown_or_stale_probe_order_candidates": len(add_candidates),
            "first_candidates": add_candidates[:10],
            "note": (
                "Receive-order candidate detector is conservative because NATS "
                "subjects can be observed out of order."
            ),
        },
        "pnl_proxy": {
            "start_mid": start_mid,
            "end_mid": end_mid,
            "window_execution_cashflow": total_cash,
            "window_pnl_proxy": pnl_proxy,
            "per_sender_execution_cashflow": window_cash,
            "per_sender_pnl_proxy": per_sender_pnl,
            "note": (
                "Proxy = execution cashflow + ending inventory*end mid "
                "- opening inventory*start mid; not grader liquidation PnL."
            ),
        },
        "bbo": {
            "parse_errors": bbo_errors,
            "start": start_bbo,
            "end": end_bbo,
        },
    }

    print("JOB31_RUN_ANALYSIS")
    print(f"window_seconds={duration_s:.3f}")
    print(f"risk_states={dict(risk_states)} risk_net=[{risk_min},{risk_max}]")
    print(
        f"risk_gap_ms mean={fmt_num(mean_gap_ms)} "
        f"max={fmt_num(max_gap_ms)} "
        f"stale_ge_1000ms={len(stale_gaps)}"
    )
    print(
        f"exec_unique={len(execs)} "
        f"exec_parse_errors={len(exec_errors)} "
        f"prewindow_execs={prewindow_exec_count}"
    )
    print(f"opening_positions={opening_pos} opening_net={opening_net}")
    print(f"ending_positions={ending_pos} ending_net={ending_net}")
    print(
        f"auth_net=[{min_auth},{max_auth}] "
        f"max_abs={max_abs_auth} "
        f"hard_intervals={len(hard_intervals)} "
        f"total_hard_ms={total_hard_ms:.3f}"
    )
    print(
        f"position_divergence_candidates={len(divergence)} "
        f"false_safe_candidates={len(false_safe)}"
    )
    print(
        f"taker_quoter_adds={add_count} "
        f"unknown_or_stale_probe_order_candidates={len(add_candidates)}"
    )
    print(
        f"start_mid={fmt_num(start_mid)} "
        f"end_mid={fmt_num(end_mid)} "
        f"execution_cashflow={total_cash} "
        f"pnl_proxy={fmt_num(pnl_proxy)}"
    )
    print(f"per_sender_pnl_proxy={per_sender_pnl}")
    print(
        f"json_errors={len(json_errors)} "
        f"risk_parse_errors={len(risk_errors)} "
        f"bbo_parse_errors={len(bbo_errors)}"
    )

    if divergence:
        print("FIRST_DIVERGENCE_CANDIDATES")
        for item in divergence[:10]:
            print(json.dumps(item, sort_keys=True))

    if false_safe:
        print("FIRST_FALSE_SAFE_CANDIDATES")
        for item in false_safe[:10]:
            print(json.dumps(item, sort_keys=True))

    if add_candidates:
        print("FIRST_ADD_READINESS_CANDIDATES")
        for item in add_candidates[:10]:
            print(json.dumps(item, sort_keys=True))

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(
            json.dumps(summary, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        print(f"json_out={args.json_out}")


if __name__ == "__main__":
    main()
