#!/usr/bin/env python3
from __future__ import annotations

import argparse
import bisect
import json
import math
from collections import defaultdict
from pathlib import Path

BBO_SUBJECT = "ex.bbo.AAH6"
REQ_SUBJECT = "ex.req.PYTKR001"
MD_SUBJECT = "ex.md.AAH6.PYTKR001"
SENDER = "PYTKR001"

DEFAULT_LAG = 5
DEFAULT_THRESH = 10.0
MATCH_WINDOW_MS = 100.0
HORIZONS_MS = (50, 250, 1000, 5000)

THRESHOLDS = (10, 12, 15, 20, 25, 30)
SPREAD_CAPS = (5, 10, 15, 20)
IMBALANCE_MINS = (0.0, 0.2, 0.4, 0.6)


def parse_args():
    p = argparse.ArgumentParser(
        description=(
            "Read-only exact-trigger reconstruction for the Job 3.1 Taker. "
            "Matches each Taker request to a nearby BBO whose executable side "
            "price equals the request limit and whose LAG-step move satisfies "
            "the production trigger."
        )
    )
    p.add_argument(
        "--evidence-root",
        type=Path,
        default=Path(r"D:\TRV_General_AI_Challenge_2026_evidence\job3.1A"),
    )
    p.add_argument("--lag", type=int, default=DEFAULT_LAG)
    p.add_argument("--thresh", type=float, default=DEFAULT_THRESH)
    p.add_argument("--json-out", type=Path, default=None)
    return p.parse_args()


def load_rows(path: Path):
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            text = line.strip()
            if not text:
                continue
            row = json.loads(text)
            row["_line"] = line_no
            rows.append(row)
    return rows


def control_mono(rows, event):
    for row in rows:
        if row.get("kind") == "CONTROL" and row.get("event") == event:
            return int(row["recv_mono_ns"])
    raise RuntimeError(f"Missing CONTROL {event}")


def parse_bbo(row):
    fields = str(row.get("payload_text", "")).split()
    if len(fields) != 6:
        return None

    try:
        bid = None if fields[2] == "-" else int(fields[2])
        ask = None if fields[4] == "-" else int(fields[4])
        bid_vol = 0 if fields[3] == "-" else int(fields[3])
        ask_vol = 0 if fields[5] == "-" else int(fields[5])
        source_ts = int(fields[0])
    except ValueError:
        return None

    # Taker only appends a mid when both sides exist.
    if (
        bid is None
        or ask is None
        or bid_vol <= 0
        or ask_vol <= 0
        or bid > ask
    ):
        return None

    total = bid_vol + ask_vol
    imbalance = (bid_vol - ask_vol) / total if total else 0.0

    return {
        "mono": int(row["recv_mono_ns"]),
        "source_ts": source_ts,
        "bid": bid,
        "ask": ask,
        "bid_vol": bid_vol,
        "ask_vol": ask_vol,
        "mid": (bid + ask) / 2.0,
        "spread": ask - bid,
        "imbalance": imbalance,
        "line": row["_line"],
    }


def parse_request(row):
    fields = str(row.get("payload_text", "")).split()
    if len(fields) != 8:
        return None

    if (
        fields[0] != SENDER
        or fields[1] != "A"
        or fields[2] != "AAH6"
        or fields[4] not in {"B", "S"}
        or fields[7] != "F"
    ):
        return None

    try:
        qty = int(fields[5])
        price = int(fields[6])
    except ValueError:
        return None

    if qty <= 0 or price <= 0:
        return None

    return {
        "oid": fields[3],
        "side": fields[4],
        "qty": qty,
        "price": price,
        "mono": int(row["recv_mono_ns"]),
        "line": row["_line"],
    }


def parse_t(row):
    fields = str(row.get("payload_text", "")).split()
    if len(fields) != 8 or fields[1] != "T":
        return None

    incoming = fields[2]
    if not incoming.startswith(SENDER + ":"):
        return None

    try:
        qty = int(fields[4])
        price = int(fields[5])
    except ValueError:
        return None

    if qty <= 0 or price <= 0 or fields[7] not in {"B", "S"}:
        return None

    return {
        "oid": incoming.split(":", 1)[1],
        "qty": qty,
        "price": price,
        "match_id": fields[6],
        "side": fields[7],
        "mono": int(row["recv_mono_ns"]),
        "line": row["_line"],
    }


def trigger_signal(bbos, idx, lag):
    if idx < lag:
        return None
    return bbos[idx]["mid"] - bbos[idx - lag]["mid"]


def trigger_satisfied(side, signal, thresh):
    if signal is None:
        return False
    if side == "B":
        return signal >= thresh
    return signal <= -thresh


