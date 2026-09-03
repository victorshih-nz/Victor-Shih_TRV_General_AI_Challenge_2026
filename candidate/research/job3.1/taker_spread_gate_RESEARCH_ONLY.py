import unittest

from taker.taker import MAX_SPREAD, THRESH, Taker


class AlwaysOpenRiskGate:
    """Test-only gate for spread-policy behavior."""

    def __init__(self):
        self.last_seq = 1

    def allows_new_exposure(self):
        return True


class FakeReply:
    def __init__(self, data=b"EXCHANGE Y 0"):
        self.data = data


class FakeConnection:
    def __init__(self):
        self.requests = []

    async def request(self, subject, payload, timeout):
        self.requests.append((subject, payload, timeout))
        return FakeReply()


class TakerSpreadGateTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.connection = FakeConnection()
        self.taker = Taker(
            self.connection,
            risk_gate=AlwaysOpenRiskGate(),
        )

    def _set_buy_momentum(self):
        self.taker.mids.clear()
        for _ in range(self.taker.mids.maxlen - 1):
            self.taker.mids.append(500.0)
        self.taker.mids.append(500.0 + THRESH)

    def _set_sell_momentum(self):
        self.taker.mids.clear()
        for _ in range(self.taker.mids.maxlen - 1):
            self.taker.mids.append(500.0 + THRESH)
        self.taker.mids.append(500.0)

    async def test_wide_spread_blocks_buy_momentum(self):
        self.taker.best_bid = 1000
        self.taker.best_ask = 1000 + MAX_SPREAD + 1
        self._set_buy_momentum()

        await self.taker.maybe_trade()

        self.assertEqual(self.connection.requests, [])

    async def test_wide_spread_blocks_sell_momentum(self):
        self.taker.best_bid = 1000
        self.taker.best_ask = 1000 + MAX_SPREAD + 1
        self._set_sell_momentum()

        await self.taker.maybe_trade()

        self.assertEqual(self.connection.requests, [])

    async def test_spread_equal_to_limit_preserves_buy_behavior(self):
        self.taker.best_bid = 1000
        self.taker.best_ask = 1000 + MAX_SPREAD
        self._set_buy_momentum()

        await self.taker.maybe_trade()

        self.assertEqual(len(self.connection.requests), 1)
        order = self.connection.requests[0][1].decode().split()
        self.assertEqual(order[4], "B")
        self.assertEqual(int(order[6]), self.taker.best_ask)

    async def test_narrow_spread_preserves_sell_behavior(self):
        self.taker.best_bid = 1000
        self.taker.best_ask = 1000 + max(0, MAX_SPREAD - 1)
        self._set_sell_momentum()

        await self.taker.maybe_trade()

        self.assertEqual(len(self.connection.requests), 1)
        order = self.connection.requests[0][1].decode().split()
        self.assertEqual(order[4], "S")
        self.assertEqual(int(order[6]), self.taker.best_bid)

    async def test_take_rechecks_wide_spread_before_dispatch(self):
        self.taker.best_bid = 1000
        self.taker.best_ask = 1000 + MAX_SPREAD + 1

        await self.taker.take("B")

        self.assertEqual(self.connection.requests, [])


if __name__ == "__main__":
    unittest.main()
