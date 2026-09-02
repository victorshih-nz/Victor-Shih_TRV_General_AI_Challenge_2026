import asyncio
import base64
import json
import unittest
from unittest.mock import patch
from decimal import Decimal

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
    URGENCY_HIGH,
    URGENCY_LOW,
    URGENCY_MEDIUM,
    BboSnapshot,
    HedgerRuntime,
    InstrumentMetadata,
    build_hedge_plan,
    classify_risk,
    load_config,
    parse_bbo,
    parse_metadata,
)


BASE_ENV = {
    "NATS_URL": "nats://localhost:4222",
    "TAKER_FEED": "AAH6",
    "TAKER_SENDER": "TAKER001",
    "SENDER": "QUOTER01",
    "HEDGER_SENDER": "HEDGER01",
}


def make_metadata(
    *,
    tick_size="1",
    ref_price="600",
    band="100",
    min_volume=1,
    max_volume=50,
    position_limit=100,
    max_tps=20,
):
    return InstrumentMetadata(
        feed="AAH6",
        tick_size=Decimal(tick_size),
        ref_price=Decimal(ref_price) if ref_price is not None else None,
        band=Decimal(band) if band is not None else None,
        min_volume=min_volume,
        max_volume=max_volume,
        position_limit=position_limit,
        max_tps=max_tps,
        raw_values={},
    )


def make_bbo(
    *,
    bid_price=590,
    bid_volume=10,
    ask_price=610,
    ask_volume=10,
    generation=1,
):
    return BboSnapshot(
        ts_ns=1700000000000000000,
        feed="AAH6",
        bid_price=bid_price,
        bid_volume=bid_volume,
        ask_price=ask_price,
        ask_volume=ask_volume,
        generation=generation,
    )


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
        self.assertEqual(metadata.min_volume, 1)
        self.assertEqual(metadata.max_volume, 50)
        self.assertEqual(metadata.position_limit, 100)
        self.assertEqual(metadata.max_tps, 20)

    def test_metadata_accepts_zero_max_tps_as_unlimited(self):
        metadata = parse_metadata(
            "AAH6",
            "ticksize=1 ref_price=600 band=100 "
            "min_volume=1 max_volume=50 "
            "position_limit=100 max_tps=0",
        )

        self.assertEqual(metadata.max_tps, 0)

    def test_metadata_requires_positive_ticksize(self):
        for payload in (
            "ref_price=600 band=100 "
            "min_volume=1 max_volume=50 "
            "position_limit=100 max_tps=20",
            "ticksize=0 ref_price=600 band=100 "
            "min_volume=1 max_volume=50 "
            "position_limit=100 max_tps=20",
            "ticksize=bad ref_price=600 band=100 "
            "min_volume=1 max_volume=50 "
            "position_limit=100 max_tps=20",
        ):
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    parse_metadata("AAH6", payload)

    def test_metadata_requires_execution_limits(self):
        base = {
            "ticksize": "1",
            "ref_price": "600",
            "band": "100",
            "min_volume": "1",
            "max_volume": "50",
            "position_limit": "100",
            "max_tps": "20",
        }

        for missing in (
            "min_volume",
            "max_volume",
            "position_limit",
            "max_tps",
        ):
            with self.subTest(missing=missing):
                values = dict(base)
                del values[missing]
                payload = " ".join(
                    f"{key}={value}"
                    for key, value in values.items()
                )

                with self.assertRaises(ValueError):
                    parse_metadata("AAH6", payload)

    def test_metadata_execution_limits_must_be_positive_integers(self):
        invalid = [
            ("min_volume", "0"),
            ("max_volume", "-1"),
            ("position_limit", "bad"),
            ("max_tps", "-1"),
        ]

        for key, value in invalid:
            with self.subTest(key=key, value=value):
                payload = (
                    "ticksize=1 ref_price=600 band=100 "
                    "min_volume=1 max_volume=50 "
                    "position_limit=100 max_tps=20"
                ).replace(f"{key}=1", f"{key}={value}") \
                 .replace(f"{key}=50", f"{key}={value}") \
                 .replace(f"{key}=100", f"{key}={value}") \
                 .replace(f"{key}=20", f"{key}={value}")

                with self.assertRaises(ValueError):
                    parse_metadata("AAH6", payload)

    def test_min_volume_must_not_exceed_max_volume(self):
        with self.assertRaises(ValueError):
            parse_metadata(
                "AAH6",
                "ticksize=1 ref_price=600 band=100 "
                "min_volume=10 max_volume=5 "
                "position_limit=100 max_tps=20",
            )

    def test_metadata_rejects_negative_band(self):
        with self.assertRaises(ValueError):
            parse_metadata(
                "AAH6",
                "ticksize=1 ref_price=600 band=-1 "
                "min_volume=1 max_volume=50 "
                "position_limit=100 max_tps=20",
            )

    def test_heartbeat_target_is_within_contract(self):
        self.assertGreater(HEARTBEAT_SECONDS, 0)
        self.assertLessEqual(HEARTBEAT_SECONDS, 0.2)


