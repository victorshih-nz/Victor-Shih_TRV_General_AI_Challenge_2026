#!/usr/bin/env python3
"""Momentum taker strategy with a minimal Hedger SAFE gate.

The legacy momentum and order logic is unchanged. New exposure is permitted only
while a fresh Hedger desk-risk message says SAFE and the NATS transport is
trusted.

Job 2.2D adds a causal exposure barrier: after a filled Taker order, no new
exposure may be dispatched until the Taker has observed its own authoritative T
execution, Hedger has explicitly acknowledged accounting the same execution
match(es), and the associated desk-risk publication has been processed. This
prevents rapid reuse of a stale SAFE snapshot.

Config (env):
  NATS_URL        default nats://127.0.0.1:4222
  TAKER_FEED      contract to trade            (default BTH6)
  TAKER_SENDER    8-char sender tag            (default PYTKR001)
  TAKER_CLIP      order size per trade         (default 3)
  TAKER_MAX_POS   max absolute position        (default 30)
  TAKER_THRESH    mid move (price units) that triggers a trade (default 10)
  TAKER_LAG       how many BBO updates back the move is measured (default 5)
  TAKER_RUN       seconds to run               (default 20)
"""

import asyncio
import collections
import os
import random
import time

import nats


NATS_URL = os.environ.get("NATS_URL", "nats://127.0.0.1:4222")
FEED = os.environ.get("TAKER_FEED", "BTH6")
SENDER = os.environ.get("TAKER_SENDER", "PYTKR001")
CLIP = int(os.environ.get("TAKER_CLIP", "3"))
MAX_POS = int(os.environ.get("TAKER_MAX_POS", "30"))
THRESH = int(os.environ.get("TAKER_THRESH", "10"))
LAG = int(os.environ.get("TAKER_LAG", "5"))
RUN_S = float(os.environ.get("TAKER_RUN", "20"))

RISK_STALE_SECONDS = 1.0
VALID_RISK_STATES = {
    "UNKNOWN",
    "SAFE",
    "CONTROLLED",
    "EMERGENCY",
}
VALID_HEDGE_DIRECTIONS = {"B", "S", "X"}


class DeskRiskGate:
    """Fail-closed consumer of Hedger desk-risk messages."""

    def __init__(
        self,
        feed,
        stale_seconds=RISK_STALE_SECONDS,
        clock=time.monotonic,
    ):
        self.feed = str(feed)
        self.stale_seconds = float(stale_seconds)
        self.clock = clock

        self.transport_trusted = False
        self.last_seq = None
        self.last_valid_received_at = None
        self.state = "UNKNOWN"

    def mark_transport_trusted(self):
        self.transport_trusted = True

    def mark_transport_untrusted(self):
        self.transport_trusted = False
        self.state = "UNKNOWN"

    def _expire_stale_if_needed(self):
        if self.last_valid_received_at is None:
            return

        if (
            self.clock() - self.last_valid_received_at
            >= self.stale_seconds
        ):
            self.last_seq = None
            self.last_valid_received_at = None
            self.state = "UNKNOWN"

    def _invalidate_risk(self):
        self.state = "UNKNOWN"

    @staticmethod
    def _decode_payload(raw_payload):
        if isinstance(raw_payload, bytes):
            return raw_payload.decode("utf-8")
        if isinstance(raw_payload, str):
            return raw_payload
        raise ValueError("desk risk payload must be text")

    @staticmethod
    def _expected_state(net_position, soft_limit, hard_limit):
        absolute_position = abs(net_position)

        if absolute_position < soft_limit:
            return "SAFE"
        if absolute_position < hard_limit:
            return "CONTROLLED"
        return "EMERGENCY"

    @staticmethod
    def _expected_direction(net_position, state):
        if state in {"UNKNOWN", "SAFE"}:
            return "X"
        return "S" if net_position > 0 else "B"

    def accept(self, raw_payload):
        """Accept one valid, newer desk-risk message.

        Returns True only when the message became the new trusted risk snapshot.
        Invalid or out-of-order evidence never refreshes freshness.
        """

        self._expire_stale_if_needed()

        try:
            payload = self._decode_payload(raw_payload)
            fields = payload.strip().split()

            if len(fields) != 8:
                raise ValueError("desk risk must contain exactly 8 fields")

            timestamp_ns = int(fields[0])
            sequence = int(fields[1])
            feed = fields[2]
            net_position = int(fields[3])
            soft_limit = int(fields[4])
            hard_limit = int(fields[5])
            state = fields[6]
            direction = fields[7]

            if timestamp_ns <= 0:
                raise ValueError("desk risk timestamp must be positive")
            if sequence < 0:
                raise ValueError("desk risk sequence must be non-negative")
            if feed != self.feed:
                raise ValueError("desk risk feed mismatch")
            if soft_limit <= 0 or hard_limit <= 0:
                raise ValueError("desk risk limits must be positive")
            if soft_limit >= hard_limit:
                raise ValueError("desk risk soft limit must be below hard limit")
            if state not in VALID_RISK_STATES:
                raise ValueError("invalid desk risk state")
            if direction not in VALID_HEDGE_DIRECTIONS:
                raise ValueError("invalid desk risk direction")

            if state != "UNKNOWN":
                expected_state = self._expected_state(
                    net_position,
                    soft_limit,
                    hard_limit,
                )
                if state != expected_state:
                    raise ValueError("desk risk state is inconsistent")

            expected_direction = self._expected_direction(
                net_position,
                state,
            )
            if direction != expected_direction:
                raise ValueError("desk risk direction is inconsistent")

        except (UnicodeDecodeError, ValueError, TypeError):
            self._invalidate_risk()
            return False

        if (
            self.last_seq is not None
            and sequence <= self.last_seq
        ):
            return False

        self.last_seq = sequence
        self.last_valid_received_at = self.clock()
        self.state = state
        return True

    def allows_new_exposure(self):
        self._expire_stale_if_needed()

        return (
            self.transport_trusted
            and self.last_valid_received_at is not None
            and self.state == "SAFE"
        )