def executable_side_price(bbo, side):
    return bbo["ask"] if side == "B" else bbo["bid"]


def find_trigger_bbo(bbos, req, lag, thresh):
    window_ns = int(MATCH_WINDOW_MS * 1_000_000)
    candidates = []

    for idx, bbo in enumerate(bbos):
        delta_ns = bbo["mono"] - req["mono"]
        if abs(delta_ns) > window_ns:
            continue
        if executable_side_price(bbo, req["side"]) != req["price"]:
            continue

        signal = trigger_signal(bbos, idx, lag)
        exact = trigger_satisfied(req["side"], signal, thresh)
        candidates.append(
            (
                0 if exact else 1,
                abs(delta_ns),
                0 if delta_ns <= 0 else 1,
                idx,
                signal,
            )
        )

    if not candidates:
        return None

    candidates.sort()
    _, _, _, idx, signal = candidates[0]
    bbo = bbos[idx]

    return {
        "idx": idx,
        "bbo": bbo,
        "signal": signal,
        "exact": trigger_satisfied(req["side"], signal, thresh),
        "delta_ms": (bbo["mono"] - req["mono"]) / 1_000_000.0,
    }


def future_bbo(bbos, times, mono):
    idx = bisect.bisect_left(times, mono)
    return bbos[idx] if idx < len(bbos) else None


def side_sign(side):
    return 1 if side == "B" else -1


def weighted_avg(items, field):
    valid = [x for x in items if x.get(field) is not None]
    qty = sum(x["fill_qty"] for x in valid)
    if qty == 0:
        return None
    return sum(x[field] * x["fill_qty"] for x in valid) / qty


def pct(n, d):
    return (100.0 * n / d) if d else None


def fmt(value, digits=3):
    if value is None:
        return "n/a"
    return f"{value:.{digits}f}"


def summarize(items):
    if not items:
        return None

    qty = sum(x["fill_qty"] for x in items)
    exact_qty = sum(x["fill_qty"] for x in items if x["exact_trigger"])
    out = {
        "trades": len(items),
        "qty": qty,
        "exact_trigger_pct": pct(exact_qty, qty),
        "signal_abs": weighted_avg(items, "signal_abs"),
        "spread": weighted_avg(items, "spread"),
        "directional_imbalance": weighted_avg(items, "directional_imbalance"),
        "edge0": weighted_avg(items, "edge0"),
    }

    for h in HORIZONS_MS:
        out[f"signal_move_{h}"] = weighted_avg(items, f"signal_move_{h}")
        out[f"markout_{h}"] = weighted_avg(items, f"markout_{h}")
        reverse_items = [
            x
            for x in items
            if x["reverse_executable"] and x.get(f"reverse_markout_{h}") is not None
        ]
        out[f"reverse_markout_{h}"] = weighted_avg(
            reverse_items, f"reverse_markout_{h}"
        )

    return out


def print_summary(label, s):
    if not s:
        print(f"{label}: no trades")
        return

    print(
        f"{label}: trades={s['trades']} qty={s['qty']} "
        f"exact_trigger={fmt(s['exact_trigger_pct'],1)}% "
        f"signal={fmt(s['signal_abs'])} "
        f"spread={fmt(s['spread'])} "
        f"dir_imb={fmt(s['directional_imbalance'])} "
        f"edge0={fmt(s['edge0'])}"
    )

    print(
        "  signal_move "
        + " ".join(
            f"{h}ms={fmt(s[f'signal_move_{h}'])}" for h in HORIZONS_MS
        )
    )
    print(
        "  markout     "
        + " ".join(
            f"{h}ms={fmt(s[f'markout_{h}'])}" for h in HORIZONS_MS
        )
    )
    print(
        "  reversed_cf "
        + " ".join(
            f"{h}ms={fmt(s[f'reverse_markout_{h}'])}" for h in HORIZONS_MS
        )
    )


