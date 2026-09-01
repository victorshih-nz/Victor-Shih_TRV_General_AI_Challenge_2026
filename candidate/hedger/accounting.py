"""Minimal desk-position accounting for sender-specific execution feeds."""

from __future__ import annotations

from collections import OrderedDict
from typing import Dict, Iterable, Optional, Tuple, Union


class AccountingUncertainty(RuntimeError):
    """Raised when execution evidence is malformed or accounting trust is lost."""


class DeskPositionAccounting:
    """Track per-sender signed positions from authoritative execution events."""

    VALID_EVENT_TYPES = {"T", "E", "A", "C"}
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
        self._accounting_trusted = True
        for sender in tracked_senders or ():
            self.register_sender(sender)

    @property
    def accounting_trusted(self) -> bool:
        return self._accounting_trusted

    @property
    def trusted(self) -> bool:
        return self._accounting_trusted

    def mark_untrusted(self, reason: str) -> None:
        self._accounting_trusted = False
        raise AccountingUncertainty(reason)

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

    @staticmethod
    def _split_public_order_identity(value: Optional[str]) -> Tuple[Optional[str], str]:
        if value is None:
            return None, ""
        text = str(value).strip()
        if not text:
            return None, ""
        if ":" in text:
            sender, order_id = text.split(":", 1)
            return sender.strip() or None, order_id.strip()
        return None, text

    @staticmethod
    def _validate_public_order_identity(value: Optional[str], field_name: str) -> Tuple[str, str]:
        if value is None:
            raise AccountingUncertainty(f"{field_name} is missing")
        text = str(value).strip()
        if not text or ":" not in text:
            raise AccountingUncertainty(f"{field_name} is not a public order identity: {value!r}")
        sender, order_id = text.split(":", 1)
        if not sender.strip() or not order_id.strip():
            raise AccountingUncertainty(f"{field_name} is not a valid public order identity: {value!r}")
        return sender.strip(), order_id.strip()

    @classmethod
    def parse_event(cls, raw_event: Union[str, bytes, None]) -> Optional[dict]:
        if raw_event is None:
            raise AccountingUncertainty("Execution event is missing")
        if isinstance(raw_event, bytes):
            try:
                raw_event = raw_event.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise AccountingUncertainty("Execution event bytes are not valid UTF-8") from exc
        if not isinstance(raw_event, str):
            raise AccountingUncertainty("Execution event is not text")

        parts = raw_event.strip().split()
        if not parts:
            raise AccountingUncertainty("Execution event is blank")

        event_timestamp = parts[0]
        event_type = parts[1].upper() if len(parts) > 1 else ""
        if event_type in {"A", "C"}:
            if event_type == "A":
                if len(parts) != 6:
                    raise AccountingUncertainty("Malformed A event: expected 6 fields")
                order_id, side, qty_text, price_text = parts[2], parts[3].upper(), parts[4], parts[5]
                if not order_id or ":" not in order_id:
                    raise AccountingUncertainty(f"Malformed A event order id: {order_id!r}")
                if side not in cls.VALID_SIDES:
                    raise AccountingUncertainty(f"Invalid A side: {side!r}")
                qty_int = cls._as_int(qty_text)
                price_int = cls._as_int(price_text)
                if qty_int is None or price_int is None or qty_int <= 0 or price_int <= 0:
                    raise AccountingUncertainty(f"Malformed A event values: {raw_event}")
                return {
                    "eventTimestamp": event_timestamp,
                    "eventType": event_type,
                    "orderId": order_id,
                    "side": side,
                    "qty": qty_int,
                    "price": price_int,
                }
            if len(parts) != 3:
                raise AccountingUncertainty("Malformed C event: expected 3 fields")
            order_id = parts[2]
            if not order_id or ":" not in order_id:
                raise AccountingUncertainty(f"Malformed C event order id: {order_id!r}")
            return {
                "eventTimestamp": event_timestamp,
                "eventType": event_type,
                "orderId": order_id,
            }
        if event_type not in cls.VALID_EVENT_TYPES:
            raise AccountingUncertainty(f"Unknown market-data event type: {event_type}")
        if len(parts) != 8:
            raise AccountingUncertainty("Malformed execution event: expected exactly 8 fields")

        incoming_order_id, resting_order_id, qty_text, price_text, match_id, aggressor_side = parts[2], parts[3], parts[4], parts[5], parts[6], parts[7]
        try:
            qty_int = int(qty_text)
            price_int = int(price_text)
        except ValueError as exc:
            raise AccountingUncertainty(f"Malformed execution values: {raw_event}") from exc
        if qty_int <= 0 or price_int <= 0:
            raise AccountingUncertainty(f"Invalid execution qty or price: {raw_event}")
        aggressor_side = aggressor_side.upper()
        if aggressor_side not in cls.VALID_SIDES:
            raise AccountingUncertainty(f"Invalid aggressor side: {aggressor_side}")

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
        if event["eventType"] == "E":
            return DeskPositionAccounting.opposite(event["aggressorSide"])
        return None

    def _check_tracked_sender(self, tracked_sender: str) -> bool:
        if not self._tracked_senders:
            return True
        return str(tracked_sender) in self._tracked_senders

    def _mark_dedup(self, dedup_key: Tuple[str, str, str, str, str, str, int, int, str]) -> bool:
        if dedup_key in self._seen_dedup_keys:
            self._seen_dedup_keys.move_to_end(dedup_key)
            return True
        if len(self._seen_dedup_keys) >= self._dedup_capacity:
            self.mark_untrusted("Dedup capacity exhausted; accounting trust lost")
        self._seen_dedup_keys[dedup_key] = None
        return False

    def apply_event(self, tracked_sender: str, raw_event: Union[str, bytes, None]) -> int:
        tracked_sender = str(tracked_sender)
        if not self._check_tracked_sender(tracked_sender):
            return 0
        if not self._accounting_trusted:
            raise AccountingUncertainty("Accounting trust lost; no later execution can mutate position")

        try:
            event = self.parse_event(raw_event)
            if event is None:
                return 0

            if event["eventType"] in {"A", "C"}:
                return 0

            if event["eventType"] not in {"T", "E"}:
                self.mark_untrusted(f"Unknown execution event type: {event['eventType']}")

            incoming_sender, incoming_order = self._validate_public_order_identity(
                event["incomingOrderId"], "incomingOrderId"
            )
            resting_sender, resting_order = self._validate_public_order_identity(
                event["restingOrderId"], "restingOrderId"
            )

            if event["eventType"] == "T":
                if incoming_sender != tracked_sender:
                    self.mark_untrusted(
                        f"T event incoming sender mismatch: tracked={tracked_sender} incoming={incoming_sender}"
                    )
            elif event["eventType"] == "E":
                if resting_sender != tracked_sender:
                    self.mark_untrusted(
                        f"E event resting sender mismatch: tracked={tracked_sender} resting={resting_sender}"
                    )

            dedup_key = self.dedup_key(
                tracked_sender,
                event["eventType"],
                event["eventTimestamp"],
                event["matchId"],
                incoming_sender + ":" + incoming_order if incoming_sender else incoming_order,
                resting_sender + ":" + resting_order if resting_sender else resting_order,
                event["qty"],
                event["price"],
                event["aggressorSide"],
            )
            if self._mark_dedup(dedup_key):
                return 0

            side = self.effective_side(event)
            if side is None:
                self.mark_untrusted(f"Unsupported execution side for event: {event}")

            delta = event["qty"] if side == "B" else -event["qty"]
            self.positions.setdefault(tracked_sender, 0)
            self.positions[tracked_sender] += delta
            return delta
        except AccountingUncertainty:
            self._accounting_trusted = False
            raise

    def get_position(self, tracked_sender: str) -> int:
        return self.positions.get(str(tracked_sender), 0)

    @property
    def desk_net_position(self) -> int:
        return sum(self.positions.values())

    @property
    def per_sender_positions(self) -> Dict[str, int]:
        return dict(self.positions)
