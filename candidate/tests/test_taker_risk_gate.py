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
        self.assertFalse(
            self.gate.allows_new_exposure()
        )

    def test_fresh_safe_and_trusted_transport_opens_gate(self):
        self.gate.mark_transport_trusted()

        accepted = self.gate.accept(
            risk_payload(1)
        )

        self.assertTrue(accepted)
        self.assertTrue(
            self.gate.allows_new_exposure()
        )

    def test_safe_is_not_enough_without_transport_trust(self):
        self.gate.accept(
            risk_payload(1)
        )

        self.assertFalse(
            self.gate.allows_new_exposure()
        )

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
            with self.subTest(
                state=expected_state
            ):
                self.gate.accept(payload)

                self.assertEqual(
                    self.gate.state,
                    expected_state,
                )
                self.assertFalse(
                    self.gate.allows_new_exposure()
                )

    def test_risk_at_1000ms_is_stale(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(
            risk_payload(10)
        )

        self.clock.advance(0.999)
        self.assertTrue(
            self.gate.allows_new_exposure()
        )

        self.clock.advance(0.001)
        self.assertFalse(
            self.gate.allows_new_exposure()
        )
        self.assertIsNone(
            self.gate.last_seq
        )

    def test_duplicate_and_older_sequence_do_not_refresh_freshness(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(
            risk_payload(10)
        )

        self.clock.advance(0.8)

        self.assertFalse(
            self.gate.accept(
                risk_payload(10)
            )
        )
        self.assertFalse(
            self.gate.accept(
                risk_payload(9)
            )
        )

        self.clock.advance(0.2)

        self.assertFalse(
            self.gate.allows_new_exposure()
        )

    def test_sequence_reset_is_accepted_only_after_stale_expiry(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(
            risk_payload(100)
        )

        self.assertFalse(
            self.gate.accept(
                risk_payload(1)
            )
        )

        self.clock.advance(1.0)

        self.assertTrue(
            self.gate.accept(
                risk_payload(1)
            )
        )
        self.assertTrue(
            self.gate.allows_new_exposure()
        )

    def test_disconnect_invalidates_safe_and_reconnect_alone_does_not_open(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(
            risk_payload(5)
        )

        self.assertTrue(
            self.gate.allows_new_exposure()
        )

        self.gate.mark_transport_untrusted()
        self.assertFalse(
            self.gate.allows_new_exposure()
        )

        self.gate.mark_transport_trusted()
        self.assertFalse(
            self.gate.allows_new_exposure()
        )

        self.gate.accept(
            risk_payload(6)
        )
        self.assertTrue(
            self.gate.allows_new_exposure()
        )

    def test_malformed_or_inconsistent_risk_fails_closed(self):
        self.gate.mark_transport_trusted()
        self.gate.accept(
            risk_payload(1)
        )

        invalid_payloads = [
            b"bad",
            risk_payload(
                2,
                feed="ZZZ9",
            ),
            risk_payload(
                2,
                soft=15,
                hard=6,
            ),
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
            with self.subTest(
                payload=payload
            ):
                self.gate.state = "SAFE"

                self.assertFalse(
                    self.gate.accept(payload)
                )
                self.assertFalse(
                    self.gate.allows_new_exposure()
                )


class FakeReply:
    def __init__(self, data):
        self.data = data


class FakeConnection:
    def __init__(self):
        self.requests = []

    async def request(
        self,
        subject,
        payload,
        timeout,
    ):
        self.requests.append(
            (subject, payload, timeout)
        )
        return FakeReply(
            b"EXCHANGE Y 0"
        )

    async def publish(
        self,
        _subject,
        _payload,
    ):
        return


class TakerRiskGateTests(
    unittest.IsolatedAsyncioTestCase
):
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
        self.gate.accept(
            risk_payload(seq)
        )

    async def test_take_sends_nothing_without_fresh_safe(self):
        await self.taker.take("B")

        self.assertEqual(
            self.connection.requests,
            [],
        )

    async def test_take_sends_when_gate_is_fresh_safe(self):
        self._open_gate()

        await self.taker.take("B")

        self.assertEqual(
            len(self.connection.requests),
            1,
        )

    async def test_second_gate_check_blocks_stale_order_dispatch(self):
        self._open_gate()

        self.clock.advance(1.0)

        await self.taker.take("B")

        self.assertEqual(
            self.connection.requests,
            [],
        )

    async def test_maybe_trade_checks_gate_before_signal_decision(self):
        # Fill the legacy momentum window with a clear upward move.
        self.taker.mids.clear()
        for value in range(
            self.taker.mids.maxlen
        ):
            self.taker.mids.append(
                500 + value * 20
            )

        await self.taker.maybe_trade()

        self.assertEqual(
            self.connection.requests,
            [],
        )

        self._open_gate()

        await self.taker.maybe_trade()

        self.assertEqual(
            len(self.connection.requests),
            1,
        )

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

        self.assertEqual(
            self.connection.requests,
            [],
        )


if __name__ == "__main__":
    unittest.main()