def analyze_run(run_dir, lag, thresh):
    rows = load_rows(run_dir / "raw.ndjson")
    start = control_mono(rows, "MEASUREMENT_START")
    end = control_mono(rows, "MEASUREMENT_END")

    bbos = []
    requests = []
    fills_by_oid = defaultdict(dict)

    for row in rows:
        subject = row.get("subject")

        if subject == BBO_SUBJECT:
            bbo = parse_bbo(row)
            if bbo is not None:
                bbos.append(bbo)

        elif subject == REQ_SUBJECT:
            mono = int(row.get("recv_mono_ns", -1))
            if start <= mono <= end:
                req = parse_request(row)
                if req is not None:
                    requests.append(req)

        elif subject == MD_SUBJECT:
            fill = parse_t(row)
            if fill is not None:
                fills_by_oid[fill["oid"]].setdefault(fill["match_id"], fill)

    bbos.sort(key=lambda x: x["mono"])
    times = [x["mono"] for x in bbos]

    filled = []
    unmatched = []
    non_exact = []

    for req in requests:
        fills = list(fills_by_oid.get(req["oid"], {}).values())
        fill_qty = sum(x["qty"] for x in fills)

        if fill_qty <= 0:
            continue

        vwap = sum(x["qty"] * x["price"] for x in fills) / fill_qty
        match = find_trigger_bbo(bbos, req, lag, thresh)

        if match is None:
            unmatched.append(req)
            continue

        if not match["exact"]:
            non_exact.append(
                {
                    "oid": req["oid"],
                    "side": req["side"],
                    "request_price": req["price"],
                    "signal": match["signal"],
                    "delta_ms": match["delta_ms"],
                    "request_line": req["line"],
                    "bbo_line": match["bbo"]["line"],
                }
            )

        bbo = match["bbo"]
        signal = match["signal"]
        sign = side_sign(req["side"])

        directional_imbalance = sign * bbo["imbalance"]
        edge0 = sign * (bbo["mid"] - vwap)

        reverse_side = "S" if req["side"] == "B" else "B"
        reverse_sign = -sign
        reverse_price = bbo["bid"] if reverse_side == "S" else bbo["ask"]
        reverse_vol = bbo["bid_vol"] if reverse_side == "S" else bbo["ask_vol"]

        trade = {
            "run": run_dir.name,
            "oid": req["oid"],
            "side": req["side"],
            "fill_qty": fill_qty,
            "signal": signal,
            "signal_abs": abs(signal) if signal is not None else None,
            "spread": bbo["spread"],
            "imbalance": bbo["imbalance"],
            "directional_imbalance": directional_imbalance,
            "edge0": edge0,
            "exact_trigger": match["exact"],
            "trigger_delta_ms": match["delta_ms"],
            "reverse_executable": reverse_vol >= fill_qty,
        }

        for h in HORIZONS_MS:
            future = future_bbo(
                bbos,
                times,
                bbo["mono"] + h * 1_000_000,
            )
            if future is None:
                trade[f"signal_move_{h}"] = None
                trade[f"markout_{h}"] = None
                trade[f"reverse_markout_{h}"] = None
                continue

            trade[f"signal_move_{h}"] = sign * (future["mid"] - bbo["mid"])
            trade[f"markout_{h}"] = sign * (future["mid"] - vwap)
            trade[f"reverse_markout_{h}"] = (
                reverse_sign * (future["mid"] - reverse_price)
            )

        filled.append(trade)

    return {
        "run": run_dir.name,
        "requests": len(requests),
        "filled_reconstructed": len(filled),
        "unmatched_filled": len(unmatched),
        "non_exact": non_exact,
        "trades": filled,
    }


def filter_summary(trades, predicate):
    return summarize([x for x in trades if x["exact_trigger"] and predicate(x)])