class Taker:
    def __init__(self, nc, risk_gate=None):
        self.nc = nc
        self.risk_gate = (
            risk_gate
            if risk_gate is not None
            else DeskRiskGate(FEED)
        )

        self.oid = random.randint(
            0,
            80_000_000,
        )  # avoid id clashes across runs
        self.best_bid = None
        self.best_ask = None
        self.mids = collections.deque(maxlen=LAG + 1)
        self.position = 0
        self.cash = 0.0
        self.fills = 0
        self.send_lock = asyncio.Lock()

        # At most one exposure-creating Taker order may be causally unresolved.
        #
        # The barrier is installed before dispatch. For a positive Y reply it
        # remains until all three independent pieces of evidence agree:
        #   1) own authoritative T volume reaches the exchange-confirmed fill;
        #   2) Hedger ACKs the same order/match volume after accounting it;
        #   3) Taker has processed desk.risk at least through the ACK risk seq.
        #
        # Risk publication sequence is freshness evidence only. It is never
        # treated as an execution/accounting acknowledgement by itself.
        #
        # A request timeout keeps the barrier closed because the exchange
        # outcome is unknown.
        self.pending_exposure = None

    def next_oid(self):
        self.oid += 1
        return f"{self.oid:08d}"

    def mid(self):
        if self.best_bid is None or self.best_ask is None:
            return None
        return (self.best_bid + self.best_ask) / 2

    def _try_release_pending_exposure(self):
        pending = self.pending_exposure
        if pending is None or pending["uncertain"]:
            return

        expected_fill = pending["expected_fill"]
        if expected_fill is None or expected_fill <= 0:
            return

        if pending["confirmed_fill"] != expected_fill:
            return

        if pending["accounted_fill"] != expected_fill:
            return

        required_risk_seq = pending["accounted_risk_seq"]
        if required_risk_seq is None:
            return

        current_risk_seq = self.risk_gate.last_seq
        if (
            current_risk_seq is None
            or current_risk_seq < required_risk_seq
        ):
            return

        # Causal reconciliation is complete. DeskRiskGate remains the final
        # exposure gate: if the reconciled state is CONTROLLED/EMERGENCY/
        # UNKNOWN, new exposure stays blocked even though this order-specific
        # barrier can now be cleared.
        self.pending_exposure = None

    async def on_risk(self, msg):
        accepted = self.risk_gate.accept(msg.data)
        if accepted:
            self._try_release_pending_exposure()

    async def on_execution(self, msg):
        """Observe authoritative own T executions for causal pacing only."""

        pending = self.pending_exposure
        if pending is None:
            return

        try:
            fields = msg.data.decode("ascii").split()
            if len(fields) != 8 or fields[1] != "T":
                return

            incoming_public_id = fields[2]
            expected_public_id = f"{SENDER}:{pending['order_id']}"
            if incoming_public_id != expected_public_id:
                return

            quantity = int(fields[4])
            match_id = fields[6]
            side = fields[7]

            if quantity <= 0 or not match_id:
                pending["uncertain"] = True
                return
            if side != pending["side"]:
                pending["uncertain"] = True
                return

        except (UnicodeDecodeError, ValueError, TypeError):
            pending["uncertain"] = True
            return

        if match_id in pending["own_match_ids"]:
            return

        pending["own_match_ids"].add(match_id)
        pending["confirmed_fill"] += quantity

        expected_fill = pending["expected_fill"]
        if (
            expected_fill is not None
            and pending["confirmed_fill"] > expected_fill
        ):
            pending["uncertain"] = True
            return

        self._try_release_pending_exposure()

    async def on_accounted(self, msg):
        """Consume Hedger's explicit Taker accounting acknowledgement."""

        pending = self.pending_exposure
        if pending is None:
            return

        try:
            fields = msg.data.decode("ascii").split()
            if len(fields) != 4:
                raise ValueError("accounting ACK must contain 4 fields")

            order_id, match_id, qty_text, risk_seq_text = fields
            if order_id != pending["order_id"]:
                return

            quantity = int(qty_text)
            risk_seq = int(risk_seq_text)

            if quantity <= 0 or risk_seq < 0 or not match_id:
                raise ValueError("invalid accounting ACK values")

        except (UnicodeDecodeError, ValueError, TypeError):
            pending["uncertain"] = True
            return

        previous = pending["accounted_matches"].get(match_id)
        if previous is not None:
            if previous != (quantity, risk_seq):
                pending["uncertain"] = True
            return

        pending["accounted_matches"][match_id] = (
            quantity,
            risk_seq,
        )
        pending["accounted_fill"] += quantity

        previous_required_seq = pending["accounted_risk_seq"]
        if (
            previous_required_seq is None
            or risk_seq > previous_required_seq
        ):
            pending["accounted_risk_seq"] = risk_seq

        expected_fill = pending["expected_fill"]
        if (
            expected_fill is not None
            and pending["accounted_fill"] > expected_fill
        ):
            pending["uncertain"] = True
            return

        self._try_release_pending_exposure()

    async def on_bbo(self, msg):
        # payload:
        # "<ts> <FEED> <bid_px> <bid_vol> <ask_px> <ask_vol>",
        # '-' if empty
        f = msg.data.decode().split()
        if len(f) < 6:
            return

        self.best_bid = (
            None
            if f[2] == "-"
            else int(f[2])
        )
        self.best_ask = (
            None
            if f[4] == "-"
            else int(f[4])
        )

        m = self.mid()
        if m is None:
            return

        self.mids.append(m)
        await self.maybe_trade()

    async def maybe_trade(self):
        if not self.risk_gate.allows_new_exposure():
            return

        if self.pending_exposure is not None:
            return

        if len(self.mids) < self.mids.maxlen:
            return

        past, now = self.mids[0], self.mids[-1]

        if (
            now - past >= THRESH
            and self.position < MAX_POS
            and self.best_ask is not None
        ):
            await self.take("B")
        elif (
            past - now >= THRESH
            and self.position > -MAX_POS
            and self.best_bid is not None
        ):
            await self.take("S")

    async def take(self, side):
        async with self.send_lock:
            # Second gate check immediately before request dispatch.
            if not self.risk_gate.allows_new_exposure():
                return

            # A prior exposure order must be fully reconciled through own T and
            # a newer Hedger risk publication before another order is allowed.
            if self.pending_exposure is not None:
                return

            px = (
                self.best_ask
                if side == "B"
                else self.best_bid
            )
            if px is None:
                return

            oid = self.next_oid()
            order = (
                f"{SENDER} A {FEED} {oid} "
                f"{side} {CLIP} {px} F"
            )

            pending = {
                "order_id": oid,
                "side": side,
                "pre_risk_seq": self.risk_gate.last_seq,
                "expected_fill": None,
                "confirmed_fill": 0,
                "own_match_ids": set(),
                "accounted_fill": 0,
                "accounted_matches": {},
                "accounted_risk_seq": None,
                "uncertain": False,
            }
            self.pending_exposure = pending

            try:
                reply = await self.nc.request(
                    f"ex.req.{SENDER}",
                    order.encode(),
                    timeout=1.0,
                )
            except Exception:
                # Outcome unknown: intentionally keep pending_exposure set.
                pending["uncertain"] = True
                return

            try:
                parts = reply.data.decode().split()
                if len(parts) < 3:
                    raise ValueError("order reply is incomplete")

                status = parts[1]

                if status == "N":
                    if pending["confirmed_fill"] == 0:
                        self.pending_exposure = None
                    else:
                        pending["uncertain"] = True
                    return

                if status != "Y":
                    raise ValueError("unknown order reply status")

                filled = int(parts[2])
                if filled < 0:
                    raise ValueError("negative fill quantity")

            except (UnicodeDecodeError, ValueError, TypeError):
                pending["uncertain"] = True
                return

            pending["expected_fill"] = filled

            if (
                pending["confirmed_fill"] > filled
                or pending["accounted_fill"] > filled
            ):
                pending["uncertain"] = True
                return

            # Preserve legacy local PnL/status accounting. Authoritative desk
            # position and the causal release barrier remain T/ACK/risk-driven.
            self.apply_fill(side, filled, px)
            if filled > 0:
                self.fills += 1

            if filled == 0:
                # F had no immediate execution, so no exposure was created.
                # A contradictory late T would be a protocol/runtime fault.
                self.pending_exposure = None
                return

            self._try_release_pending_exposure()

    def apply_fill(self, side, qty, px):
        signed = qty if side == "B" else -qty
        self.position += signed
        self.cash -= signed * px

    def pnl(self):
        m = self.mid()
        return self.cash + (
            self.position * m
            if m is not None
            else 0
        )

    async def publish_status(self):
        s = (
            f"pos={self.position} cash={self.cash:.0f} "
            f"pnl={self.pnl():.0f} fills={self.fills}"
        )
        print(f"[taker] {s}", flush=True)
        await self.nc.publish(
            f"strat.{SENDER}.status",
            s.encode(),
        )

    async def reporter(self):
        while True:
            await asyncio.sleep(1.0)
            await self.publish_status()


