import unittest

from hedger.hedger import (
    DEFAULT_DESK_HARD_POS,
    DEFAULT_DESK_SOFT_POS,
    DIRECTION_BUY,
    DIRECTION_NONE,
    DIRECTION_SELL,
    HEARTBEAT_SECONDS,
    RISK_CONTROLLED,
    RISK_EMERGENCY,
    RISK_SAFE,
    RISK_UNKNOWN,
    HedgerRuntime,
    classify_risk,
    load_config,
    parse_metadata,
)


BASE_ENV = {
    "NATS_URL": "nats://localhost:4222",
    "TAKER_FEED": "AAH6",
    "TAKER_SENDER": "TAKER001",
    "SENDER": "QUOTER01",
    "HEDGER_SENDER": "HEDGER01",
}


class HedgerConfigTests(unittest.TestCase):
    def test_load_config_uses_default_limits(self):
        config = load_config(BASE_ENV)

        self.assertEqual(config.nats_url, "nats://localhost:4222")
        self.assertEqual(config.feed, "AAH6")
        self.assertEqual(config.taker_sender, "TAKER001")
        self.assertEqual(config.quoter_sender, "QUOTER01")
        self.assertEqual(config.hedger_sender, "HEDGER01")
        self.assertEqual(
            config.soft_limit,
            DEFAULT_DESK_SOFT_POS,
        )
        self.assertEqual(
            config.hard_limit,
            DEFAULT_DESK_HARD_POS,
        )

    def test_load_config_accepts_explicit_limits(self):
        env = dict(BASE_ENV)
        env["DESK_SOFT_POS"] = "7"
        env["DESK_HARD_POS"] = "20"

        config = load_config(env)

        self.assertEqual(config.soft_limit, 7)
        self.assertEqual(config.hard_limit, 20)

    def test_missing_required_config_fails(self):
        env = dict(BASE_ENV)
        del env["NATS_URL"]

        with self.assertRaises(ValueError):
            load_config(env)

    def test_feed_must_be_four_characters(self):
        env = dict(BASE_ENV)
        env["TAKER_FEED"] = "ABC"

        with self.assertRaises(ValueError):
            load_config(env)

    def test_sender_must_be_eight_characters(self):
        env = dict(BASE_ENV)
        env["HEDGER_SENDER"] = "SHORT"

        with self.assertRaises(ValueError):
            load_config(env)

    def test_three_senders_must_be_distinct(self):
        env = dict(BASE_ENV)
        env["HEDGER_SENDER"] = env["TAKER_SENDER"]

        with self.assertRaises(ValueError):
            load_config(env)

    def test_soft_limit_must_be_less_than_hard_limit(self):
        env = dict(BASE_ENV)
        env["DESK_SOFT_POS"] = "15"
        env["DESK_HARD_POS"] = "15"

        with self.assertRaises(ValueError):
            load_config(env)

    def test_limits_must_be_positive_integers(self):
        invalid_values = [
            ("DESK_SOFT_POS", "0"),
            ("DESK_SOFT_POS", "-1"),
            ("DESK_HARD_POS", "abc"),
        ]

        for name, value in invalid_values:
            with self.subTest(name=name, value=value):
                env = dict(BASE_ENV)
                env[name] = value

                with self.assertRaises(ValueError):
                    load_config(env)


