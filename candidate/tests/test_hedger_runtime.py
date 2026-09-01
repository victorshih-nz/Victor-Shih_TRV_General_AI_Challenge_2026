import unittest

from hedger.hedger import (
    DEFAULT_DESK_HARD_POS,
    DEFAULT_DESK_SOFT_POS,
    DIRECTION_BUY,
    DIRECTION_NONE,
    DIRECTION_SELL,
    RISK_CONTROLLED,
    RISK_EMERGENCY,
    RISK_SAFE,
    RISK_UNKNOWN,
    classify_risk,
    load_config,
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


if __name__ == "__main__":
    unittest.main()