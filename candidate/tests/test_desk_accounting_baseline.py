import unittest

from hedger.accounting import AccountingUncertainty, DeskPositionAccounting


class DeskAccountingBaselineTests(unittest.TestCase):
    def test_t_buy_incoming_increases_position(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 T A:INBUY001 A:RESTSELL001 5 600 42 B")

        self.assertEqual(delta, 5)
        self.assertEqual(desk.get_position("A"), 5)
        self.assertEqual(desk.desk_net_position, 5)

    def test_t_sell_incoming_decreases_position(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 T A:INSSELL001 A:RESTBUY001 5 600 43 S")

        self.assertEqual(delta, -5)
        self.assertEqual(desk.get_position("A"), -5)
        self.assertEqual(desk.desk_net_position, -5)

    def test_e_buy_aggressor_results_in_resting_sell_negative(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 E A:RESTBUY001 A:INCSELL001 5 600 44 B")

        self.assertEqual(delta, -5)
        self.assertEqual(desk.get_position("A"), -5)

    def test_e_sell_aggressor_results_in_resting_buy_positive(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 E A:RESTSELL001 A:INCBUY001 5 600 45 S")

        self.assertEqual(delta, 5)
        self.assertEqual(desk.get_position("A"), 5)

    def test_t_sender_ownership_validation(self):
        desk = DeskPositionAccounting(["A"])
        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", "1700000000000000000 T B:INBUY001 A:RESTSELL001 5 600 46 B")
        self.assertFalse(desk.accounting_trusted)

    def test_e_sender_ownership_validation(self):
        desk = DeskPositionAccounting(["A"])
        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", "1700000000000000000 E A:INCSELL001 B:RESTBUY001 5 600 47 B")
        self.assertFalse(desk.accounting_trusted)

    def test_three_sender_aggregation(self):
        desk = DeskPositionAccounting(["A", "B", "C"])
        desk.apply_event("A", "1700000000000000000 T A:ABUY001 A:ASELL001 3 600 101 B")
        desk.apply_event("B", "1700000000000000000 E B:BREST001 B:BINC001 2 600 102 S")
        desk.apply_event("C", "1700000000000000000 T C:CBUY001 C:CSELL001 4 600 103 S")

        self.assertEqual(desk.get_position("A"), 3)
        self.assertEqual(desk.get_position("B"), 2)
        self.assertEqual(desk.get_position("C"), -4)
        self.assertEqual(desk.desk_net_position, 1)

    def test_tracked_senders_self_trade_cancels_in_desk_net(self):
        desk = DeskPositionAccounting(["A", "B"])
        shared_ts = "1700000000000000000"
        shared_qty = 5
        shared_price = 600
        match_id = "100"

        desk.apply_event("A", f"{shared_ts} T A:ORDER_IN B:ORDER_OUT {shared_qty} {shared_price} {match_id} B")
        desk.apply_event("B", f"{shared_ts} E A:ORDER_IN B:ORDER_OUT {shared_qty} {shared_price} {match_id} B")

        self.assertEqual(desk.get_position("A"), 5)
        self.assertEqual(desk.get_position("B"), -5)
        self.assertEqual(desk.desk_net_position, 0)

    def test_exact_duplicate_execution_is_counted_once(self):
        desk = DeskPositionAccounting(["A"])
        event = "1700000000000000000 T A:DUPBUY001 A:DUPSELL001 5 600 47 B"

        first_delta = desk.apply_event("A", event)
        second_delta = desk.apply_event("A", event)

        self.assertEqual(first_delta, 5)
        self.assertEqual(second_delta, 0)
        self.assertEqual(desk.get_position("A"), 5)

    def test_same_match_id_for_legitimately_different_sender_event_is_not_collapsed(self):
        desk = DeskPositionAccounting(["A", "B"])
        event_a = "1700000000000000000 T A:MATCHA001 B:MATCHB001 5 600 99 B"
        event_b = "1700000000000000000 E A:MATCHA001 B:MATCHB001 5 600 99 B"

        desk.apply_event("A", event_a)
        desk.apply_event("B", event_b)

        self.assertEqual(desk.get_position("A"), 5)
        self.assertEqual(desk.get_position("B"), -5)
        self.assertEqual(desk.desk_net_position, 0)

    def test_valid_a_ignored_safely(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 A A:BUY B 5 600")

        self.assertEqual(delta, 0)
        self.assertEqual(desk.get_position("A"), 0)
        self.assertTrue(desk.accounting_trusted)

    def test_valid_c_ignored_safely(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 C A:BUY001")

        self.assertEqual(delta, 0)
        self.assertEqual(desk.get_position("A"), 0)
        self.assertTrue(desk.accounting_trusted)

    def test_invalid_utf8_bytes_fail_closed(self):
        desk = DeskPositionAccounting(["A"])
        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", b"\xff\xfe")
        self.assertFalse(desk.accounting_trusted)
        self.assertEqual(desk.get_position("A"), 0)

    def test_missing_sender_identity_on_t_or_e_fails_closed(self):
        desk = DeskPositionAccounting(["A"])
        invalid_events = [
            "1700000000000000000 T BAD A:RESTSELL001 5 600 150 B",
            "1700000000000000000 E A:INCSELL001 BAD 5 600 151 B",
        ]
        for event in invalid_events:
            with self.assertRaises(AccountingUncertainty):
                desk.apply_event("A", event)
            self.assertFalse(desk.accounting_trusted)
            desk = DeskPositionAccounting(["A"])

    def test_malformed_a_or_c_fail_closed(self):
        desk = DeskPositionAccounting(["A"])
        for event in [
            "1700000000000000000 A A:BUY001 B 0 600",
            "1700000000000000000 C BAD",
        ]:
            with self.assertRaises(AccountingUncertainty):
                desk.apply_event("A", event)
            self.assertFalse(desk.accounting_trusted)
            desk = DeskPositionAccounting(["A"])

    def test_unknown_or_malformed_market_data_event_causes_trust_loss(self):
        desk = DeskPositionAccounting(["A"])
        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", "1700000000000000000 Q A:BOGUS")
        self.assertFalse(desk.accounting_trusted)

    def test_malformed_t_or_e_causes_trust_loss(self):
        desk = DeskPositionAccounting(["A"])
        malformed = [
            "1700000000000000000 T A:BAD A:REST abc 600 120 B",
            "1700000000000000000 E A:REST A:BAD 0 600 121 B",
        ]
        for event in malformed:
            with self.assertRaises(AccountingUncertainty):
                desk.apply_event("A", event)
            self.assertFalse(desk.accounting_trusted)
            desk = DeskPositionAccounting(["A"])

    def test_dedup_capacity_exhaustion_causes_trust_loss(self):
        desk = DeskPositionAccounting(["A"], dedup_capacity=2)
        desk.apply_event("A", "1700000000000000000 T A:E1 A:REST1 5 600 200 B")
        desk.apply_event("A", "1700000000000000000 T A:E2 A:REST2 3 600 201 B")

        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", "1700000000000000000 T A:E3 A:REST3 2 600 202 B")

        self.assertFalse(desk.accounting_trusted)
        self.assertEqual(desk.get_position("A"), 8)

    def test_after_trust_loss_no_later_execution_mutates_authoritative_position(self):
        desk = DeskPositionAccounting(["A"])
        desk.apply_event("A", "1700000000000000000 T A:GOOD1 A:GOODREST 5 600 300 B")
        self.assertEqual(desk.get_position("A"), 5)

        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", "1700000000000000000 T B:BAD A:GOODREST 5 600 301 B")

        with self.assertRaises(AccountingUncertainty):
            desk.apply_event("A", "1700000000000000000 T A:AFTER A:RESTAFTER 1 600 302 B")

        self.assertEqual(desk.get_position("A"), 5)

    def test_untracked_sender_is_ignored(self):
        desk = DeskPositionAccounting(["A", "B"])
        delta = desk.apply_event("C", "1700000000000000000 T C:IN C:OUT 5 600 111 B")

        self.assertEqual(delta, 0)
        self.assertEqual(desk.get_position("C"), 0)
        self.assertEqual(desk.desk_net_position, 0)


if __name__ == "__main__":
    unittest.main()
