"""Minimal desk-position accounting for sender-specific execution feeds."""

from __future__ import annotations

from collections import OrderedDict
from typing import Dict, Iterable, Optional, Tuple, Union


class DeskPositionAccounting:
    """Track per-sender signed positions from execution-bearing market events."""

    VALID_EVENT_TYPES = {"T", "E"}
    VALID_SIDES = {"B", "S"}

    def __init__(
        self,
        tracked_senders: Optional[Iterable[str]] = None,
        dedup_capacity: int = 256,
    ):
        self.positions: Dict[str, int] = {}
        self._tracked_senders = set()
        self._dedup_capacity = max(1, int(dedup_capacity))
        self._seen_dedup_keys: "OrderedDict[Tuple, None]" = OrderedDict()
        for sender in tracked_senders or ():
            self.register_sender(sender)

    def register_sender(self, sender: str) -> None:
        if sender is None:
            return
        sender_key = str(sender)
        self._tracked_senders.add(sender_key)
        self.positions.setdefault(sender_key, 0)

    @staticmethod
    def opposite(side: str) -> str:
        if side == "B":
            return "S"
        if side == "S":
            return "B"
        return side

    @staticmethod
    def _as_int(value: str) -> Optional[int]:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    @classmethod
    def parse_event(cls, raw_event: Union[str, bytes, None]) -> Optional[dict]:
        if raw_event is None:
            return None
        if isinstance(raw_event, bytes):
            raw_event = raw_event.decode("utf-8", errors="ignore")
        if not isinstance(raw_event, str):
            return None

        parts = raw_event.strip().split()
        if len(parts) < 8:
            return None

        event_timestamp, event_type, incoming_order_id, resting_order_id, qty, price, match_id, aggressor_side = parts[:8]
        if event_type not in cls.VALID_EVENT_TYPES:
            return None
        if aggressor_side not in cls.VALID_SIDES:
            return None
        qty_int = cls._as_int(qty)
        price_int = cls._as_int(price)
        if qty_int is None or price_int is None or qty_int <= 0:
            return None

        return {
            "eventTimestamp": event_timestamp,
            "eventType": event_type,
            "incomingOrderId": incoming_order_id,
            "restingOrderId": resting_order_id,
            "qty": qty_int,
            "price": price_int,
            "matchId": match_id,
            "aggressorSide": aggressor_side,
        }

    @classmethod
    def dedup_key(
        cls,
        tracked_sender: str,
        event_type: str,
        event_timestamp: str,
        match_id: str,
        incoming_order_id: str,
        resting_order_id: str,
        qty: int,
        price: int,
        aggressor_side: str,
    ) -> Tuple[str, str, str, str, str, str, int, int, str]:
        return (
            str(tracked_sender),
            str(event_type),
            str(event_timestamp),
            str(match_id),
            str(incoming_order_id),
            str(resting_order_id),
            int(qty),
            int(price),
            str(aggressor_side),
        )

    @staticmethod
    def effective_side(event: dict) -> Optional[str]:
        if event is None:
            return None
        if event["eventType"] == "T":
            return event["aggressorSide"]
        return DeskPositionAccounting.opposite(event["aggressorSide"])

    def _check_tracked_sender(self, tracked_sender: str) -> bool:
        if not self._tracked_senders:
            return True
        return str(tracked_sender) in self._tracked_senders

    def _mark_dedup(self, dedup_key: Tuple[str, str, str, str, str, str, int, int, str]) -> bool:
        if dedup_key in self._seen_dedup_keys:
            self._seen_dedup_keys.move_to_end(dedup_key)
            return True
        self._seen_dedup_keys[dedup_key] = None
        if len(self._seen_dedup_keys) > self._dedup_capacity:
            self._seen_dedup_keys.popitem(last=False)
        return False

    def apply_event(self, tracked_sender: str, raw_event: Union[str, bytes, None]) -> int:
        tracked_sender = str(tracked_sender)
        if not self._check_tracked_sender(tracked_sender):
            return 0

        event = self.parse_event(raw_event)
        if event is None:
            return 0

        side = self.effective_side(event)
        if side is None:
            return 0

        dedup_key = self.dedup_key(
            tracked_sender,
            event["eventType"],
            event["eventTimestamp"],
            event["matchId"],
            event["incomingOrderId"],
            event["restingOrderId"],
            event["qty"],
            event["price"],
            event["aggressorSide"],
        )
        if self._mark_dedup(dedup_key):
            return 0

        delta = event["qty"] if side == "B" else -event["qty"]
        self.positions.setdefault(tracked_sender, 0)
        self.positions[tracked_sender] = self.positions[tracked_sender] + delta
        return delta

    def get_position(self, tracked_sender: str) -> int:
        return self.positions.get(str(tracked_sender), 0)

    @property
    def desk_net_position(self) -> int:
        return sum(self.positions.values())

    @property
    def per_sender_positions(self) -> Dict[str, int]:
        return dict(self.positions)