async def main():
    risk_gate = DeskRiskGate(FEED)

    async def on_disconnected():
        risk_gate.mark_transport_untrusted()

    async def on_reconnected():
        risk_gate.mark_transport_trusted()

    async def on_closed():
        risk_gate.mark_transport_untrusted()

    nc = await nats.connect(
        NATS_URL,
        disconnected_cb=on_disconnected,
        reconnected_cb=on_reconnected,
        closed_cb=on_closed,
    )

    t = Taker(
        nc,
        risk_gate=risk_gate,
    )

    # Install risk, exact own execution, and Hedger accounting ACK before
    # market data. Keep transport trust closed until all subscriptions flush.
    await nc.subscribe(
        f"desk.risk.{FEED}",
        cb=t.on_risk,
    )
    await nc.subscribe(
        f"ex.md.{FEED}.{SENDER}",
        cb=t.on_execution,
    )
    await nc.subscribe(
        f"desk.accounted.{FEED}.{SENDER}",
        cb=t.on_accounted,
    )
    await nc.subscribe(
        f"ex.bbo.{FEED}",
        cb=t.on_bbo,
    )
    await nc.flush()

    risk_gate.mark_transport_trusted()

    print(
        f"[taker] {SENDER} trading {FEED} "
        f"clip={CLIP} thresh={THRESH} "
        f"for {RUN_S}s",
        flush=True,
    )

    rep = asyncio.create_task(t.reporter())

    await asyncio.sleep(RUN_S)

    rep.cancel()
    await t.publish_status()

    print(
        f"[taker] final: position={t.position} "
        f"pnl={t.pnl():.0f} fills={t.fills}",
        flush=True,
    )

    await nc.drain()


if __name__ == "__main__":
    asyncio.run(main())