class HedgerBboTests(unittest.TestCase):
    def test_parse_valid_two_sided_bbo(self):
        bbo = parse_bbo(
            "1700000000000000000 AAH6 590 8 610 9",
            expected_feed="AAH6",
            generation=3,
        )

        self.assertEqual(bbo.bid_price, 590)
        self.assertEqual(bbo.bid_volume, 8)
        self.assertEqual(bbo.ask_price, 610)
        self.assertEqual(bbo.ask_volume, 9)
        self.assertEqual(bbo.generation, 3)

    def test_parse_allows_one_sided_bbo(self):
        bid_only = parse_bbo(
            "1700000000000000000 AAH6 590 8 - -",
            expected_feed="AAH6",
            generation=1,
        )
        ask_only = parse_bbo(
            "1700000000000000001 AAH6 - - 610 9",
            expected_feed="AAH6",
            generation=2,
        )

        self.assertEqual(bid_only.bid_price, 590)
        self.assertIsNone(bid_only.ask_price)
        self.assertIsNone(ask_only.bid_price)
        self.assertEqual(ask_only.ask_price, 610)

    def test_parse_accepts_exchange_runtime_empty_side_as_dash_zero(self):
        bid_only = parse_bbo(
            "1700000000000000000 AAH6 590 8 - 0",
            expected_feed="AAH6",
            generation=1,
        )
        ask_only = parse_bbo(
            "1700000000000000001 AAH6 - 0 610 9",
            expected_feed="AAH6",
            generation=2,
        )

        self.assertEqual(bid_only.bid_price, 590)
        self.assertEqual(bid_only.bid_volume, 8)
        self.assertIsNone(bid_only.ask_price)
        self.assertIsNone(bid_only.ask_volume)

        self.assertIsNone(ask_only.bid_price)
        self.assertIsNone(ask_only.bid_volume)
        self.assertEqual(ask_only.ask_price, 610)
        self.assertEqual(ask_only.ask_volume, 9)

    def test_empty_bbo_side_rejects_positive_volume_without_price(self):
        invalid_payloads = [
            "1700000000000000000 AAH6 590 8 - 1",
            "1700000000000000001 AAH6 - 1 610 9",
        ]

        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    parse_bbo(
                        payload,
                        expected_feed="AAH6",
                        generation=1,
                    )

    def test_invalid_bbo_is_rejected(self):
        invalid_payloads = [
            "1700000000000000000 AAH6 590 - 610 9",
            "1700000000000000000 AAH6 - 8 610 9",
            "1700000000000000000 AAH6 0 8 610 9",
            "1700000000000000000 AAH6 590 0 610 9",
            "1700000000000000000 AAH6 590 8 610",
            "not-a-time AAH6 590 8 610 9",
        ]

        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    parse_bbo(
                        payload,
                        expected_feed="AAH6",
                        generation=1,
                    )

    def test_wrong_feed_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_bbo(
                "1700000000000000000 BBH6 590 8 610 9",
                expected_feed="AAH6",
                generation=1,
            )