class HedgerRiskClassificationTests(unittest.TestCase):
    def test_untrusted_accounting_is_unknown(self):
        assessment = classify_risk(
            net_position=12,
            soft_limit=6,
            hard_limit=15,
            accounting_trusted=False,
        )

        self.assertEqual(assessment.state, RISK_UNKNOWN)
        self.assertEqual(assessment.direction, DIRECTION_NONE)

    def test_safe_zone_has_no_hedge_direction(self):
        for position in (-5, 0, 5):
            with self.subTest(position=position):
                assessment = classify_risk(
                    position,
                    6,
                    15,
                )

                self.assertEqual(
                    assessment.state,
                    RISK_SAFE,
                )
                self.assertEqual(
                    assessment.direction,
                    DIRECTION_NONE,
                )

    def test_soft_limit_enters_controlled(self):
        long_assessment = classify_risk(6, 6, 15)
        short_assessment = classify_risk(-6, 6, 15)

        self.assertEqual(
            long_assessment.state,
            RISK_CONTROLLED,
        )
        self.assertEqual(
            long_assessment.direction,
            DIRECTION_SELL,
        )

        self.assertEqual(
            short_assessment.state,
            RISK_CONTROLLED,
        )
        self.assertEqual(
            short_assessment.direction,
            DIRECTION_BUY,
        )

    def test_position_inside_controlled_zone(self):
        self.assertEqual(
            classify_risk(14, 6, 15).state,
            RISK_CONTROLLED,
        )
        self.assertEqual(
            classify_risk(-14, 6, 15).state,
            RISK_CONTROLLED,
        )

    def test_hard_limit_enters_emergency(self):
        long_assessment = classify_risk(15, 6, 15)
        short_assessment = classify_risk(-15, 6, 15)

        self.assertEqual(
            long_assessment.state,
            RISK_EMERGENCY,
        )
        self.assertEqual(
            long_assessment.direction,
            DIRECTION_SELL,
        )

        self.assertEqual(
            short_assessment.state,
            RISK_EMERGENCY,
        )
        self.assertEqual(
            short_assessment.direction,
            DIRECTION_BUY,
        )


class HedgerMetadataTests(unittest.TestCase):
    def test_valid_metadata_is_accepted(self):
        metadata = parse_metadata(
            "AAH6",
            "ticksize=1 ref_price=600 band=100 "
            "min_volume=1 max_volume=50 "
            "position_limit=100 max_tps=20",
        )

        self.assertEqual(str(metadata.tick_size), "1")
        self.assertEqual(str(metadata.ref_price), "600")
        self.assertEqual(str(metadata.band), "100")

    def test_metadata_requires_positive_ticksize(self):
        for payload in (
            "ref_price=600 band=100",
            "ticksize=0 ref_price=600 band=100",
            "ticksize=bad ref_price=600 band=100",
        ):
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    parse_metadata("AAH6", payload)

    def test_metadata_rejects_negative_band(self):
        with self.assertRaises(ValueError):
            parse_metadata(
                "AAH6",
                "ticksize=1 ref_price=600 band=-1",
            )

    def test_heartbeat_target_is_within_contract(self):
        self.assertGreater(HEARTBEAT_SECONDS, 0)
        self.assertLessEqual(HEARTBEAT_SECONDS, 0.2)


class FakeEntry:
    def __init__(self, value):
        self.value = value


class FakeKeyValue:
    def __init__(self, value):
        self.value = value

    async def get(self, _key):
        return FakeEntry(self.value)


class FakeJetStream:
    def __init__(self, value):
        self.value = value

    async def key_value(self, bucket):
        if bucket != "EX_META":
            raise RuntimeError("unexpected bucket")
        return FakeKeyValue(self.value)


class FakeMessage:
    def __init__(self, data):
        self.data = data


class FakeConnection:
    def __init__(self):
        self.subscriptions = {}
        self.published = []
        self.flush_count = 0
        self.metadata_value = (
            b"ticksize=1 ref_price=600 band=100 "
            b"min_volume=1 max_volume=50 "
            b"position_limit=100 max_tps=20"
        )

    def jetstream(self):
        return FakeJetStream(self.metadata_value)

    async def subscribe(self, subject, cb):
        self.subscriptions[subject] = cb

    async def publish(self, subject, data):
        self.published.append((subject, data))

    async def flush(self):
        self.flush_count += 1


class HedgerRuntimeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.config = load_config(BASE_ENV)
        self.connection = FakeConnection()
        self.runtime = HedgerRuntime(
            self.config,
            connection=self.connection,
        )

    async def asyncSetUp(self):
        pass

    async def test_start_installs_exact_subscriptions_and_first_safe(self):
        await self.runtime.start(start_heartbeat=False)

        self.assertEqual(
            set(self.connection.subscriptions),
            {
                "ex.md.AAH6.TAKER001",
                "ex.md.AAH6.QUOTER01",
                "ex.md.AAH6.HEDGER01",
                "ex.bbo.AAH6",
            },
        )
        self.assertTrue(self.runtime.startup_established)
        self.assertTrue(self.runtime.ready)

        self.assertGreaterEqual(
            self.connection.flush_count,
            2,
        )

        subject, raw_payload = self.connection.published[0]
        fields = raw_payload.decode("ascii").split()

        self.assertEqual(subject, "desk.risk.AAH6")
        self.assertEqual(fields[1], "1")
        self.assertEqual(fields[2], "AAH6")
        self.assertEqual(fields[3], "0")
        self.assertEqual(fields[4], "6")
        self.assertEqual(fields[5], "15")
        self.assertEqual(fields[6], "SAFE")
        self.assertEqual(fields[7], "X")

    async def test_execution_updates_position_and_publishes_controlled(self):
        await self.runtime.start(start_heartbeat=False)

        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        await callback(
            FakeMessage(
                b"1700000000000000000 T "
                b"TAKER001:BUY00001 EXT00001:SELL0001 "
                b"6 600 42 B"
            )
        )

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            6,
        )

        fields = self.connection.published[-1][1].decode(
            "ascii"
        ).split()

        self.assertEqual(fields[1], "2")
        self.assertEqual(fields[3], "6")
        self.assertEqual(fields[6], "CONTROLLED")
        self.assertEqual(fields[7], "S")

    async def test_uncertainty_publishes_unknown_and_preserves_position(self):
        await self.runtime.start(start_heartbeat=False)

        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]

        await callback(
            FakeMessage(
                b"1700000000000000000 T "
                b"TAKER001:BUY00001 EXT00001:SELL0001 "
                b"5 600 42 B"
            )
        )

        await callback(
            FakeMessage(
                b"1700000000000000001 T "
                b"OTHER001:BUY00002 EXT00001:SELL0002 "
                b"1 600 43 B"
            )
        )

        self.assertFalse(
            self.runtime.accounting.accounting_trusted
        )
        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            5,
        )

        fields = self.connection.published[-1][1].decode(
            "ascii"
        ).split()

        self.assertEqual(fields[3], "5")
        self.assertEqual(fields[6], "UNKNOWN")
        self.assertEqual(fields[7], "X")

    async def test_disconnect_after_ready_never_recovers_safe(self):
        await self.runtime.start(start_heartbeat=False)

        await self.runtime._on_disconnected()

        self.assertFalse(self.runtime.ready)
        self.assertFalse(
            self.runtime.accounting.accounting_trusted
        )

        await self.runtime._on_reconnected()

        fields = self.connection.published[-1][1].decode(
            "ascii"
        ).split()

        self.assertEqual(fields[1], "2")
        self.assertEqual(fields[6], "UNKNOWN")
        self.assertEqual(fields[7], "X")
        self.assertFalse(self.runtime.ready)

    async def test_pre_ready_sender_event_loses_startup_trust(self):
        await self.runtime._handle_execution(
            "TAKER001",
            (
                b"1700000000000000000 T "
                b"TAKER001:BUY00001 EXT00001:SELL0001 "
                b"1 600 42 B"
            ),
        )

        self.assertFalse(
            self.runtime.accounting.accounting_trusted
        )
        self.assertFalse(self.runtime.startup_established)
        self.assertEqual(self.connection.published, [])

    async def test_every_risk_publish_increments_sequence(self):
        await self.runtime.start(start_heartbeat=False)

        self.assertEqual(self.runtime.sequence, 1)

        published = await self.runtime.publish_risk()

        self.assertTrue(published)
        self.assertEqual(self.runtime.sequence, 2)

        seqs = [
            int(payload.decode("ascii").split()[1])
            for _, payload in self.connection.published
        ]
        self.assertEqual(seqs, [1, 2])


if __name__ == "__main__":
    unittest.main()
