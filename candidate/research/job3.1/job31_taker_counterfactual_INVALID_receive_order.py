#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import json
from collections import defaultdict
from pathlib import Path

SENDER = "PYTKR001"
BBO_SUBJECT = "ex.bbo.AAH6"
REQ_SUBJECT = "ex.req.PYTKR001"
MD_SUBJECT = "ex.md.AAH6.PYTKR001"
HORIZONS_MS = (50, 250, 1000, 5000)
CUTOFFS = (10, 12, 15, 20, 25, 30)
LAG = 5


def parse_args():
    p = argparse.ArgumentParser(
        description="Read-only Taker trigger/counterfactual diagnosis for Job 3.1."
    )
    p.add_argument(
        "--evidence-root",
        type=Path,
        default=Path(r"D:\TRV_General_AI_Challenge_2026_evidence\job3.1A"),
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


def parse_bbo(row):
    p = str(row.get("payload_text", "")).split()
    if len(p) != 6:
        return None
    try:
        bid = None if p[2] == "-" else int(p[2])
        ask = None if p[4] == "-" else int(p[4])
        bid_vol = int(p[3])
        ask_vol = int(p[5])
    except ValueError:
        return None
    if bid is None or ask is None or bid_vol <= 0 or ask_vol <= 0 or bid > ask:
        return None
    return {
        "mono": int(row["recv_mono_ns"]),
        "source_ts": int(p[0]),
        "bid": bid,
        "ask": ask,
        "bid_vol": bid_vol,
        "ask_vol": ask_vol,
        "mid": (bid + ask) / 2.0,
        "spread": ask - bid,
        "line": row["_line"],
    }


def parse_request(row):
    p = str(row.get("payload_text", "")).split()
    if len(p) != 8:
        return None
    if p[0] != SENDER or p[1] != "A" or p[2] != "AAH6" or p[7] != "F":
        return None
    try:
        qty = int(p[5])
        px = int(p[6])
    except ValueError:
        return None
    if p[4] not in {"B", "S"} or qty <= 0 or px <= 0:
        return None
    return {
        "oid": p[3],
        "side": p[4],
        "qty": qty,
        "px": px,
        "mono": int(row["recv_mono_ns"]),
        "line": row["_line"],
    }


def parse_t(row):
    p = str(row.get("payload_text", "")).split()
    if len(p) != 8 or p[1] != "T":
        return None
    incoming = p[2]
    if not incoming.startswith(SENDER + ":"):
        return None
    try:
        qty = int(p[4])
        px = int(p[5])
    except ValueError:
        return None
    if qty <= 0 or px <= 0 or p[7] not in {"B", "S"}:
        return None
    return {
        "oid": incoming.split(":", 1)[1],
        "qty": qty,
        "px": px,
        "side": p[7],
        "match_id": p[6],
        "mono": int(row["recv_mono_ns"]),
        "source_ts": int(p[0]),
        "line": row["_line"],
    }


def future_bbo(bbos, times, t):
    i = bisect.bisect_left(times, t)
    return bbos[i] if i < len(bbos) else None


def latest_bbo_index(times, t):
    return bisect.bisect_right(times, t) - 1


def side_sign(side):
    return 1 if side == "B" else -1


def weighted_avg(items, value_key, qty_key="fill_qty"):
    qty = sum(x[qty_key] for x in items if x.get(value_key) is not None)
    if not qty:
        return None
    return sum(x[value_key] * x[qty_key] for x in items if x.get(value_key) is not None) / qty


def fmt(v, digits=3):
    return "n/a" if v is None else f"{v:.{digits}f}"


def analyze_run(run_dir):
    rows = load_rows(run_dir / "raw.ndjson")
    start = control(rows, "MEASUREMENT_START")
    end = control(rows, "MEASUREMENT_END")

    bbos = []
    requests = []
    fills_by_oid = defaultdict(list)

    for r in rows:
        subj = r.get("subject")
        if subj == BBO_SUBJECT:
            b = parse_bbo(r)
            if b:
                bbos.append(b)
        elif subj == REQ_SUBJECT and start <= int(r.get("recv_mono_ns", -1)) <= end:
            q = parse_request(r)
            if q:
                requests.append(q)
        elif subj == MD_SUBJECT:
            t = parse_t(r)
            if t:
                fills_by_oid[t["oid"]].append(t)

    bbos.sort(key=lambda x: x["mono"])
    times = [b["mono"] for b in bbos]

    trades = []
    reconstruction_failures = 0

    for req in requests:
        fills = fills_by_oid.get(req["oid"], [])
        # Dedup match ids defensively.
        unique = {}
        for fill in fills:
            unique.setdefault(fill["match_id"], fill)
        fills = list(unique.values())

        fill_qty = sum(f["qty"] for f in fills)
        if fill_qty <= 0:
            continue

        actual_vwap = sum(f["px"] * f["qty"] for f in fills) / fill_qty

        i = latest_bbo_index(times, req["mono"])
        if i < LAG:
            reconstruction_failures += 1
            continue

        current = bbos[i]
        past = bbos[i - LAG]
        signal_signed = current["mid"] - past["mid"]
        signal_abs = abs(signal_signed)
        expected_side = "B" if signal_signed >= 0 else "S"

        # Because probe observes BBO and request on separate subjects, this is
        # diagnostic only. Keep whether the reconstructed direction matches the request.
        direction_match = expected_side == req["side"]

        sign = side_sign(req["side"])
        actual_edge0 = sign * (current["mid"] - actual_vwap)

        reverse_side = "S" if req["side"] == "B" else "B"
        reverse_sign = -sign
        reverse_px = current["bid"] if reverse_side == "S" else current["ask"]
        reverse_available = (
            current["bid_vol"] if reverse_side == "S" else current["ask_vol"]
        )
        reverse_executable = reverse_available >= fill_qty
        reverse_edge0 = reverse_sign * (current["mid"] - reverse_px)

        trade = {
            "run": run_dir.name,
            "oid": req["oid"],
            "side": req["side"],
            "fill_qty": fill_qty,
            "signal_signed": signal_signed,
            "signal_abs": signal_abs,
            "spread": current["spread"],
            "direction_match": direction_match,
            "actual_edge0": actual_edge0,
            "reverse_edge0": reverse_edge0,
            "reverse_executable": reverse_executable,
        }

        for h in HORIZONS_MS:
            fut = future_bbo(bbos, times, req["mono"] + h * 1_000_000)
            if not fut:
                trade[f"signal_{h}"] = None
                trade[f"actual_markout_{h}"] = None
                trade[f"reverse_markout_{h}"] = None
                continue

            # Post-trigger movement in original Taker direction.
            signal_move = sign * (fut["mid"] - current["mid"])
            actual_markout = sign * (fut["mid"] - actual_vwap)
            reverse_markout = reverse_sign * (fut["mid"] - reverse_px)

            trade[f"signal_{h}"] = signal_move
            trade[f"actual_markout_{h}"] = actual_markout
            trade[f"reverse_markout_{h}"] = reverse_markout

        trades.append(trade)

    return {
        "run": run_dir.name,
        "request_count": len(requests),
        "filled_trade_count": len(trades),
        "reconstruction_failures": reconstruction_failures,
        "trades": trades,
    }


def group_summary(items):
    qty = sum(x["fill_qty"] for x in items)
    if not qty:
        return None
    out = {
        "trades": len(items),
        "qty": qty,
        "signal_abs": weighted_avg(items, "signal_abs"),
        "spread": weighted_avg(items, "spread"),
        "direction_match_rate": sum(x["fill_qty"] for x in items if x["direction_match"]) / qty,
        "reverse_executable_rate": sum(x["fill_qty"] for x in items if x["reverse_executable"]) / qty,
        "actual_edge0": weighted_avg(items, "actual_edge0"),
        "reverse_edge0": weighted_avg(items, "reverse_edge0"),
    }
    for h in HORIZONS_MS:
        out[f"signal_{h}"] = weighted_avg(items, f"signal_{h}")
        out[f"actual_markout_{h}"] = weighted_avg(items, f"actual_markout_{h}")
        rev_items = [x for x in items if x["reverse_executable"]]
        out[f"reverse_markout_{h}"] = weighted_avg(rev_items, f"reverse_markout_{h}")
    return out


def print_group(label, summary):
    if not summary:
        print(f"{label}: no trades")
        return
    print(
        f"{label}: trades={summary['trades']} qty={summary['qty']} "
        f"signal={fmt(summary['signal_abs'])} spread={fmt(summary['spread'])} "
        f"direction_match={summary['direction_match_rate']*100:.1f}% "
        f"reverse_executable={summary['reverse_executable_rate']*100:.1f}% "
        f"edge0={fmt(summary['actual_edge0'])}"
    )
    print(
        "  actual_markout "
        + " ".join(
            f"{h}ms={fmt(summary[f'actual_markout_{h}'])}" for h in HORIZONS_MS
        )
    )
    print(
        "  signal_move    "
        + " ".join(f"{h}ms={fmt(summary[f'signal_{h}'])}" for h in HORIZONS_MS)
    )
    print(
        "  reversed_cf    "
        + " ".join(
            f"{h}ms={fmt(summary[f'reverse_markout_{h}'])}" for h in HORIZONS_MS
        )
    )


def main():
    a = parse_args()
    root = a.evidence_root.resolve()

    runs = []
    all_trades = []
    for run_dir in sorted(root.glob("run-*-seed-*")):
        raw = run_dir / "raw.ndjson"
        if not raw.exists():
            continue
        result = analyze_run(run_dir)
        runs.append(result)
        all_trades.extend(result["trades"])

    if not runs:
        raise SystemExit(f"No run evidence found under {root}")

    print("JOB31_TAKER_COUNTERFACTUAL")
    print("READ_ONLY=true")
    print("NOTE=reconstructed trigger signal uses probe receive order across subjects; treat as diagnostic, not authoritative execution ordering.")
    print()

    print("RUNS")
    for r in runs:
        print(
            f"{r['run']}: requests={r['request_count']} "
            f"filled={r['filled_trade_count']} "
            f"reconstruction_failures={r['reconstruction_failures']}"
        )

    print()
    print("ALL_FILLED")
    print_group("all", group_summary(all_trades))

    print()
    print("BY_SIDE")
    for side in ("B", "S"):
        print_group(side, group_summary([x for x in all_trades if x["side"] == side]))

    print()
    print("BY_RECONSTRUCTED_SIGNAL_BUCKET")
    buckets = [
        ("<10", lambda x: x["signal_abs"] < 10),
        ("10-14.999", lambda x: 10 <= x["signal_abs"] < 15),
        ("15-19.999", lambda x: 15 <= x["signal_abs"] < 20),
        ("20-29.999", lambda x: 20 <= x["signal_abs"] < 30),
        (">=30", lambda x: x["signal_abs"] >= 30),
    ]
    for label, pred in buckets:
        print_group(label, group_summary([x for x in all_trades if pred(x)]))

    print()
    print("THRESHOLD_RETENTION_COUNTERFACTUAL")
    print("cutoff retained_trades retained_qty current_1000ms current_5000ms reversed_1000ms reversed_5000ms")
    for cutoff in CUTOFFS:
        items = [x for x in all_trades if x["signal_abs"] >= cutoff and x["direction_match"]]
        s = group_summary(items)
        if not s:
            print(f"{cutoff} 0 0 n/a n/a n/a n/a")
            continue
        print(
            f"{cutoff} {s['trades']} {s['qty']} "
            f"{fmt(s['actual_markout_1000'])} {fmt(s['actual_markout_5000'])} "
            f"{fmt(s['reverse_markout_1000'])} {fmt(s['reverse_markout_5000'])}"
        )

    print()
    print("BY_SPREAD_BUCKET")
    spread_buckets = [
        ("<=10", lambda x: x["spread"] <= 10),
        ("11-15", lambda x: 10 < x["spread"] <= 15),
        ("16-20", lambda x: 15 < x["spread"] <= 20),
        (">20", lambda x: x["spread"] > 20),
    ]
    for label, pred in spread_buckets:
        print_group(label, group_summary([x for x in all_trades if pred(x)]))

    output = {
        "runs": runs,
        "all": group_summary(all_trades),
    }
    out = a.json_out or (root / "taker-counterfactual.json")
    out.write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
    print()
    print(f"json_out={out}")
    print("NO_TUNING_PERFORMED")


if __name__ == "__main__":
    main()