class HedgePlanningTests(unittest.TestCase):
    def test_safe_and_unknown_do_not_plan(self):
        metadata = make_metadata()
        bbo = make_bbo()

        self.assertIsNone(
            build_hedge_plan(
                net_position=5,
                hedger_position=0,
                soft_limit=6,
                hard_limit=15,
                metadata=metadata,
                bbo=bbo,
            )
        )

        self.assertIsNone(
            build_hedge_plan(
                net_position=15,
                hedger_position=0,
                soft_limit=6,
                hard_limit=15,
                metadata=metadata,
                bbo=bbo,
                accounting_trusted=False,
            )
        )

    def test_long_uses_bid_and_short_uses_ask(self):
        metadata = make_metadata()
        bbo = make_bbo(
            bid_price=590,
            bid_volume=20,
            ask_price=610,
            ask_volume=20,
        )

        long_plan = build_hedge_plan(
            net_position=15,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )
        short_plan = build_hedge_plan(
            net_position=-15,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(long_plan.side, DIRECTION_SELL)
        self.assertEqual(long_plan.price, 590)
        self.assertEqual(short_plan.side, DIRECTION_BUY)
        self.assertEqual(short_plan.price, 610)
        self.assertEqual(long_plan.order_type, "F")
        self.assertEqual(short_plan.order_type, "F")

    def test_low_urgency_uses_minimum_batch_when_fast_path_unavailable(self):
        metadata = make_metadata(min_volume=1)
        bbo = make_bbo(bid_volume=3)

        plan = build_hedge_plan(
            net_position=10,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.urgency, URGENCY_LOW)
        self.assertFalse(plan.fast_path)
        self.assertEqual(plan.remaining_reduction, 5)
        self.assertEqual(plan.quantity, 1)

    def test_medium_urgency_uses_sixty_percent_rounded_up(self):
        metadata = make_metadata()
        bbo = make_bbo(bid_volume=7)

        plan = build_hedge_plan(
            net_position=14,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.urgency, URGENCY_MEDIUM)
        self.assertFalse(plan.fast_path)
        self.assertEqual(plan.remaining_reduction, 9)
        self.assertEqual(plan.quantity, 6)

    def test_high_urgency_uses_maximum_safe_executable_reduction(self):
        metadata = make_metadata()
        bbo = make_bbo(bid_volume=8)

        plan = build_hedge_plan(
            net_position=20,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.urgency, URGENCY_HIGH)
        self.assertFalse(plan.fast_path)
        self.assertEqual(plan.remaining_reduction, 15)
        self.assertEqual(plan.quantity, 8)

    def test_fast_path_enters_safe_when_top_of_book_can_absorb_remaining(self):
        metadata = make_metadata()
        bbo = make_bbo(bid_volume=20)

        plan = build_hedge_plan(
            net_position=14,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.urgency, URGENCY_MEDIUM)
        self.assertTrue(plan.fast_path)
        self.assertEqual(plan.remaining_reduction, 9)
        self.assertEqual(plan.quantity, 9)
        self.assertEqual(14 - plan.quantity, 5)

    def test_min_volume_can_reduce_past_soft_boundary_without_crossing_zero(self):
        metadata = make_metadata(min_volume=3)
        bbo = make_bbo(bid_volume=10)

        plan = build_hedge_plan(
            net_position=6,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.urgency, URGENCY_LOW)
        self.assertFalse(plan.fast_path)
        self.assertEqual(plan.quantity, 3)
        self.assertGreaterEqual(6 - plan.quantity, 0)

    def test_plan_respects_bbo_volume(self):
        metadata = make_metadata()
        bbo = make_bbo(bid_volume=4)

        plan = build_hedge_plan(
            net_position=20,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.quantity, 4)

    def test_plan_respects_metadata_max_volume(self):
        metadata = make_metadata(max_volume=5)
        bbo = make_bbo(bid_volume=20)

        plan = build_hedge_plan(
            net_position=20,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.quantity, 5)

    def test_plan_respects_hedger_position_capacity(self):
        metadata = make_metadata(position_limit=100)
        bbo = make_bbo(bid_volume=20)

        plan = build_hedge_plan(
            net_position=20,
            hedger_position=-98,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertEqual(plan.quantity, 2)

    def test_plan_never_intentionally_crosses_zero(self):
        metadata = make_metadata(
            min_volume=10,
            max_volume=50,
        )
        bbo = make_bbo(bid_volume=50)

        plan = build_hedge_plan(
            net_position=6,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=bbo,
        )

        self.assertIsNone(plan)

    def test_missing_executable_side_returns_no_plan(self):
        metadata = make_metadata()

        long_plan = build_hedge_plan(
            net_position=15,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=make_bbo(
                bid_price=None,
                bid_volume=None,
            ),
        )
        short_plan = build_hedge_plan(
            net_position=-15,
            hedger_position=0,
            soft_limit=6,
            hard_limit=15,
            metadata=metadata,
            bbo=make_bbo(
                ask_price=None,
                ask_volume=None,
            ),
        )

        self.assertIsNone(long_plan)
        self.assertIsNone(short_plan)

    def test_invalid_tick_or_band_price_returns_no_plan(self):
        off_tick_metadata = make_metadata(
            tick_size="5",
            ref_price="600",
            band="100",
        )
        out_of_band_metadata = make_metadata(
            tick_size="1",
            ref_price="600",
            band="20",
        )

        self.assertIsNone(
            build_hedge_plan(
                net_position=15,
                hedger_position=0,
                soft_limit=6,
                hard_limit=15,
                metadata=off_tick_metadata,
                bbo=make_bbo(bid_price=592),
            )
        )

        self.assertIsNone(
            build_hedge_plan(
                net_position=15,
                hedger_position=0,
                soft_limit=6,
                hard_limit=15,
                metadata=out_of_band_metadata,
                bbo=make_bbo(bid_price=570),
            )
        )


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


class FakeReply:
    def __init__(self, data):
        self.data = data


class FakeConnection:
    def __init__(self):
        self.subscriptions = {}
        self.published = []
        self.flush_count = 0
        self.requests = []
        self.request_results = []
        self.events = []
        self.metadata_value = (
            b"ticksize=1 ref_price=600 band=100 "
            b"min_volume=1 max_volume=50 "
            b"position_limit=100 max_tps=20"
        )
        self.stream_messages = []

    def jetstream(self):
        return FakeJetStream(self.metadata_value)

    async def subscribe(self, subject, cb):
        self.subscriptions[subject] = cb

    async def publish(self, subject, data):
        self.published.append((subject, data))
        self.events.append(("publish", subject, data))

    async def flush(self):
        self.flush_count += 1

    async def request(self, subject, data, timeout):
        if subject == "$JS.API.STREAM.INFO.EX_MD":
            if self.stream_messages:
                sequences = [
                    message["seq"]
                    for message in self.stream_messages
                ]
                state = {
                    "first_seq": min(sequences),
                    "last_seq": max(sequences),
                }
            else:
                state = {
                    "first_seq": 0,
                    "last_seq": 0,
                }

            return FakeReply(
                json.dumps(
                    {
                        "type": "io.nats.jetstream.api.v1.stream_info_response",
                        "state": state,
                    }
                ).encode("utf-8")
            )

        if subject == "$JS.API.STREAM.MSG.GET.EX_MD":
            request_payload = json.loads(
                data.decode("ascii")
            )
            cursor = int(
                request_payload.get("seq", 1)
            )
            target_subject = request_payload.get(
                "next_by_subj"
            )

            matches = [
                message
                for message in self.stream_messages
                if (
                    message["seq"] >= cursor
                    and message["subject"] == target_subject
                )
            ]

            if not matches:
                return FakeReply(
                    json.dumps(
                        {
                            "type": "io.nats.jetstream.api.v1.stream_msg_get_response",
                            "error": {
                                "code": 404,
                                "description": "no message found",
                            },
                        }
                    ).encode("utf-8")
                )

            message = min(
                matches,
                key=lambda item: item["seq"],
            )

            return FakeReply(
                json.dumps(
                    {
                        "type": "io.nats.jetstream.api.v1.stream_msg_get_response",
                        "message": {
                            "subject": message["subject"],
                            "seq": message["seq"],
                            "data": base64.b64encode(
                                message["data"]
                            ).decode("ascii"),
                        },
                    }
                ).encode("utf-8")
            )

        self.requests.append((subject, data, timeout))
        self.events.append(("request", subject, data))

        if not self.request_results:
            raise AssertionError("No fake request result configured")

        result = self.request_results.pop(0)

        if isinstance(result, BaseException):
            raise result

        return FakeReply(result)


class HedgerRuntimeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.config = load_config(BASE_ENV)
        self.connection = FakeConnection()
        self.runtime = HedgerRuntime(
            self.config,
            connection=self.connection,
        )

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

    async def test_start_replays_retained_execution_before_first_risk(self):
        self.connection.stream_messages = [
            {
                "seq": 10,
                "subject": "ex.md.AAH6.TAKER001",
                "data": (
                    b"1700000000000000000 T "
                    b"TAKER001:RSTBUY01 EXT00001:RSTASK01 "
                    b"8 600 5001 B"
                ),
            },
        ]

        await self.runtime.start(start_heartbeat=False)

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            8,
        )
        self.assertTrue(self.runtime.startup_established)
        self.assertTrue(self.runtime.ready)

        risk_payloads = [
            payload
            for subject, payload in self.connection.published
            if subject == "desk.risk.AAH6"
        ]
        self.assertEqual(len(risk_payloads), 1)

        fields = risk_payloads[0].decode("ascii").split()
        self.assertEqual(fields[3], "8")
        self.assertEqual(fields[6], "CONTROLLED")
        self.assertEqual(fields[7], "S")

    async def test_replayed_execution_then_live_duplicate_counts_once(self):
        payload = (
            b"1700000000000000000 T "
            b"TAKER001:RSTBUY01 EXT00001:RSTASK01 "
            b"8 600 5001 B"
        )
        self.connection.stream_messages = [
            {
                "seq": 10,
                "subject": "ex.md.AAH6.TAKER001",
                "data": payload,
            },
        ]

        await self.runtime.start(start_heartbeat=False)

        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        await callback(FakeMessage(payload))

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            8,
        )

    async def test_malformed_retained_execution_fails_startup_closed(self):
        self.connection.stream_messages = [
            {
                "seq": 10,
                "subject": "ex.md.AAH6.TAKER001",
                "data": (
                    b"1700000000000000000 T "
                    b"TAKER001:BAD00001 EXT00001:BAD00002 "
                    b"BROKEN 600 5001 B"
                ),
            },
        ]

        with self.assertRaises(RuntimeError):
            await self.runtime.start(start_heartbeat=False)

        self.assertFalse(self.runtime.startup_established)
        self.assertFalse(
            self.runtime.accounting.accounting_trusted
        )

        risk_payloads = [
            payload
            for subject, payload in self.connection.published
            if subject == "desk.risk.AAH6"
        ]
        self.assertEqual(risk_payloads, [])

    async def test_valid_bbo_is_retained_and_generation_increments(self):
        await self.runtime.start(start_heartbeat=False)

        bbo_callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]

        await bbo_callback(
            FakeMessage(
                b"1700000000000000000 AAH6 590 8 610 9"
            )
        )

        self.assertEqual(self.runtime.bbo_generation, 1)
        self.assertEqual(self.runtime.latest_bbo.bid_price, 590)

        await bbo_callback(
            FakeMessage(
                b"1700000000000000001 AAH6 591 7 611 8"
            )
        )

        self.assertEqual(self.runtime.bbo_generation, 2)
        self.assertEqual(self.runtime.latest_bbo.bid_price, 591)

    async def test_invalid_bbo_clears_latest_without_advancing_generation(self):
        await self.runtime.start(start_heartbeat=False)

        bbo_callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]

        await bbo_callback(
            FakeMessage(
                b"1700000000000000000 AAH6 590 8 610 9"
            )
        )
        self.assertEqual(self.runtime.bbo_generation, 1)
        self.assertIsNotNone(self.runtime.latest_bbo)

        await bbo_callback(
            FakeMessage(
                b"1700000000000000001 AAH6 590 - 610 9"
            )
        )

        self.assertEqual(self.runtime.bbo_generation, 1)
        self.assertIsNone(self.runtime.latest_bbo)

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

        risk_payloads = [
            payload
            for subject, payload in self.connection.published
            if subject == "desk.risk.AAH6"
        ]
        fields = risk_payloads[-1].decode("ascii").split()

        self.assertEqual(fields[1], "2")
        self.assertEqual(fields[3], "6")
        self.assertEqual(fields[6], "CONTROLLED")
        self.assertEqual(fields[7], "S")

    async def test_taker_execution_ack_is_after_matching_risk_publish(self):
        await self.runtime.start(start_heartbeat=False)
        self.connection.events.clear()

        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        await callback(
            FakeMessage(
                b"1700000000000000000 T "
                b"TAKER001:BUY00001 EXT00001:SELL0001 "
                b"3 600 42 B"
            )
        )

        publishes = [
            event
            for event in self.connection.events
            if event[0] == "publish"
        ]

        self.assertEqual(len(publishes), 2)

        risk_event, ack_event = publishes

        self.assertEqual(
            risk_event[1],
            "desk.risk.AAH6",
        )
        risk_fields = risk_event[2].decode("ascii").split()
        self.assertEqual(risk_fields[1], "2")
        self.assertEqual(risk_fields[3], "3")
        self.assertEqual(risk_fields[6], RISK_SAFE)

        self.assertEqual(
            ack_event[1],
            "desk.accounted.AAH6.TAKER001",
        )
        self.assertEqual(
            ack_event[2].decode("ascii").split(),
            ["BUY00001", "42", "3", "2"],
        )

    async def test_partial_taker_executions_ack_each_accounted_match(self):
        await self.runtime.start(start_heartbeat=False)
        self.connection.published.clear()

        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]

        await callback(
            FakeMessage(
                b"1700000000000000000 T "
                b"TAKER001:BUY00001 EXT00001:SELL0001 "
                b"1 600 51 B"
            )
        )
        await callback(
            FakeMessage(
                b"1700000000000000001 T "
                b"TAKER001:BUY00001 EXT00002:SELL0002 "
                b"2 600 52 B"
            )
        )

        acknowledgements = [
            payload.decode("ascii").split()
            for subject, payload in self.connection.published
            if subject == "desk.accounted.AAH6.TAKER001"
        ]

        self.assertEqual(
            acknowledgements,
            [
                ["BUY00001", "51", "1", "2"],
                ["BUY00001", "52", "2", "3"],
            ],
        )
        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            3,
        )

    async def test_duplicate_taker_execution_does_not_publish_duplicate_ack(self):
        await self.runtime.start(start_heartbeat=False)
        self.connection.published.clear()

        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        event = FakeMessage(
            b"1700000000000000000 T "
            b"TAKER001:BUY00001 EXT00001:SELL0001 "
            b"3 600 61 B"
        )

        await callback(event)
        await callback(event)

        acknowledgements = [
            payload
            for subject, payload in self.connection.published
            if subject == "desk.accounted.AAH6.TAKER001"
        ]

        self.assertEqual(len(acknowledgements), 1)
        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            3,
        )

    async def test_current_hedge_plan_uses_authoritative_position_and_latest_bbo(self):
        await self.runtime.start(start_heartbeat=False)

        execution_callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        bbo_callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]

        await execution_callback(
            FakeMessage(
                b"1700000000000000000 T "
                b"TAKER001:BUY00001 EXT00001:SELL0001 "
                b"14 600 42 B"
            )
        )

        await bbo_callback(
            FakeMessage(
                b"1700000000000000001 AAH6 590 7 610 9"
            )
        )

        plan = await self.runtime.current_hedge_plan()

        self.assertIsNotNone(plan)
        self.assertEqual(plan.side, DIRECTION_SELL)
        self.assertEqual(plan.urgency, URGENCY_MEDIUM)
        self.assertEqual(plan.quantity, 6)
        self.assertEqual(plan.price, 590)
        self.assertEqual(plan.order_type, "F")

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

        plan = await self.runtime.current_hedge_plan()
        self.assertIsNone(plan)

    async def test_disconnect_after_ready_invalidates_bbo_and_never_recovers_safe(self):
        await self.runtime.start(start_heartbeat=False)

        bbo_callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]
        await bbo_callback(
            FakeMessage(
                b"1700000000000000000 AAH6 590 8 610 9"
            )
        )
        self.assertIsNotNone(self.runtime.latest_bbo)

        await self.runtime._on_disconnected()

        self.assertIsNone(self.runtime.latest_bbo)
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


class HedgerSingleFExecutionTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.config = load_config(BASE_ENV)
        self.connection = FakeConnection()
        self.runtime = HedgerRuntime(
            self.config,
            connection=self.connection,
        )

    async def asyncSetUp(self):
        await self.runtime.start(start_heartbeat=False)

    async def asyncTearDown(self):
        await self.runtime.close()

    async def _set_long_position(self, quantity=15):
        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        await callback(
            FakeMessage(
                (
                    "1700000000000000000 T "
                    "TAKER001:BUY00001 EXT00001:SELL0001 "
                    f"{quantity} 600 42 B"
                ).encode("ascii")
            )
        )

    async def _set_bbo(
        self,
        *,
        ts=1700000000000000100,
        bid=590,
        bid_volume=20,
        ask=610,
        ask_volume=20,
    ):
        callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]
        await callback(
            FakeMessage(
                (
                    f"{ts} AAH6 "
                    f"{bid} {bid_volume} "
                    f"{ask} {ask_volume}"
                ).encode("ascii")
            )
        )

    def _current_order_id(self):
        self.assertIsNotNone(self.runtime.hedge_in_flight)
        return self.runtime.hedge_in_flight.order_id

    async def _deliver_hedger_t(
        self,
        *,
        order_id,
        quantity,
        match_id=9001,
        ts=1700000000000000200,
        side="S",
    ):
        callback = self.connection.subscriptions[
            "ex.md.AAH6.HEDGER01"
        ]

        if side == "S":
            resting = "EXT00001:BUY00001"
        else:
            resting = "EXT00001:SELL0001"

        await callback(
            FakeMessage(
                (
                    f"{ts} T "
                    f"HEDGER01:{order_id} {resting} "
                    f"{quantity} 590 {match_id} {side}"
                ).encode("ascii")
            )
        )

    async def test_execute_sends_one_fill_and_kill_order(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 0")

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        self.assertEqual(len(self.connection.requests), 1)

        subject, raw_request, timeout = self.connection.requests[0]
        fields = raw_request.decode("ascii").split()

        self.assertEqual(subject, "ex.req.HEDGER01")
        self.assertEqual(fields[0], "HEDGER01")
        self.assertEqual(fields[1], "A")
        self.assertEqual(fields[2], "AAH6")
        self.assertEqual(len(fields[3]), 8)
        self.assertEqual(fields[4], "S")
        self.assertEqual(fields[5], "10")
        self.assertEqual(fields[6], "590")
        self.assertEqual(fields[7], "F")
        self.assertGreater(timeout, 0)

        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            0,
        )
        self.assertIsNone(self.runtime.hedge_in_flight)

    async def test_y_positive_reply_does_not_mutate_position_before_t(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 4")

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            0,
        )
        self.assertIsNotNone(self.runtime.hedge_in_flight)
        self.assertEqual(
            self.runtime.hedge_in_flight.expected_fill_quantity,
            4,
        )
        self.assertEqual(
            self.runtime.hedge_in_flight.confirmed_fill_quantity,
            0,
        )

    async def test_matching_authoritative_t_updates_position_and_completes(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 4")

        await self.runtime.execute_current_hedge_once()
        order_id = self._current_order_id()

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=4,
        )

        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            -4,
        )
        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            11,
        )
        self.assertIsNone(self.runtime.hedge_in_flight)
        self.assertTrue(self.runtime.accounting.accounting_trusted)

    async def test_partial_authoritative_fills_accumulate_until_reply_quantity(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 5")

        await self.runtime.execute_current_hedge_once()
        order_id = self._current_order_id()

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=2,
            match_id=9101,
            ts=1700000000000000201,
        )

        self.assertIsNotNone(self.runtime.hedge_in_flight)
        self.assertEqual(
            self.runtime.hedge_in_flight.confirmed_fill_quantity,
            2,
        )
        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            -2,
        )

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=3,
            match_id=9102,
            ts=1700000000000000202,
        )

        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            -5,
        )
        self.assertIsNone(self.runtime.hedge_in_flight)
        self.assertTrue(self.runtime.accounting.accounting_trusted)

    async def test_second_order_is_blocked_while_first_is_in_flight(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 4")

        first = await self.runtime.execute_current_hedge_once()
        second = await self.runtime.execute_current_hedge_once()

        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(len(self.connection.requests), 1)

    async def test_same_bbo_generation_is_not_reused_after_y_zero(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 0")

        first = await self.runtime.execute_current_hedge_once()
        second = await self.runtime.execute_current_hedge_once()

        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(len(self.connection.requests), 1)
        self.assertEqual(
            self.runtime.last_attempted_bbo_generation,
            1,
        )

        await self._set_bbo(
            ts=1700000000000000101,
            bid=591,
        )
        self.connection.request_results.append(b"EXCH0001 Y 0")

        third = await self.runtime.execute_current_hedge_once()

        self.assertTrue(third)
        self.assertEqual(len(self.connection.requests), 2)
        self.assertEqual(
            self.runtime.last_attempted_bbo_generation,
            2,
        )

    async def test_reject_releases_single_flight_without_position_change(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(
            b"EXCH0001 N 203 reused-order-id"
        )

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        self.assertIsNone(self.runtime.hedge_in_flight)
        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            0,
        )
        self.assertTrue(self.runtime.accounting.accounting_trusted)

    async def test_request_timeout_fails_closed_without_freezing_accounting(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(asyncio.TimeoutError())

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        self.assertTrue(self.runtime.accounting.accounting_trusted)
        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertFalse(self.runtime.ready)
        self.assertIsNone(self.runtime.latest_bbo)
        self.assertIsNotNone(self.runtime.hedge_in_flight)

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)
        self.assertEqual(risk_fields[7], DIRECTION_NONE)

    async def test_request_timeout_late_authoritative_t_updates_diagnostic_position(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(asyncio.TimeoutError())

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        order_id = self._current_order_id()
        requested_quantity = self.runtime.hedge_in_flight.requested_quantity

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=requested_quantity,
            match_id=99001,
            ts=1700000000000000999,
        )

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            15 - requested_quantity,
        )
        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            -requested_quantity,
        )
        self.assertTrue(self.runtime.accounting.accounting_trusted)
        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertFalse(self.runtime.ready)
        self.assertIsNone(
            self.runtime.hedge_in_flight,
            "A late full-requested T is terminal for the retained order identity",
        )

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(
            int(risk_fields[3]),
            15 - requested_quantity,
        )
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)

        await self._set_bbo(
            ts=1700000000000001000,
            bid=589,
        )
        self.connection.request_results.append(b"EXCH0001 Y 0")

        second = await self.runtime.execute_current_hedge_once()

        self.assertFalse(second)
        self.assertEqual(len(self.connection.requests), 1)

    async def test_confirmation_timeout_late_remaining_t_is_accounted_but_stays_unknown(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 4")

        with patch(
            "hedger.hedger.HEDGE_CONFIRM_TIMEOUT_SECONDS",
            0.001,
        ):
            attempted = await self.runtime.execute_current_hedge_once()
            self.assertTrue(attempted)
            order_id = self._current_order_id()

            await self._deliver_hedger_t(
                order_id=order_id,
                quantity=2,
                match_id=99011,
                ts=1700000000000001101,
            )

            await asyncio.sleep(0.02)

        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertEqual(self.runtime.accounting.desk_net_position, 13)
        self.assertIsNotNone(self.runtime.hedge_in_flight)

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=2,
            match_id=99012,
            ts=1700000000000001102,
        )

        self.assertEqual(self.runtime.accounting.desk_net_position, 11)
        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            -4,
        )
        self.assertTrue(self.runtime.accounting.accounting_trusted)
        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertFalse(self.runtime.ready)
        self.assertIsNone(self.runtime.hedge_in_flight)

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(int(risk_fields[3]), 11)
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)

    async def test_malformed_reply_fails_closed_without_freezing_accounting(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 MAYBE 4")

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        self.assertTrue(self.runtime.accounting.accounting_trusted)
        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertIsNone(self.runtime.latest_bbo)
        self.assertIsNotNone(self.runtime.hedge_in_flight)

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)

    async def test_malformed_reply_late_full_t_is_accounted_and_terminal_but_stays_unknown(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 MAYBE 4")

        attempted = await self.runtime.execute_current_hedge_once()

        self.assertTrue(attempted)
        self.assertIsNotNone(self.runtime.hedge_in_flight)
        order_id = self._current_order_id()
        requested_quantity = self.runtime.hedge_in_flight.requested_quantity

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=requested_quantity,
            match_id=99021,
            ts=1700000000000001201,
        )

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            15 - requested_quantity,
        )
        self.assertEqual(
            self.runtime.accounting.get_position("HEDGER01"),
            -requested_quantity,
        )
        self.assertTrue(self.runtime.accounting.accounting_trusted)
        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertFalse(self.runtime.ready)
        self.assertIsNone(self.runtime.hedge_in_flight)

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(
            int(risk_fields[3]),
            15 - requested_quantity,
        )
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)

    async def test_mismatched_authoritative_t_fails_closed(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 4")

        await self.runtime.execute_current_hedge_once()

        await self._deliver_hedger_t(
            order_id="BAD00001",
            quantity=4,
        )

        self.assertFalse(self.runtime.accounting.accounting_trusted)
        self.assertIsNotNone(
            self.runtime.accounting.get_position("HEDGER01")
        )

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)

    async def test_authoritative_fill_greater_than_reply_fails_closed(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 2")

        await self.runtime.execute_current_hedge_once()
        order_id = self._current_order_id()

        await self._deliver_hedger_t(
            order_id=order_id,
            quantity=3,
        )

        self.assertFalse(self.runtime.accounting.accounting_trusted)

    async def test_confirmation_timeout_fails_closed_without_freezing_accounting(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 4")

        with patch(
            "hedger.hedger.HEDGE_CONFIRM_TIMEOUT_SECONDS",
            0.001,
        ):
            attempted = await self.runtime.execute_current_hedge_once()
            self.assertTrue(attempted)
            self.assertIsNotNone(self.runtime.hedge_in_flight)

            await asyncio.sleep(0.02)

        self.assertTrue(self.runtime.accounting.accounting_trusted)
        self.assertTrue(self.runtime.hedge_outcome_uncertain)
        self.assertIsNone(self.runtime.latest_bbo)
        self.assertIsNotNone(self.runtime.hedge_in_flight)

        risk_fields = (
            self.connection.published[-1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(risk_fields[6], RISK_UNKNOWN)

    async def test_zero_max_tps_does_not_block_fresh_bbo_attempts(self):
        self.connection.metadata_value = (
            b"ticksize=1 ref_price=600 band=100 "
            b"min_volume=1 max_volume=50 "
            b"position_limit=100 max_tps=0"
        )

        await self.runtime.close()
        self.runtime = HedgerRuntime(
            self.config,
            connection=self.connection,
        )
        await self.runtime.start(start_heartbeat=False)

        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 0")

        with patch("hedger.hedger.time.monotonic", return_value=100.0):
            first = await self.runtime.execute_current_hedge_once()

        await self._set_bbo(
            ts=1700000000000000101,
            bid=591,
        )
        self.connection.request_results.append(b"EXCH0001 Y 0")

        with patch("hedger.hedger.time.monotonic", return_value=100.1):
            second = await self.runtime.execute_current_hedge_once()

        self.assertTrue(first)
        self.assertTrue(second)
        self.assertEqual(len(self.connection.requests), 2)

    async def test_max_tps_blocks_additional_attempt_in_same_window(self):
        self.connection.metadata_value = (
            b"ticksize=1 ref_price=600 band=100 "
            b"min_volume=1 max_volume=50 "
            b"position_limit=100 max_tps=1"
        )

        # Restart the fake runtime so the new metadata is loaded.
        await self.runtime.close()
        self.runtime = HedgerRuntime(
            self.config,
            connection=self.connection,
        )
        await self.runtime.start(start_heartbeat=False)

        await self._set_long_position(15)
        await self._set_bbo()

        self.connection.request_results.append(b"EXCH0001 Y 0")

        with patch("hedger.hedger.time.monotonic", return_value=100.0):
            first = await self.runtime.execute_current_hedge_once()

        await self._set_bbo(
            ts=1700000000000000101,
            bid=591,
        )
        self.connection.request_results.append(b"EXCH0001 Y 0")

        with patch("hedger.hedger.time.monotonic", return_value=100.5):
            second = await self.runtime.execute_current_hedge_once()

        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(len(self.connection.requests), 1)

    async def test_order_ids_are_unique_across_fresh_bbo_attempts(self):
        await self._set_long_position(15)
        await self._set_bbo()
        self.connection.request_results.append(b"EXCH0001 Y 0")

        await self.runtime.execute_current_hedge_once()
        first_fields = (
            self.connection.requests[-1][1]
            .decode("ascii")
            .split()
        )

        await self._set_bbo(
            ts=1700000000000000101,
            bid=591,
        )
        self.connection.request_results.append(b"EXCH0001 Y 0")

        await self.runtime.execute_current_hedge_once()
        second_fields = (
            self.connection.requests[-1][1]
            .decode("ascii")
            .split()
        )

        self.assertNotEqual(first_fields[3], second_fields[3])
        self.assertEqual(len(first_fields[3]), 8)
        self.assertEqual(len(second_fields[3]), 8)


class HedgerAutoReductionTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.config = load_config(BASE_ENV)
        self.connection = FakeConnection()
        self.runtime = HedgerRuntime(
            self.config,
            connection=self.connection,
        )

    async def asyncSetUp(self):
        await self.runtime.start(
            start_heartbeat=False,
            start_hedging=True,
        )

    async def asyncTearDown(self):
        await self.runtime.close()

    async def _wait_for_requests(self, count, timeout=0.2):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout

        while loop.time() < deadline:
            if len(self.connection.requests) >= count:
                # Let request/reply cleanup finish as well.
                await asyncio.sleep(0)
                await asyncio.sleep(0)
                return
            await asyncio.sleep(0)

        self.fail(
            f"Timed out waiting for {count} hedge request(s); "
            f"saw {len(self.connection.requests)}"
        )

    async def _set_taker_long(
        self,
        quantity,
        *,
        ts=1700000000000000000,
        order_id="BUY00001",
        match_id=42,
    ):
        callback = self.connection.subscriptions[
            "ex.md.AAH6.TAKER001"
        ]
        await callback(
            FakeMessage(
                (
                    f"{ts} T "
                    f"TAKER001:{order_id} EXT00001:SELL0001 "
                    f"{quantity} 600 {match_id} B"
                ).encode("ascii")
            )
        )

    async def _set_bbo(
        self,
        *,
        ts,
        bid=590,
        bid_volume=20,
        ask=610,
        ask_volume=20,
    ):
        callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]
        await callback(
            FakeMessage(
                (
                    f"{ts} AAH6 "
                    f"{bid} {bid_volume} "
                    f"{ask} {ask_volume}"
                ).encode("ascii")
            )
        )

    async def _deliver_hedger_sell_t(
        self,
        *,
        order_id,
        quantity,
        ts,
        match_id,
        price=590,
    ):
        callback = self.connection.subscriptions[
            "ex.md.AAH6.HEDGER01"
        ]
        await callback(
            FakeMessage(
                (
                    f"{ts} T "
                    f"HEDGER01:{order_id} EXT00001:BUY00001 "
                    f"{quantity} {price} {match_id} S"
                ).encode("ascii")
            )
        )

    def _request_order_id(self, index=-1):
        fields = (
            self.connection.requests[index][1]
            .decode("ascii")
            .split()
        )
        return fields[3]

    async def test_preexisting_bbo_is_not_used_after_risk_transition(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )

        await self._set_taker_long(
            15,
            ts=1700000000000000001,
        )

        # Risk is now CONTROLLED/EMERGENCY and has been published,
        # but the BBO existed before that publication.
        await asyncio.sleep(0)
        self.assertEqual(len(self.connection.requests), 0)

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
        )
        await self._wait_for_requests(1)

        self.assertEqual(len(self.connection.requests), 1)

    async def test_risk_publication_happens_before_automatic_f(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )
        self.connection.events.clear()

        await self._set_taker_long(
            15,
            ts=1700000000000000001,
        )

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
        )
        await self._wait_for_requests(1)

        event_types = [event[0] for event in self.connection.events]
        self.assertIn("publish", event_types)
        self.assertIn("request", event_types)
        self.assertLess(
            event_types.index("publish"),
            event_types.index("request"),
        )

        first_publish = next(
            event
            for event in self.connection.events
            if event[0] == "publish"
        )
        risk_fields = first_publish[2].decode("ascii").split()
        self.assertEqual(first_publish[1], "desk.risk.AAH6")
        self.assertEqual(risk_fields[3], "15")
        self.assertEqual(risk_fields[6], RISK_EMERGENCY)
        self.assertEqual(risk_fields[7], DIRECTION_SELL)

    async def test_exchange_runtime_bid_only_bbo_triggers_long_emergency_hedge(self):
        await self._set_taker_long(
            15,
            ts=1700000000000000001,
        )

        self.connection.request_results.append(b"EXCH0001 Y 0")
        callback = self.connection.subscriptions["ex.bbo.AAH6"]
        await callback(
            FakeMessage(
                b"1700000000000000002 AAH6 600 1 - 0"
            )
        )
        await self._wait_for_requests(1)

        fields = (
            self.connection.requests[0][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(fields[4], DIRECTION_SELL)
        self.assertEqual(fields[5], "1")
        self.assertEqual(fields[6], "600")
        self.assertEqual(fields[7], "F")

    async def test_safe_position_never_triggers_auto_hedge(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )
        await self._set_taker_long(
            5,
            ts=1700000000000000001,
        )
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
        )

        await asyncio.sleep(0)
        await asyncio.sleep(0)

        self.assertEqual(len(self.connection.requests), 0)
        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            5,
        )

    async def test_y_zero_needs_another_fresh_bbo_before_retry(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )
        await self._set_taker_long(
            15,
            ts=1700000000000000001,
        )

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
        )
        await self._wait_for_requests(1)

        await asyncio.sleep(0)
        await asyncio.sleep(0)
        self.assertEqual(len(self.connection.requests), 1)

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000003,
            bid=592,
        )
        await self._wait_for_requests(2)

        self.assertEqual(len(self.connection.requests), 2)

    async def test_authoritative_fill_rearms_only_for_post_fill_bbo(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )
        await self._set_taker_long(
            15,
            ts=1700000000000000001,
        )

        self.connection.request_results.append(b"EXCH0001 Y 4")
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
        )
        await self._wait_for_requests(1)

        first_order_id = self._request_order_id()

        await self._deliver_hedger_sell_t(
            order_id=first_order_id,
            quantity=4,
            ts=1700000000000000003,
            match_id=9001,
            price=591,
        )

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            11,
        )
        self.assertIsNone(self.runtime.hedge_in_flight)

        # The BBO used for the first F cannot immediately launch F #2.
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        self.assertEqual(len(self.connection.requests), 1)

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000004,
            bid=592,
        )
        await self._wait_for_requests(2)

        self.assertEqual(len(self.connection.requests), 2)

    async def test_emergency_repeats_f_only_on_fresh_bbo(self):
        await self._set_bbo(
            ts=1700000000000000000,
            bid_volume=5,
        )
        await self._set_taker_long(
            20,
            ts=1700000000000000001,
        )

        self.connection.request_results.append(b"EXCH0001 Y 5")
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
            bid_volume=5,
        )
        await self._wait_for_requests(1)

        first_fields = (
            self.connection.requests[0][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(first_fields[4], DIRECTION_SELL)
        self.assertEqual(first_fields[5], "5")
        self.assertEqual(first_fields[7], "F")

        await self._deliver_hedger_sell_t(
            order_id=first_fields[3],
            quantity=5,
            ts=1700000000000000003,
            match_id=9101,
            price=591,
        )

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            15,
        )

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000004,
            bid=592,
            bid_volume=4,
        )
        await self._wait_for_requests(2)

        second_fields = (
            self.connection.requests[1][1]
            .decode("ascii")
            .split()
        )
        self.assertEqual(second_fields[4], DIRECTION_SELL)
        self.assertEqual(second_fields[5], "4")
        self.assertEqual(second_fields[7], "F")

    async def test_entering_safe_stops_reduction_even_with_new_bbo(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )
        await self._set_taker_long(
            7,
            ts=1700000000000000001,
        )

        # remaining_reduction = 2, so the fast path can move +7 to +5.
        self.connection.request_results.append(b"EXCH0001 Y 2")
        await self._set_bbo(
            ts=1700000000000000002,
            bid=591,
        )
        await self._wait_for_requests(1)

        order_id = self._request_order_id()
        await self._deliver_hedger_sell_t(
            order_id=order_id,
            quantity=2,
            ts=1700000000000000003,
            match_id=9201,
            price=591,
        )

        self.assertEqual(
            self.runtime.accounting.desk_net_position,
            5,
        )

        await self._set_bbo(
            ts=1700000000000000004,
            bid=592,
        )
        await asyncio.sleep(0)
        await asyncio.sleep(0)

        self.assertEqual(len(self.connection.requests), 1)

    async def test_invalid_post_risk_bbo_does_not_trigger(self):
        await self._set_bbo(
            ts=1700000000000000000,
        )
        await self._set_taker_long(
            15,
            ts=1700000000000000001,
        )

        bbo_callback = self.connection.subscriptions[
            "ex.bbo.AAH6"
        ]
        await bbo_callback(
            FakeMessage(
                b"1700000000000000002 AAH6 591 - 611 10"
            )
        )

        await asyncio.sleep(0)
        self.assertEqual(len(self.connection.requests), 0)
        self.assertIsNone(self.runtime.latest_bbo)

        self.connection.request_results.append(b"EXCH0001 Y 0")
        await self._set_bbo(
            ts=1700000000000000003,
            bid=592,
        )
        await self._wait_for_requests(1)

        self.assertEqual(len(self.connection.requests), 1)



if __name__ == "__main__":
    unittest.main()
