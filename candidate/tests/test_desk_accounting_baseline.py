import unittest

from hedger.accounting import DeskPositionAccounting


class DeskAccountingBaselineTests(unittest.TestCase):
    def test_t_buy_incoming_increases_position(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 T INBUY001 RESTSELL001 5 600 42 B")

        self.assertEqual(delta, 5)
        self.assertEqual(desk.get_position("A"), 5)
        self.assertEqual(desk.desk_net_position, 5)

    def test_t_sell_incoming_decreases_position(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 T INSSELL001 RESTBUY001 5 600 43 S")

        self.assertEqual(delta, -5)
        self.assertEqual(desk.get_position("A"), -5)
        self.assertEqual(desk.desk_net_position, -5)

    def test_e_buy_aggressor_results_in_resting_sell_negative(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 E RESTBUY001 INCSELL001 5 600 44 B")

        self.assertEqual(delta, -5)
        self.assertEqual(desk.get_position("A"), -5)

    def test_e_sell_aggressor_results_in_resting_buy_positive(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 E RESTSELL001 INCBUY001 5 600 45 S")

        self.assertEqual(delta, 5)
        self.assertEqual(desk.get_position("A"), 5)

    def test_non_execution_event_does_not_change_position(self):
        desk = DeskPositionAccounting(["A"])
        delta = desk.apply_event("A", "1700000000000000000 A INBUY001 RESTSELL001 5 600 46 B")

        self.assertEqual(delta, 0)
        self.assertEqual(desk.get_position("A"), 0)
        self.assertEqual(desk.desk_net_position, 0)

    def test_exact_duplicate_execution_is_counted_once(self):
        desk = DeskPositionAccounting(["A"])
        event = "1700000000000000000 T DUPBUY001 DUPSELL001 5 600 47 B"

        first_delta = desk.apply_event("A", event)
        second_delta = desk.apply_event("A", event)

        self.assertEqual(first_delta, 5)
        self.assertEqual(second_delta, 0)
        self.assertEqual(desk.get_position("A"), 5)

    def test_same_match_id_for_different_sender_or_event_side_is_not_collapsed(self):
        desk = DeskPositionAccounting(["A", "B"])
        event_a = "1700000000000000000 T MATCHA001 MATCHB001 5 600 99 B"
        event_b = "1700000000000000000 E MATCHB001 MATCHA001 5 600 99 B"

        desk.apply_event("A", event_a)
        desk.apply_event("B", event_b)

        self.assertEqual(desk.get_position("A"), 5)
        self.assertEqual(desk.get_position("B"), -5)
        self.assertEqual(desk.desk_net_position, 0)

    def test_tracked_senders_self_trade_cancels_in_desk_net(self):
        desk = DeskPositionAccounting(["A", "B"])
        shared_ts = "1700000000000000000"
        shared_incoming = "ORDER_IN"
        shared_resting = "ORDER_OUT"
        shared_qty = 5
        shared_price = 600
        match_id = "100"
        aggressor_side = "B"

        event_a = f"{shared_ts} T {shared_incoming} {shared_resting} {shared_qty} {shared_price} {match_id} {aggressor_side}"
        event_b = f"{shared_ts} E {shared_incoming} {shared_resting} {shared_qty} {shared_price} {match_id} {aggressor_side}"

        desk.apply_event("A", event_a)
        desk.apply_event("B", event_b)

        self.assertEqual(desk.get_position("A"), 5)
        self.assertEqual(desk.get_position("B"), -5)
        self.assertEqual(desk.desk_net_position, 0)

    def test_untracked_sender_is_ignored(self):
        desk = DeskPositionAccounting(["A", "B"])
        delta = desk.apply_event("C", "1700000000000000000 T C_IN C_OUT 5 600 111 B")

        self.assertEqual(delta, 0)
        self.assertEqual(desk.get_position("C"), 0)
        self.assertEqual(desk.desk_net_position, 0)

    def test_invalid_execution_qty_is_ignored(self):
        desk = DeskPositionAccounting(["A"])
        invalid_events = [
            "1700000000000000000 T BADZERO BADTX 0 600 120 B",
            "1700000000000000000 T BADNEG BADTX -2 600 121 B",
            "1700000000000000000 T BADMAL BADTX abc 600 122 B",
        ]

        for event in invalid_events:
            self.assertEqual(desk.apply_event("A", event), 0)

        self.assertEqual(desk.get_position("A"), 0)
        self.assertEqual(desk.desk_net_position, 0)

    def test_dedup_capacity_is_bounded_and_eviction_preserves_accounting(self):
        desk = DeskPositionAccounting(["A"], dedup_capacity=2)
        event1 = "1700000000000000000 T E1 E1REST 5 600 200 B"
        event2 = "1700000000000000000 T E2 E2REST 3 600 201 B"
        event3 = "1700000000000000000 T E3 E3REST 2 600 202 B"

        self.assertEqual(desk.apply_event("A", event1), 5)
        self.assertEqual(desk.apply_event("A", event1), 0)
        self.assertEqual(desk.apply_event("A", event2), 3)
        self.assertEqual(desk.apply_event("A", event3), 2)
        self.assertEqual(desk.get_position("A"), 10)
        self.assertEqual(desk.apply_event("A", event1), 5)
        self.assertEqual(desk.get_position("A"), 15)

    def test_multiple_tracked_senders_aggregate_correcrly(self):
        desk = DeskPositionAccounting(["A", "B", "C"])
        desk.apply_event("A", "1700000000000000000 T ABUY001 ASSELL001 3 600 101 B")
        desk.apply_event("B", "1700000000000000000 E BREST001 BINC001 2 600 102 S")
        desk.apply_event("C", "1700000000000000000 T CBUY001 CSELL001 4 600 103 S")

        self.assertEqual(desk.get_position("A"), 3)
        self.assertEqual(desk.get_position("B"), 2)
        self.assertEqual(desk.get_position("C"), -4)
        self.assertEqual(desk.desk_net_position, 1)


if __name__ == "__main__":
    unittest.main()
