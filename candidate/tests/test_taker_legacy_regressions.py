import asyncio
import os
import sys
import unittest
from pathlib import Path

CANDIDATE_ROOT = Path(__file__).resolve().parents[1]
if str(CANDIDATE_ROOT) not in sys.path:
    sys.path.insert(0, str(CANDIDATE_ROOT))

from taker.taker import Taker


class AlwaysOpenRiskGate:
    """Test-only gate for legacy order/fill regression mechanics."""

    def allows_new_exposure(self):
        return True


class FakeReply:
    def __init__(self, reply_text):
        self.data = reply_text.encode()


class FakeNATS:
    def __init__(self, reply_text):
        self.reply_text = reply_text
        self.subjects = []
        self.payloads = []

    async def request(self, subject, payload, timeout=1.0):
        self.subjects.append(subject)
        self.payloads.append(payload.decode())
        return FakeReply(self.reply_text)


class TakerLegacyRegressionTests(unittest.TestCase):
    def setUp(self):
        self.taker = Taker(
            None,
            risk_gate=AlwaysOpenRiskGate(),
        )
        self.taker.best_ask = 600
        self.taker.best_bid = 590
        self.taker.position = 0
        self.taker.cash = 0.0

    def _make_order_taker(self, reply_text):
        taker = Taker(
            FakeNATS(reply_text),
            risk_gate=AlwaysOpenRiskGate(),
        )
        taker.best_ask = 600
        taker.best_bid = 590
        return taker

    def test_take_uses_sender_specific_order_subject(self):
        sender = os.environ.get(
            "TAKER_SENDER",
            "PYTKR001",
        )
        taker = self._make_order_taker(
            "EXCHANGE Y 5",
        )

        asyncio.run(
            taker.take("B")
        )

        self.assertEqual(
            taker.nc.subjects[0],
            f"ex.req.{sender}",
        )
        self.assertTrue(
            taker.nc.payloads[0].startswith(
                f"{sender} A "
            )
        )

    def test_buy_fill_is_positive(self):
        self.taker.apply_fill(
            "B",
            5,
            600,
        )

        self.assertEqual(
            self.taker.position,
            5,
        )
        self.assertEqual(
            self.taker.cash,
            -3000.0,
        )

    def test_sell_fill_is_negative(self):
        self.taker.apply_fill(
            "S",
            5,
            600,
        )

        self.assertEqual(
            self.taker.position,
            -5,
        )
        self.assertEqual(
            self.taker.cash,
            3000.0,
        )

    def test_y_zero_applies_no_position_change(self):
        taker = self._make_order_taker(
            "EXCHANGE Y 0",
        )

        asyncio.run(
            taker.take("B")
        )

        self.assertEqual(
            taker.position,
            0,
        )
        self.assertEqual(
            taker.fills,
            0,
        )

    def test_partial_y_3_differs_from_requested_clip(self):
        taker = self._make_order_taker(
            "EXCHANGE Y 3",
        )

        asyncio.run(
            taker.take("B")
        )

        self.assertEqual(
            taker.position,
            3,
        )

    def test_full_y_5_applies_exact_quantity(self):
        taker = self._make_order_taker(
            "EXCHANGE Y 5",
        )

        asyncio.run(
            taker.take("S")
        )

        self.assertEqual(
            taker.position,
            -5,
        )

    def test_n_reject_applies_no_fill(self):
        taker = self._make_order_taker(
            "EXCHANGE N 100 malformed request",
        )

        asyncio.run(
            taker.take("B")
        )

        self.assertEqual(
            taker.position,
            0,
        )
        self.assertEqual(
            taker.fills,
            0,
        )

    def test_unrelated_taker_behavior_unchanged(self):
        self.taker.best_bid = 500
        self.taker.best_ask = 600

        self.assertEqual(
            self.taker.mid(),
            550.0,
        )


if __name__ == "__main__":
    unittest.main()
