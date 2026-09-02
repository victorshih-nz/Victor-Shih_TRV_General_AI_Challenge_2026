import asyncio
import unittest

from taker.taker import DeskRiskGate, Taker


def risk_payload(
    seq,
    *,
    feed="AAH6",
    position=0,
    soft=6,
    hard=15,
    state="SAFE",
    direction="X",
):
    return (
        f"1788264000000000000 {seq} {feed} "
        f"{position} {soft} {hard} "
        f"{state} {direction}"
    ).encode()


class FakeClock:
    def __init__(self):
        self.value = 100.0

    def __call__(self):
        return self.value

    def advance(self, seconds):
        self.value += seconds


class DeskRiskGateTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.gate = DeskRiskGate(
            "AAH6",
            clock=self.clock,
        )

    def test_gate_starts_closed(self):
        self.assertFalse(self.gate.allows_new_exposure())

    def test_fresh_safe_and_trusted_transport_opens_gate(self):
        self.gate.mark_transport_trusted()
        accepted = self.gate.accept(risk_payload(1))
        self.assertTrue(accepted)
        self.assertTrue(self.gate.allows_new_exposure())

    def test_safe_is_not_enough_without_transport_trust(self):
        self.gate.accept(risk_payload(1))
        self.assertFalse(self.gate.allows_new_exposure())

    def test_unknown_controlled_and_emergency_close_gate(self):
        cases = [
            (
                risk_payload(
                    1,
                    state="UNKNOWN",
                    direction="X",
                ),
                "UNKNOWN",
            ),
            (
                risk_payload(
                    2,
                    position=6,
                    state="CONTROLLED",
                    direction="S",
                ),
                "CONTROLLED",
            ),
            (
                risk_payload(
                    3,
                    position=-15,
                    state="EMERGENCY",
                    direction="B",
                ),
                "EMERGENCY",
            ),
        ]

        self.gate.mark_transport_trusted()

        for payload, expected_state in cases:
            with self.subTest(state=expected_state):
                self.gate.accept(payload)
                self.assertEqual(self.gate.state, expected_state)
                self.assertFalse(self.gate.allows_new_exposure())

    def test_risk_at_1000ms_is_stale(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(risk_payload(10))

        self.clock.advance(0.999)
        self.assertTrue(self.gate.allows_new_exposure())

        self.clock.advance(0.001)
        self.assertFalse(self.gate.allows_new_exposure())
        self.assertIsNone(self.gate.last_seq)

    def test_duplicate_and_older_sequence_do_not_refresh_freshness(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(risk_payload(10))

        self.clock.advance(0.8)

        self.assertFalse(self.gate.accept(risk_payload(10)))
        self.assertFalse(self.gate.accept(risk_payload(9)))

        self.clock.advance(0.2)
        self.assertFalse(self.gate.allows_new_exposure())

    def test_sequence_reset_is_accepted_only_after_stale_expiry(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(risk_payload(100))

        self.assertFalse(self.gate.accept(risk_payload(1)))

        self.clock.advance(1.0)

        self.assertTrue(self.gate.accept(risk_payload(1)))
        self.assertTrue(self.gate.allows_new_exposure())

    def test_disconnect_invalidates_safe_and_reconnect_alone_does_not_open(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(risk_payload(5))

        self.assertTrue(self.gate.allows_new_exposure())

        self.gate.mark_transport_untrusted()
        self.assertFalse(self.gate.allows_new_exposure())

        self.gate.mark_transport_trusted()
        self.assertFalse(self.gate.allows_new_exposure())

        self.gate.accept(risk_payload(6))
        self.assertTrue(self.gate.allows_new_exposure())

    def test_malformed_or_inconsistent_risk_fails_closed(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(risk_payload(1))

        invalid_payloads = [
            b"bad",
            risk_payload(2, feed="ZZZ9"),
            risk_payload(2, soft=15, hard=6),
            risk_payload(
                2,
                position=10,
                state="SAFE",
                direction="X",
            ),
            risk_payload(
                2,
                position=10,
                state="CONTROLLED",
                direction="B",
            ),
        ]

        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                self.gate.state = "SAFE"
                self.assertFalse(self.gate.accept(payload))
                self.assertFalse(self.gate.allows_new_exposure())


class FakeReply:
    def __init__(self, data):
        self.data = data


class FakeMessage:
    def __init__(self, data):
        self.data = data


class FakeConnection:
    def __init__(self, replies=None, error=None):
        self.requests = []
        self.replies = list(replies or [b"EXCHANGE Y 0"])
        self.error = error

    async def request(
        self,
        subject,
        payload,
        timeout,
    ):
        self.requests.append((subject, payload, timeout))

        if self.error is not None:
            raise self.error

        if self.replies:
            data = self.replies.pop(0)
        else:
            data = b"EXCHANGE Y 0"

        return FakeReply(data)

    async def publish(
        self,
        _subject,
        _payload,
    ):
        return


class TakerRiskGateTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.gate = DeskRiskGate(
            "AAH6",
            clock=self.clock,
        )
        self.connection = FakeConnection()
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600

    def _open_gate(self, seq=1):
        self.gate.mark_transport_trusted()
        self.gate.accept(risk_payload(seq))

    def _last_order_id(self):
        payload = self.connection.requests[-1][1].decode()
        return payload.split()[3]

    async def _own_t(self, oid, qty, side="B", match=1):
        await self.taker.on_execution(
            FakeMessage(
                (
                    f"1788264000000000001 T "
                    f"PYTKR001:{oid} OTHER001:ABCDEFGH "
                    f"{qty} 600 {match} {side}"
                ).encode()
            )
        )

    async def _risk(
        self,
        seq,
        *,
        position=0,
        state="SAFE",
        direction="X",
    ):
        await self.taker.on_risk(
            FakeMessage(
                risk_payload(
                    seq,
                    position=position,
                    state=state,
                    direction=direction,
                )
            )
        )

    async def _ack(
        self,
        oid,
        match_id,
        qty,
        risk_seq,
    ):
        await self.taker.on_accounted(
            FakeMessage(
                (
                    f"{oid} {match_id} "
                    f"{qty} {risk_seq}"
                ).encode()
            )
        )

    async def test_take_sends_nothing_without_fresh_safe(self):
        await self.taker.take("B")
        self.assertEqual(self.connection.requests, [])

    async def test_take_sends_when_gate_is_fresh_safe(self):
        self._open_gate()
        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

    async def test_second_gate_check_blocks_stale_order_dispatch(self):
        self._open_gate()
        self.clock.advance(1.0)

        await self.taker.take("B")

        self.assertEqual(self.connection.requests, [])

    async def test_maybe_trade_checks_gate_before_signal_decision(self):
        self.taker.mids.clear()
        for value in range(self.taker.mids.maxlen):
            self.taker.mids.append(500 + value * 20)

        await self.taker.maybe_trade()
        self.assertEqual(self.connection.requests, [])

        self._open_gate()

        await self.taker.maybe_trade()
        self.assertEqual(len(self.connection.requests), 1)

    async def test_controlled_risk_stops_new_taker_order(self):
        self._open_gate(seq=1)

        self.gate.accept(
            risk_payload(
                2,
                position=6,
                state="CONTROLLED",
                direction="S",
            )
        )

        await self.taker.take("B")

        self.assertEqual(self.connection.requests, [])

    async def test_filled_order_blocks_until_own_t_ack_and_associated_risk(self):
        self.connection = FakeConnection(
            replies=[
                b"EXCHANGE Y 3",
                b"EXCHANGE Y 0",
            ]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        first_oid = self._last_order_id()

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._own_t(first_oid, 3, match=11)

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._risk(
            2,
            position=3,
            state="SAFE",
            direction="X",
        )

        # A newer SAFE publication is not an accounting ACK.
        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._ack(
            first_oid,
            "11",
            3,
            2,
        )

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 2)

    async def test_newer_safe_heartbeats_without_matching_ack_do_not_release(self):
        self.connection = FakeConnection(
            replies=[
                b"EXCHANGE Y 3",
                b"EXCHANGE Y 0",
            ]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=100)

        await self.taker.take("B")
        oid = self._last_order_id()
        await self._own_t(oid, 3, match=21)

        # These can be delayed heartbeats that do not prove the T was
        # accounted. Even +2 sequence advancement must not release.
        await self._risk(101)
        await self._risk(102)

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)
        self.assertIsNotNone(self.taker.pending_exposure)

    async def test_ack_before_associated_risk_waits_for_that_risk_sequence(self):
        self.connection = FakeConnection(
            replies=[
                b"EXCHANGE Y 3",
                b"EXCHANGE Y 0",
            ]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        oid = self._last_order_id()

        await self._own_t(oid, 3, match=31)
        await self._ack(
            oid,
            "31",
            3,
            2,
        )

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._risk(
            2,
            position=3,
            state="SAFE",
            direction="X",
        )

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 2)

    async def test_ack_and_risk_before_own_t_wait_for_own_execution(self):
        self.connection = FakeConnection(
            replies=[
                b"EXCHANGE Y 3",
                b"EXCHANGE Y 0",
            ]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        oid = self._last_order_id()

        await self._risk(
            2,
            position=3,
            state="SAFE",
            direction="X",
        )
        await self._ack(
            oid,
            "41",
            3,
            2,
        )

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._own_t(oid, 3, match=41)

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 2)

    async def test_partial_authoritative_fills_require_all_t_and_all_acks(self):
        self.connection = FakeConnection(
            replies=[
                b"EXCHANGE Y 3",
                b"EXCHANGE Y 0",
            ]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        oid = self._last_order_id()

        await self._own_t(oid, 1, match=51)
        await self._risk(
            2,
            position=1,
            state="SAFE",
            direction="X",
        )
        await self._ack(
            oid,
            "51",
            1,
            2,
        )

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._own_t(oid, 2, match=52)
        await self._ack(
            oid,
            "52",
            2,
            3,
        )

        # ACK proves Hedger published risk seq 3, but Taker must process
        # that risk snapshot before releasing.
        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

        await self._risk(
            3,
            position=3,
            state="SAFE",
            direction="X",
        )

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 2)

    async def test_duplicate_t_and_ack_do_not_double_count(self):
        self.connection = FakeConnection(
            replies=[b"EXCHANGE Y 3"]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        oid = self._last_order_id()

        await self._own_t(oid, 3, match=61)
        await self._own_t(oid, 3, match=61)

        await self._ack(oid, "61", 3, 2)
        await self._ack(oid, "61", 3, 2)

        pending = self.taker.pending_exposure
        self.assertEqual(pending["confirmed_fill"], 3)
        self.assertEqual(pending["accounted_fill"], 3)

    async def test_reconciled_controlled_risk_clears_order_barrier_but_gate_stays_closed(self):
        self.connection = FakeConnection(
            replies=[
                b"EXCHANGE Y 3",
                b"EXCHANGE Y 0",
            ]
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        oid = self._last_order_id()
        await self._own_t(oid, 3, match=71)
        await self._ack(oid, "71", 3, 2)

        await self._risk(
            2,
            position=6,
            state="CONTROLLED",
            direction="S",
        )

        self.assertIsNone(self.taker.pending_exposure)
        self.assertFalse(self.gate.allows_new_exposure())

        await self.taker.take("B")
        self.assertEqual(len(self.connection.requests), 1)

    async def test_t_before_reply_is_supported_and_still_requires_ack_and_risk(self):
        class TBeforeReplyConnection(FakeConnection):
            def __init__(self, taker):
                super().__init__()
                self.taker = taker

            async def request(self, subject, payload, timeout):
                self.requests.append((subject, payload, timeout))
                oid = payload.decode().split()[3]

                await self.taker.on_execution(
                    FakeMessage(
                        (
                            f"1788264000000000001 T "
                            f"PYTKR001:{oid} OTHER001:ABCDEFGH "
                            f"3 600 81 B"
                        ).encode()
                    )
                )

                return FakeReply(b"EXCHANGE Y 3")

        self._open_gate(seq=1)
        connection = TBeforeReplyConnection(self.taker)
        self.taker.nc = connection
        self.connection = connection

        await self.taker.take("B")
        oid = self._last_order_id()

        self.assertIsNotNone(self.taker.pending_exposure)
        self.assertEqual(
            self.taker.pending_exposure["confirmed_fill"],
            3,
        )

        await self._ack(oid, "81", 3, 2)
        await self._risk(
            2,
            position=3,
            state="SAFE",
            direction="X",
        )

        self.assertIsNone(self.taker.pending_exposure)

    async def test_request_timeout_keeps_causal_barrier_closed(self):
        self.connection = FakeConnection(
            error=asyncio.TimeoutError()
        )
        self.taker = Taker(
            self.connection,
            risk_gate=self.gate,
        )
        self.taker.best_bid = 590
        self.taker.best_ask = 600
        self._open_gate(seq=1)

        await self.taker.take("B")
        await self.taker.take("B")

        self.assertEqual(len(self.connection.requests), 1)
        self.assertIsNotNone(self.taker.pending_exposure)
        self.assertTrue(
            self.taker.pending_exposure["uncertain"]
        )


if __name__ == "__main__":
    unittest.main()