def main():
    a = parse_args()
    root = a.evidence_root.resolve()

    runs = []
    all_trades = []

    for run_dir in sorted(root.glob("run-*-seed-*")):
        if not (run_dir / "raw.ndjson").exists():
            continue
        result = analyze_run(run_dir, a.lag, a.thresh)
        runs.append(result)
        all_trades.extend(result["trades"])

    if not runs:
        raise SystemExit(f"No completed run evidence found under {root}")

    exact = [x for x in all_trades if x["exact_trigger"]]

    print("JOB31_TAKER_TRIGGER_RECONSTRUCTION_V2")
    print("READ_ONLY=true")
    print(f"lag={a.lag} thresh={a.thresh:g} match_window_ms={MATCH_WINDOW_MS:g}")
    print(
        "METHOD=request is matched to a nearby two-sided BBO with identical "
        "executable-side price; exact matches must also satisfy the production "
        "LAG/THRESH trigger."
    )
    print()

    print("RECONSTRUCTION_QUALITY")
    for r in runs:
        exact_count = sum(1 for x in r["trades"] if x["exact_trigger"])
        print(
            f"{r['run']}: requests={r['requests']} "
            f"filled_reconstructed={r['filled_reconstructed']} "
            f"exact={exact_count} "
            f"non_exact={len(r['non_exact'])} "
            f"unmatched_filled={r['unmatched_filled']}"
        )

    total_reconstructed = len(all_trades)
    exact_pct = pct(len(exact), total_reconstructed)
    print(
        f"ALL: reconstructed={total_reconstructed} exact={len(exact)} "
        f"exact_pct={fmt(exact_pct,1)}%"
    )

    deltas = [abs(x["trigger_delta_ms"]) for x in exact]
    if deltas:
        deltas_sorted = sorted(deltas)
        p95 = deltas_sorted[min(len(deltas_sorted) - 1, math.ceil(0.95 * len(deltas_sorted)) - 1)]
        print(
            f"exact_abs_delta_ms mean={fmt(sum(deltas)/len(deltas),3)} "
            f"p95={fmt(p95,3)} max={fmt(max(deltas),3)}"
        )

    print()
    print("EXACT_TRIGGER_ALL")
    print_summary("all", summarize(exact))

    print()
    print("BY_SIDE")
    for side in ("B", "S"):
        print_summary(side, summarize([x for x in exact if x["side"] == side]))

    print()
    print("THRESHOLD_FILTER_DIAGNOSTIC")
    print(
        "cutoff trades qty spread dir_imb markout_1000 markout_5000 "
        "reversed_1000 reversed_5000"
    )
    for cutoff in THRESHOLDS:
        s = filter_summary(all_trades, lambda x, c=cutoff: x["signal_abs"] >= c)
        if not s:
            print(f"{cutoff} 0 0 n/a n/a n/a n/a n/a n/a")
            continue
        print(
            f"{cutoff} {s['trades']} {s['qty']} "
            f"{fmt(s['spread'])} {fmt(s['directional_imbalance'])} "
            f"{fmt(s['markout_1000'])} {fmt(s['markout_5000'])} "
            f"{fmt(s['reverse_markout_1000'])} {fmt(s['reverse_markout_5000'])}"
        )

    print()
    print("SPREAD_CAP_DIAGNOSTIC")
    print("max_spread trades qty markout_250 markout_1000 markout_5000")
    for cap in SPREAD_CAPS:
        s = filter_summary(all_trades, lambda x, c=cap: x["spread"] <= c)
        if not s:
            print(f"{cap} 0 0 n/a n/a n/a")
            continue
        print(
            f"{cap} {s['trades']} {s['qty']} "
            f"{fmt(s['markout_250'])} "
            f"{fmt(s['markout_1000'])} "
            f"{fmt(s['markout_5000'])}"
        )

    print()
    print("DIRECTIONAL_IMBALANCE_DIAGNOSTIC")
    print(
        "Interpretation: positive directional imbalance means BBO size supports "
        "the current Taker direction; negative opposes it."
    )
    print("min_dir_imb trades qty spread markout_250 markout_1000 markout_5000")
    for minimum in IMBALANCE_MINS:
        s = filter_summary(
            all_trades,
            lambda x, m=minimum: x["directional_imbalance"] >= m,
        )
        if not s:
            print(f"{minimum:.1f} 0 0 n/a n/a n/a n/a")
            continue
        print(
            f"{minimum:.1f} {s['trades']} {s['qty']} "
            f"{fmt(s['spread'])} "
            f"{fmt(s['markout_250'])} "
            f"{fmt(s['markout_1000'])} "
            f"{fmt(s['markout_5000'])}"
        )

    print()
    print("COMBINED_SPREAD_PLUS_IMBALANCE")
    print(
        "max_spread min_dir_imb trades qty markout_250 markout_1000 markout_5000"
    )
    for cap in (10, 15, 20):
        for minimum in (0.0, 0.2, 0.4):
            s = filter_summary(
                all_trades,
                lambda x, c=cap, m=minimum:
                    x["spread"] <= c
                    and x["directional_imbalance"] >= m,
            )
            if not s:
                print(f"{cap} {minimum:.1f} 0 0 n/a n/a n/a")
                continue
            print(
                f"{cap} {minimum:.1f} {s['trades']} {s['qty']} "
                f"{fmt(s['markout_250'])} "
                f"{fmt(s['markout_1000'])} "
                f"{fmt(s['markout_5000'])}"
            )

    print()
    print("CAUTION")
    print(
        "These are observational trade-level filters on historical fills. "
        "Skipping trades would change later position/risk/pacing, so this is "
        "evidence for choosing one bounded live test, not a simulated PnL claim."
    )

    output = {
        "lag": a.lag,
        "thresh": a.thresh,
        "runs": runs,
        "exact_summary": summarize(exact),
    }
    out = a.json_out or (root / "taker-trigger-reconstruction-v2.json")
    out.write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
    print()
    print(f"json_out={out}")
    print("NO_TUNING_PERFORMED")


if __name__ == "__main__":
    main()
