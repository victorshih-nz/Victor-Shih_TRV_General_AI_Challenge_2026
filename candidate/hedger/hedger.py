"""Hedger runtime, desk-risk publisher, and staged single-F risk reduction."""

from __future__ import annotations

import asyncio
import base64
import json
from collections import deque
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import os
import re
import time
from typing import Deque, Mapping, Optional

import nats

from hedger.accounting import AccountingUncertainty, DeskPositionAccounting


DEFAULT_DESK_SOFT_POS = 6
DEFAULT_DESK_HARD_POS = 15
HEARTBEAT_SECONDS = 0.2
MAX_EXECUTION_DEDUP_ENTRIES = 4096
HEDGE_REQUEST_TIMEOUT_SECONDS = 0.5
HEDGE_CONFIRM_TIMEOUT_SECONDS = 1.0

RISK_UNKNOWN = "UNKNOWN"
RISK_SAFE = "SAFE"
RISK_CONTROLLED = "CONTROLLED"
RISK_EMERGENCY = "EMERGENCY"

DIRECTION_BUY = "B"
DIRECTION_SELL = "S"
DIRECTION_NONE = "X"

URGENCY_LOW = "LOW"
URGENCY_MEDIUM = "MEDIUM"
URGENCY_HIGH = "HIGH"


@dataclass(frozen=True)
class HedgerConfig:
    nats_url: str
    feed: str
    taker_sender: str
    quoter_sender: str
    hedger_sender: str
    soft_limit: int
    hard_limit: int


@dataclass(frozen=True)
class RiskAssessment:
    state: str
    direction: str


@dataclass(frozen=True)
class InstrumentMetadata:
    feed: str
    tick_size: Decimal
    ref_price: Optional[Decimal]
    band: Optional[Decimal]
    min_volume: int
    max_volume: int
    position_limit: int
    max_tps: int
    raw_values: Mapping[str, str]


@dataclass(frozen=True)
class BboSnapshot:
    ts_ns: int
    feed: str
    bid_price: Optional[int]
    bid_volume: Optional[int]
    ask_price: Optional[int]
    ask_volume: Optional[int]
    generation: int


@dataclass(frozen=True)
class HedgePlan:
    side: str
    quantity: int
    price: int
    urgency: str
    remaining_reduction: int
    bbo_generation: int
    fast_path: bool
    order_type: str = "F"


@dataclass
class HedgeInFlight:
    order_id: str
    side: str
    requested_quantity: int
    price: int
    bbo_generation: int
    sent_monotonic: float
    expected_fill_quantity: Optional[int] = None
    confirmed_fill_quantity: int = 0


def _require_text(env: Mapping[str, str], name: str) -> str:
    raw_value = env.get(name)
    if raw_value is None:
        raise ValueError(f"{name} is required")

    value = str(raw_value).strip()
    if not value:
        raise ValueError(f"{name} is required")

    return value


def _require_fixed_width(
    env: Mapping[str, str],
    name: str,
    width: int,
) -> str:
    value = _require_text(env, name)

    if len(value) != width:
        raise ValueError(f"{name} must be exactly {width} characters")

    if any(character.isspace() for character in value):
        raise ValueError(f"{name} must not contain whitespace")

    return value


def _read_positive_int(
    env: Mapping[str, str],
    name: str,
    default: int,
) -> int:
    raw_value = env.get(name)

    if raw_value is None or str(raw_value).strip() == "":
        return default

    try:
        value = int(str(raw_value).strip())
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc

    if value <= 0:
        raise ValueError(f"{name} must be positive")

    return value


def validate_limits(soft_limit: int, hard_limit: int) -> None:
    if soft_limit <= 0:
        raise ValueError("soft_limit must be positive")

    if hard_limit <= 0:
        raise ValueError("hard_limit must be positive")

    if soft_limit >= hard_limit:
        raise ValueError("soft_limit must be less than hard_limit")


def load_config(
    env: Optional[Mapping[str, str]] = None,
) -> HedgerConfig:
    source = os.environ if env is None else env

    nats_url = _require_text(source, "NATS_URL")
    feed = _require_fixed_width(source, "TAKER_FEED", 4)

    taker_sender = _require_fixed_width(
        source,
        "TAKER_SENDER",
        8,
    )
    quoter_sender = _require_fixed_width(
        source,
        "SENDER",
        8,
    )
    hedger_sender = _require_fixed_width(
        source,
        "HEDGER_SENDER",
        8,
    )

    senders = {
        taker_sender,
        quoter_sender,
        hedger_sender,
    }
    if len(senders) != 3:
        raise ValueError(
            "TAKER_SENDER, SENDER, and HEDGER_SENDER must be distinct"
        )

    soft_limit = _read_positive_int(
        source,
        "DESK_SOFT_POS",
        DEFAULT_DESK_SOFT_POS,
    )
    hard_limit = _read_positive_int(
        source,
        "DESK_HARD_POS",
        DEFAULT_DESK_HARD_POS,
    )

    validate_limits(soft_limit, hard_limit)

    return HedgerConfig(
        nats_url=nats_url,
        feed=feed,
        taker_sender=taker_sender,
        quoter_sender=quoter_sender,
        hedger_sender=hedger_sender,
        soft_limit=soft_limit,
        hard_limit=hard_limit,
    )


def classify_risk(
    net_position: int,
    soft_limit: int,
    hard_limit: int,
    accounting_trusted: bool = True,
) -> RiskAssessment:
    validate_limits(soft_limit, hard_limit)

    if not accounting_trusted:
        return RiskAssessment(
            state=RISK_UNKNOWN,
            direction=DIRECTION_NONE,
        )

    absolute_position = abs(net_position)

    if absolute_position < soft_limit:
        return RiskAssessment(
            state=RISK_SAFE,
            direction=DIRECTION_NONE,
        )

    if absolute_position < hard_limit:
        state = RISK_CONTROLLED
    else:
        state = RISK_EMERGENCY

    direction = (
        DIRECTION_SELL
        if net_position > 0
        else DIRECTION_BUY
    )

    return RiskAssessment(
        state=state,
        direction=direction,
    )


def _parse_decimal(
    values: Mapping[str, str],
    key: str,
    *,
    required: bool,
) -> Optional[Decimal]:
    raw_value = values.get(key)

    if raw_value is None:
        if required:
            raise ValueError(f"Metadata is missing required {key}")
        return None

    try:
        return Decimal(raw_value)
    except InvalidOperation as exc:
        raise ValueError(f"Metadata {key} must be numeric") from exc


def _parse_positive_metadata_int(
    values: Mapping[str, str],
    key: str,
) -> int:
    raw_value = values.get(key)

    if raw_value is None:
        raise ValueError(f"Metadata is missing required {key}")

    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"Metadata {key} must be an integer") from exc

    if value <= 0:
        raise ValueError(f"Metadata {key} must be > 0")

    return value


def _parse_nonnegative_metadata_int(
    values: Mapping[str, str],
    key: str,
) -> int:
    raw_value = values.get(key)

    if raw_value is None:
        raise ValueError(f"Metadata is missing required {key}")

    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"Metadata {key} must be an integer") from exc

    if value < 0:
        raise ValueError(f"Metadata {key} must be >= 0")

    return value


def parse_metadata(feed: str, payload: str) -> InstrumentMetadata:
    if not feed or not feed.strip():
        raise ValueError("feed must not be blank")

    if payload is None or not str(payload).strip():
        raise ValueError(f"Empty EX_META entry for feed {feed}")

    values = {}
    for token in re.split(r"[\s,;]+", str(payload).strip()):
        if "=" not in token:
            continue

        key, value = token.split("=", 1)
        key = key.strip()
        value = value.strip()

        if key and value:
            values[key] = value

    tick_size = _parse_decimal(
        values,
        "ticksize",
        required=True,
    )
    ref_price = _parse_decimal(
        values,
        "ref_price",
        required=False,
    )
    band = _parse_decimal(
        values,
        "band",
        required=False,
    )
    min_volume = _parse_positive_metadata_int(
        values,
        "min_volume",
    )
    max_volume = _parse_positive_metadata_int(
        values,
        "max_volume",
    )
    position_limit = _parse_positive_metadata_int(
        values,
        "position_limit",
    )
    # Exchange metadata uses max_tps=0 to mean that no explicit
    # per-feed TPS cap is configured. Preserve the raw zero and only
    # enforce the local rolling-window guard when the value is > 0.
    max_tps = _parse_nonnegative_metadata_int(
        values,
        "max_tps",
    )

    if tick_size is None or tick_size <= 0:
        raise ValueError("Metadata ticksize must be > 0")

    if ref_price is not None and ref_price <= 0:
        raise ValueError("Metadata ref_price must be > 0")

    if band is not None and band < 0:
        raise ValueError("Metadata band must be >= 0")

    if min_volume > max_volume:
        raise ValueError(
            "Metadata min_volume must be <= max_volume"
        )

    return InstrumentMetadata(
        feed=feed.strip(),
        tick_size=tick_size,
        ref_price=ref_price,
        band=band,
        min_volume=min_volume,
        max_volume=max_volume,
        position_limit=position_limit,
        max_tps=max_tps,
        raw_values=dict(values),
    )


def _parse_bbo_side(
    price_text: str,
    volume_text: str,
    side_name: str,
) -> tuple[Optional[int], Optional[int]]:
    # The exchange represents an empty BBO side as "- 0" at runtime.
    # Keep accepting "- -" as well because it is the documented empty form.
    # A numeric price with zero volume is not a usable quote and remains invalid.
    if price_text == "-":
        if volume_text in {"-", "0"}:
            return None, None
        raise ValueError(
            f"{side_name} empty BBO price requires volume '-' or 0"
        )

    if volume_text == "-":
        raise ValueError(
            f"{side_name} numeric BBO price requires numeric volume"
        )

    try:
        price = int(price_text)
        volume = int(volume_text)
    except ValueError as exc:
        raise ValueError(
            f"{side_name} BBO price/volume must be integers"
        ) from exc

    if price <= 0 or volume <= 0:
        raise ValueError(
            f"{side_name} BBO price/volume must be > 0"
        )

    return price, volume


def parse_bbo(
    payload,
    *,
    expected_feed: str,
    generation: int,
) -> BboSnapshot:
    if isinstance(payload, bytes):
        try:
            payload = payload.decode("ascii")
        except UnicodeDecodeError as exc:
            raise ValueError("BBO is not valid ASCII") from exc

    if not isinstance(payload, str):
        raise ValueError("BBO must be text")

    parts = payload.strip().split()
    if len(parts) != 6:
        raise ValueError("BBO must contain exactly 6 fields")

    ts_text, feed, bid_px, bid_vol, ask_px, ask_vol = parts

    try:
        ts_ns = int(ts_text)
    except ValueError as exc:
        raise ValueError("BBO timestamp must be an integer") from exc

    if ts_ns <= 0:
        raise ValueError("BBO timestamp must be > 0")

    if feed != expected_feed:
        raise ValueError(
            f"BBO feed mismatch: expected {expected_feed}, got {feed}"
        )

    if generation <= 0:
        raise ValueError("BBO generation must be positive")

    bid_price, bid_volume = _parse_bbo_side(
        bid_px,
        bid_vol,
        "bid",
    )
    ask_price, ask_volume = _parse_bbo_side(
        ask_px,
        ask_vol,
        "ask",
    )

    return BboSnapshot(
        ts_ns=ts_ns,
        feed=feed,
        bid_price=bid_price,
        bid_volume=bid_volume,
        ask_price=ask_price,
        ask_volume=ask_volume,
        generation=generation,
    )


def _price_is_valid_for_metadata(
    price: int,
    metadata: InstrumentMetadata,
) -> bool:
    price_decimal = Decimal(price)

    if price_decimal <= 0:
        return False

    if price_decimal % metadata.tick_size != 0:
        return False

    if metadata.ref_price is not None and metadata.band is not None:
        lower_bound = metadata.ref_price - metadata.band
        upper_bound = metadata.ref_price + metadata.band

        if price_decimal < lower_bound or price_decimal > upper_bound:
            return False

    return True


def _urgency_for_position(
    absolute_position: int,
    soft_limit: int,
    hard_limit: int,
) -> str:
    midpoint = (soft_limit + hard_limit + 1) // 2

    if absolute_position >= hard_limit:
        return URGENCY_HIGH

    if absolute_position >= midpoint:
        return URGENCY_MEDIUM

    return URGENCY_LOW


def build_hedge_plan(
    *,
    net_position: int,
    hedger_position: int,
    soft_limit: int,
    hard_limit: int,
    metadata: InstrumentMetadata,
    bbo: Optional[BboSnapshot],
    accounting_trusted: bool = True,
) -> Optional[HedgePlan]:
    """
    Build one staged risk-reducing F-order plan.

    Job 2.2A is planning only. This function never submits an order.
    """
    validate_limits(soft_limit, hard_limit)

    if not accounting_trusted or bbo is None:
        return None

    absolute_position = abs(net_position)
    if absolute_position < soft_limit:
        return None

    side = (
        DIRECTION_SELL
        if net_position > 0
        else DIRECTION_BUY
    )

    if side == DIRECTION_SELL:
        executable_price = bbo.bid_price
        executable_volume = bbo.bid_volume
        position_capacity = (
            metadata.position_limit + hedger_position
        )
    else:
        executable_price = bbo.ask_price
        executable_volume = bbo.ask_volume
        position_capacity = (
            metadata.position_limit - hedger_position
        )

    if executable_price is None or executable_volume is None:
        return None

    if not _price_is_valid_for_metadata(
        executable_price,
        metadata,
    ):
        return None

    position_capacity = max(0, position_capacity)

    hard_quantity_cap = min(
        executable_volume,
        metadata.max_volume,
        position_capacity,
        absolute_position,
    )

    if hard_quantity_cap < metadata.min_volume:
        return None

    remaining_reduction = (
        absolute_position - soft_limit + 1
    )
    urgency = _urgency_for_position(
        absolute_position,
        soft_limit,
        hard_limit,
    )

    fast_path = (
        remaining_reduction >= metadata.min_volume
        and remaining_reduction <= hard_quantity_cap
    )

    if fast_path:
        desired_quantity = remaining_reduction
    elif urgency == URGENCY_HIGH:
        desired_quantity = remaining_reduction
    elif urgency == URGENCY_MEDIUM:
        # Deterministic 60% of remaining reduction, rounded up.
        desired_quantity = (
            remaining_reduction * 3 + 4
        ) // 5
    else:
        desired_quantity = metadata.min_volume

    desired_quantity = max(
        desired_quantity,
        metadata.min_volume,
    )
    quantity = min(
        desired_quantity,
        hard_quantity_cap,
    )

    if quantity < metadata.min_volume:
        return None

    return HedgePlan(
        side=side,
        quantity=quantity,
        price=executable_price,
        urgency=urgency,
        remaining_reduction=remaining_reduction,
        bbo_generation=bbo.generation,
        fast_path=fast_path,
    )


def format_risk_payload(
    *,
    ts_ns: int,
    seq: int,
    feed: str,
    net_position: int,
    soft_limit: int,
    hard_limit: int,
    assessment: RiskAssessment,
) -> str:
    return (
        f"{ts_ns} {seq} {feed} {net_position} "
        f"{soft_limit} {hard_limit} "
        f"{assessment.state} {assessment.direction}"
    )


class HedgerRuntime:
    """Own authoritative desk accounting and publish advisory desk risk."""

    def __init__(
        self,
        config: HedgerConfig,
        connection=None,
        heartbeat_seconds: float = HEARTBEAT_SECONDS,
    ):
        self.config = config
        self.nc = connection
        self.metadata: Optional[InstrumentMetadata] = None
        self.latest_bbo: Optional[BboSnapshot] = None

        self.accounting = DeskPositionAccounting(
            [
                config.taker_sender,
                config.quoter_sender,
                config.hedger_sender,
            ],
            dedup_capacity=MAX_EXECUTION_DEDUP_ENTRIES,
        )

        self._heartbeat_seconds = heartbeat_seconds
        self._heartbeat_task: Optional[asyncio.Task] = None
        self._state_lock = asyncio.Lock()
        self._publish_lock = asyncio.Lock()

        self._startup_established = False
        self._startup_reconciling = False
        self._transport_connected = False
        self._owns_connection = connection is None
        self._closing = False
        self._seq = 0
        self._bbo_generation = 0

        # Hedge execution state. Only one F order may be unresolved at
        # a time. Exchange T/E remains authoritative for position;
        # request replies are never used to mutate it.
        self._hedge_in_flight: Optional[HedgeInFlight] = None
        self._last_attempted_bbo_generation = 0
        self._hedge_request_times: Deque[float] = deque()
        self._hedge_execution_lock = asyncio.Lock()
        self._hedge_confirmation_task: Optional[asyncio.Task] = None
        self._order_sequence = time.time_ns() & 0xFFFFFFFF

        # Request/reply or confirmation uncertainty is a trading-authority
        # failure, not permission to discard later authoritative T/E.
        # Once set, risk remains UNKNOWN for the rest of this runtime epoch,
        # while exact sender-specific execution evidence may still update the
        # diagnostic desk position.
        self._hedge_outcome_uncertain = False

        # Job 2.2C staged reduction coordination. A position-changing
        # execution must first be published as desk risk. Only a valid
        # BBO received after that publication may trigger the next F.
        self._auto_hedging_enabled = False
        self._auto_hedge_required_bbo_generation: Optional[int] = None
        self._auto_hedge_task: Optional[asyncio.Task] = None

    @property
    def risk_subject(self) -> str:
        return f"desk.risk.{self.config.feed}"

    @property
    def taker_accounted_subject(self) -> str:
        return (
            f"desk.accounted.{self.config.feed}."
            f"{self.config.taker_sender}"
        )

    @property
    def bbo_subject(self) -> str:
        return f"ex.bbo.{self.config.feed}"

    @property
    def execution_subjects(self) -> Mapping[str, str]:
        return {
            self.config.taker_sender: (
                f"ex.md.{self.config.feed}.{self.config.taker_sender}"
            ),
            self.config.quoter_sender: (
                f"ex.md.{self.config.feed}.{self.config.quoter_sender}"
            ),
            self.config.hedger_sender: (
                f"ex.md.{self.config.feed}.{self.config.hedger_sender}"
            ),
        }

    @property
    def startup_established(self) -> bool:
        return self._startup_established

    @property
    def ready(self) -> bool:
        return (
            self._startup_established
            and self._transport_connected
            and self.accounting.accounting_trusted
            and not self._hedge_outcome_uncertain
        )

    @property
    def hedge_outcome_uncertain(self) -> bool:
        return self._hedge_outcome_uncertain

    @property
    def sequence(self) -> int:
        return self._seq

    @property
    def bbo_generation(self) -> int:
        return self._bbo_generation

    @property
    def order_subject(self) -> str:
        return f"ex.req.{self.config.hedger_sender}"

    @property
    def hedge_in_flight(self) -> Optional[HedgeInFlight]:
        return self._hedge_in_flight

    @property
    def last_attempted_bbo_generation(self) -> int:
        return self._last_attempted_bbo_generation

    async def start(
        self,
        *,
        start_heartbeat: bool = True,
        start_hedging: bool = False,
    ) -> None:
        if self.nc is None:
            self.nc = await nats.connect(
                self.config.nats_url,
                connect_timeout=5,
                disconnected_cb=self._on_disconnected,
                reconnected_cb=self._on_reconnected,
                closed_cb=self._on_closed,
                error_cb=self._on_error,
            )

        self._transport_connected = True
        self.metadata = await self._load_metadata()

        async with self._state_lock:
            self._startup_reconciling = True

        try:
            # Install the exact live subscriptions first. Any execution that
            # arrives while retained EX_MD history is replayed goes through the
            # same accounting path. Existing execution dedup absorbs overlap.
            for sender, subject in self.execution_subjects.items():
                await self.nc.subscribe(
                    subject,
                    cb=self._execution_callback(sender),
                )

            await self.nc.subscribe(
                self.bbo_subject,
                cb=self._on_bbo,
            )

            await self.nc.flush()

            await self._replay_retained_execution_history()

            # Establish a final transport boundary before opening startup
            # authority. Live callbacks can overlap replay, but there is no
            # replay->subscribe gap because subscriptions were installed first.
            await self.nc.flush()

            async with self._state_lock:
                if not self.accounting.accounting_trusted:
                    raise RuntimeError(
                        "Hedger startup trust was lost during "
                        "authoritative position recovery"
                    )

                self._startup_reconciling = False
                self._startup_established = True

        except Exception:
            async with self._state_lock:
                self._startup_reconciling = False

                if self.accounting.accounting_trusted:
                    try:
                        self.accounting.mark_untrusted(
                            "Hedger startup authoritative position "
                            "recovery failed"
                        )
                    except AccountingUncertainty:
                        pass

            raise

        published = await self.publish_risk()
        if not published:
            raise RuntimeError(
                "Hedger could not publish first authoritative desk risk"
            )

        async with self._state_lock:
            self._auto_hedging_enabled = bool(start_hedging)
            self._auto_hedge_required_bbo_generation = None

        if start_heartbeat:
            self._heartbeat_task = asyncio.create_task(
                self._heartbeat_loop(),
                name="hedger-risk-heartbeat",
            )

    async def _jetstream_api_json(
        self,
        subject: str,
        payload: Optional[dict] = None,
    ) -> dict:
        raw_payload = (
            b""
            if payload is None
            else json.dumps(
                payload,
                separators=(",", ":"),
            ).encode("ascii")
        )

        reply = await self.nc.request(
            subject,
            raw_payload,
            timeout=2.0,
        )

        try:
            decoded = json.loads(
                reply.data.decode("utf-8")
            )
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise RuntimeError(
                f"Invalid JetStream API response from {subject}"
            ) from exc

        if not isinstance(decoded, dict):
            raise RuntimeError(
                f"Invalid JetStream API response from {subject}"
            )

        return decoded

    @staticmethod
    def _jetstream_error_code(response: dict) -> Optional[int]:
        error = response.get("error")

        if not isinstance(error, dict):
            return None

        code = error.get("code")
        try:
            return int(code)
        except (TypeError, ValueError):
            return None

    async def _snapshot_ex_md_window(self) -> tuple[int, int]:
        response = await self._jetstream_api_json(
            "$JS.API.STREAM.INFO.EX_MD"
        )

        if "error" in response:
            raise RuntimeError(
                "Unable to inspect retained EX_MD history"
            )

        state = response.get("state")
        if not isinstance(state, dict):
            raise RuntimeError(
                "EX_MD stream info is missing state"
            )

        try:
            first_seq = int(state.get("first_seq", 0))
            last_seq = int(state.get("last_seq", 0))
        except (TypeError, ValueError) as exc:
            raise RuntimeError(
                "EX_MD stream sequence window is invalid"
            ) from exc

        if last_seq <= 0:
            return (0, 0)

        if first_seq <= 0 or first_seq > last_seq:
            raise RuntimeError(
                "EX_MD stream sequence window is invalid"
            )

        return (first_seq, last_seq)

    async def _next_retained_execution(
        self,
        subject: str,
        cursor: int,
    ) -> Optional[tuple[int, bytes]]:
        response = await self._jetstream_api_json(
            "$JS.API.STREAM.MSG.GET.EX_MD",
            {
                "seq": cursor,
                "next_by_subj": subject,
            },
        )

        if "error" in response:
            if self._jetstream_error_code(response) == 404:
                return None

            raise RuntimeError(
                f"Unable to replay retained execution subject {subject}"
            )

        message = response.get("message")
        if not isinstance(message, dict):
            raise RuntimeError(
                "JetStream retained message response is malformed"
            )

        if message.get("subject") != subject:
            raise RuntimeError(
                "JetStream retained execution subject mismatch"
            )

        try:
            sequence = int(message["seq"])
        except (KeyError, TypeError, ValueError) as exc:
            raise RuntimeError(
                "JetStream retained execution sequence is invalid"
            ) from exc

        raw_data = message.get("data")
        if not isinstance(raw_data, str):
            raise RuntimeError(
                "JetStream retained execution data is missing"
            )

        try:
            data = base64.b64decode(
                raw_data,
                validate=True,
            )
        except Exception as exc:
            raise RuntimeError(
                "JetStream retained execution data is invalid base64"
            ) from exc

        return (sequence, data)

    async def _replay_retained_execution_history(self) -> None:
        first_seq, last_seq = await self._snapshot_ex_md_window()

        if last_seq == 0:
            return

        for sender, subject in self.execution_subjects.items():
            cursor = first_seq

            while cursor <= last_seq:
                retained = await self._next_retained_execution(
                    subject,
                    cursor,
                )

                if retained is None:
                    break

                sequence, payload = retained

                if sequence < cursor:
                    raise RuntimeError(
                        "JetStream retained execution sequence "
                        "moved backwards"
                    )

                if sequence > last_seq:
                    break

                await self._handle_execution(
                    sender,
                    payload,
                )

                if not self.accounting.accounting_trusted:
                    raise RuntimeError(
                        "Malformed or inconsistent authoritative "
                        "execution found during startup recovery"
                    )

                cursor = sequence + 1

    async def _load_metadata(self) -> InstrumentMetadata:
        try:
            js = self.nc.jetstream()
            kv = await js.key_value("EX_META")
            entry = await kv.get(self.config.feed)
        except Exception as exc:
            raise RuntimeError(
                f"Unable to read EX_META for feed {self.config.feed}"
            ) from exc

        if entry is None:
            raise RuntimeError(
                f"Missing EX_META entry for feed {self.config.feed}"
            )

        raw_value = entry.value
        if isinstance(raw_value, bytes):
            try:
                payload = raw_value.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise RuntimeError(
                    f"EX_META for feed {self.config.feed} is not valid UTF-8"
                ) from exc
        else:
            payload = str(raw_value)

        return parse_metadata(
            self.config.feed,
            payload,
        )

    def _execution_callback(self, tracked_sender: str):
        async def callback(msg) -> None:
            await self._handle_execution(
                tracked_sender,
                msg.data,
            )

        return callback

    async def _handle_execution(
        self,
        tracked_sender: str,
        payload,
    ) -> None:
        should_publish = False
        position_changed = False
        taker_ack = None

        async with self._state_lock:
            if (
                not self._startup_established
                and not self._startup_reconciling
            ):
                if self.accounting.accounting_trusted:
                    try:
                        self.accounting.mark_untrusted(
                            "Sender-specific market data arrived before "
                            "Hedger startup readiness"
                        )
                    except AccountingUncertainty:
                        pass
                return

            was_trusted = self.accounting.accounting_trusted

            try:
                delta = self.accounting.apply_event(
                    tracked_sender,
                    payload,
                )
            except AccountingUncertainty:
                should_publish = was_trusted
            else:
                position_changed = delta != 0

                if self._startup_reconciling:
                    # Startup recovery is accounting-only. Do not emit risk,
                    # accounting ACKs, or drive live Hedger order state from
                    # historical lifecycle evidence.
                    return

                should_publish = position_changed

                if (
                    position_changed
                    and tracked_sender == self.config.taker_sender
                ):
                    try:
                        event = DeskPositionAccounting.parse_event(payload)
                        if (
                            event is None
                            or event.get("eventType") != "T"
                        ):
                            self.accounting.mark_untrusted(
                                "Taker position changed without an incoming "
                                "T execution event"
                            )

                        incoming_public_id = event["incomingOrderId"]
                        incoming_sender, order_id = (
                            incoming_public_id.split(":", 1)
                        )
                        if (
                            incoming_sender != tracked_sender
                            or not order_id
                        ):
                            self.accounting.mark_untrusted(
                                "Taker execution identity is inconsistent "
                                "with the tracked sender"
                            )

                        taker_ack = (
                            order_id,
                            str(event["matchId"]),
                            int(event["qty"]),
                        )
                    except (
                        AccountingUncertainty,
                        KeyError,
                        TypeError,
                        ValueError,
                    ):
                        if self.accounting.accounting_trusted:
                            try:
                                self.accounting.mark_untrusted(
                                    "Unable to build Taker accounting "
                                    "acknowledgement from authoritative T"
                                )
                            except AccountingUncertainty:
                                pass
                        should_publish = True
                        taker_ack = None

                if (
                    position_changed
                    and tracked_sender == self.config.hedger_sender
                ):
                    try:
                        self._observe_hedger_execution_locked(
                            payload,
                            delta,
                        )
                    except AccountingUncertainty:
                        should_publish = True

        if should_publish:
            published_seq = await self._publish_risk_snapshot()
            published = published_seq is not None

            # A Taker accounting acknowledgement is emitted only after the
            # corresponding authoritative desk-risk snapshot has been
            # published and flushed. The acknowledgement carries the exact
            # order/match identity and that risk sequence; risk seq alone is
            # never treated as an execution acknowledgement.
            if taker_ack is not None and published_seq is not None:
                order_id, match_id, quantity = taker_ack
                await self._publish_taker_accounted_ack(
                    order_id=order_id,
                    match_id=match_id,
                    quantity=quantity,
                    risk_seq=published_seq,
                )

            # The next staged F may only be armed after the new
            # authoritative position/risk state has been published.
            if position_changed and published:
                await self._arm_auto_hedge_after_risk_publication()

    async def _publish_taker_accounted_ack(
        self,
        *,
        order_id: str,
        match_id: str,
        quantity: int,
        risk_seq: int,
    ) -> bool:
        if (
            self.nc is None
            or not self._startup_established
            or not self._transport_connected
        ):
            return False

        payload = (
            f"{order_id} {match_id} "
            f"{int(quantity)} {int(risk_seq)}"
        )

        try:
            await self.nc.publish(
                self.taker_accounted_subject,
                payload.encode("ascii"),
            )
            await self.nc.flush()
        except Exception:
            self._transport_connected = False
            await self._invalidate_bbo()
            await self._lose_accounting_trust(
                "Taker accounting acknowledgement publication failed"
            )
            return False

        return True

    async def _arm_auto_hedge_after_risk_publication(self) -> None:
        async with self._state_lock:
            if (
                not self._auto_hedging_enabled
                or not self._startup_established
                or not self._transport_connected
                or not self.accounting.accounting_trusted
                or self._hedge_outcome_uncertain
            ):
                self._auto_hedge_required_bbo_generation = None
                return

            assessment = classify_risk(
                net_position=self.accounting.desk_net_position,
                soft_limit=self.config.soft_limit,
                hard_limit=self.config.hard_limit,
                accounting_trusted=True,
            )

            if assessment.state not in {
                RISK_CONTROLLED,
                RISK_EMERGENCY,
            }:
                self._auto_hedge_required_bbo_generation = None
                return

            # Require one valid BBO generation strictly after this
            # risk publication. A BBO that arrived while publishing
            # risk is intentionally treated as pre-coordination state.
            self._auto_hedge_required_bbo_generation = (
                self._bbo_generation + 1
            )

    def _auto_hedge_base_ready_locked(self) -> bool:
        required_generation = (
            self._auto_hedge_required_bbo_generation
        )

        return (
            self._auto_hedging_enabled
            and not self._closing
            and self._startup_established
            and self._transport_connected
            and self.accounting.accounting_trusted
            and not self._hedge_outcome_uncertain
            and self.metadata is not None
            and self.latest_bbo is not None
            and required_generation is not None
            and self.latest_bbo.generation >= required_generation
            and self.latest_bbo.generation
            > self._last_attempted_bbo_generation
            and self._hedge_in_flight is None
        )

    def _schedule_auto_hedge_locked(self) -> None:
        if not self._auto_hedge_base_ready_locked():
            return

        plan = build_hedge_plan(
            net_position=self.accounting.desk_net_position,
            hedger_position=self.accounting.get_position(
                self.config.hedger_sender
            ),
            soft_limit=self.config.soft_limit,
            hard_limit=self.config.hard_limit,
            metadata=self.metadata,
            bbo=self.latest_bbo,
            accounting_trusted=True,
        )
        if plan is None:
            return

        task = self._auto_hedge_task
        if task is not None and not task.done():
            return

        self._auto_hedge_task = asyncio.create_task(
            self._auto_hedge_worker(),
            name="hedger-staged-risk-reduction",
        )

    def _auto_retry_delay_locked(self) -> Optional[float]:
        if not self._auto_hedge_base_ready_locked():
            return None

        plan = build_hedge_plan(
            net_position=self.accounting.desk_net_position,
            hedger_position=self.accounting.get_position(
                self.config.hedger_sender
            ),
            soft_limit=self.config.soft_limit,
            hard_limit=self.config.hard_limit,
            metadata=self.metadata,
            bbo=self.latest_bbo,
            accounting_trusted=True,
        )
        if plan is None:
            return None

        now = time.monotonic()
        self._prune_hedge_request_times_locked(now)

        if self.metadata.max_tps == 0:
            return 0.0

        if len(self._hedge_request_times) < self.metadata.max_tps:
            return 0.0

        return max(
            0.001,
            self._hedge_request_times[0] + 1.0 - now + 0.001,
        )

    async def _auto_hedge_worker(self) -> None:
        current_task = asyncio.current_task()

        try:
            while True:
                attempted = await self.execute_current_hedge_once()
                if attempted:
                    return

                async with self._state_lock:
                    retry_delay = self._auto_retry_delay_locked()

                if retry_delay is None:
                    return

                if retry_delay > 0:
                    await asyncio.sleep(retry_delay)
                else:
                    # Avoid a hot loop if state changed between the
                    # eligibility check and execute_current_hedge_once.
                    await asyncio.sleep(0)
        except asyncio.CancelledError:
            raise
        finally:
            async with self._state_lock:
                if self._auto_hedge_task is current_task:
                    self._auto_hedge_task = None

                    # A newer valid BBO may have arrived while the
                    # request/reply path was active. Do not lose that
                    # fresh trigger.
                    self._schedule_auto_hedge_locked()

    async def _on_bbo(self, msg) -> None:
        payload = getattr(msg, "data", msg)

        async with self._state_lock:
            next_generation = self._bbo_generation + 1

            try:
                snapshot = parse_bbo(
                    payload,
                    expected_feed=self.config.feed,
                    generation=next_generation,
                )
            except ValueError:
                self.latest_bbo = None
                return

            self._bbo_generation = next_generation
            self.latest_bbo = snapshot
            self._schedule_auto_hedge_locked()

    async def current_hedge_plan(self) -> Optional[HedgePlan]:
        async with self._state_lock:
            if (
                not self._startup_established
                or not self._transport_connected
                or self.metadata is None
            ):
                return None

            return build_hedge_plan(
                net_position=self.accounting.desk_net_position,
                hedger_position=self.accounting.get_position(
                    self.config.hedger_sender
                ),
                soft_limit=self.config.soft_limit,
                hard_limit=self.config.hard_limit,
                metadata=self.metadata,
                bbo=self.latest_bbo,
                accounting_trusted=(
                    self.accounting.accounting_trusted
                    and not self._hedge_outcome_uncertain
                ),
            )

    def _next_order_id_locked(self) -> str:
        self._order_sequence = (
            self._order_sequence + 1
        ) & 0xFFFFFFFF
        return f"{self._order_sequence:08X}"

    def _prune_hedge_request_times_locked(self, now: float) -> None:
        cutoff = now - 1.0
        while (
            self._hedge_request_times
            and self._hedge_request_times[0] <= cutoff
        ):
            self._hedge_request_times.popleft()

    def _cancel_confirmation_task_locked(self) -> None:
        task = self._hedge_confirmation_task
        self._hedge_confirmation_task = None

        if (
            task is not None
            and task is not asyncio.current_task()
            and not task.done()
        ):
            task.cancel()

    def _clear_hedge_in_flight_locked(self) -> None:
        self._hedge_in_flight = None
        self._cancel_confirmation_task_locked()

    def _mark_accounting_untrusted_locked(self, reason: str) -> bool:
        if not self.accounting.accounting_trusted:
            return False

        try:
            self.accounting.mark_untrusted(reason)
        except AccountingUncertainty:
            return True

        return False

    def _observe_hedger_execution_locked(
        self,
        payload,
        delta: int,
    ) -> None:
        event = DeskPositionAccounting.parse_event(payload)

        if event is None or event.get("eventType") != "T":
            self.accounting.mark_untrusted(
                "Hedger received an execution that was not an "
                "incoming T event for an F order"
            )

        in_flight = self._hedge_in_flight
        if in_flight is None:
            self.accounting.mark_untrusted(
                "Hedger execution arrived with no matching F order "
                "in flight"
            )

        expected_public_id = (
            f"{self.config.hedger_sender}:{in_flight.order_id}"
        )
        incoming_order_id = event.get("incomingOrderId")

        if incoming_order_id != expected_public_id:
            self.accounting.mark_untrusted(
                "Hedger execution does not match the current F order"
            )

        if event.get("aggressorSide") != in_flight.side:
            self.accounting.mark_untrusted(
                "Hedger execution side does not match the current F order"
            )

        expected_delta = (
            event["qty"]
            if in_flight.side == DIRECTION_BUY
            else -event["qty"]
        )
        if delta != expected_delta:
            self.accounting.mark_untrusted(
                "Hedger execution delta is inconsistent with the F order"
            )

        in_flight.confirmed_fill_quantity += event["qty"]

        if (
            in_flight.confirmed_fill_quantity
            > in_flight.requested_quantity
        ):
            self.accounting.mark_untrusted(
                "Hedger authoritative fills exceed requested F quantity"
            )

        # Even when the request/reply outcome is unknown, reaching the full
        # client-requested quantity is terminal: no further legitimate fill
        # can exist for this order. Clear only the retained order identity;
        # trading authority remains UNKNOWN when outcome uncertainty is set.
        if (
            in_flight.confirmed_fill_quantity
            == in_flight.requested_quantity
        ):
            self._clear_hedge_in_flight_locked()
            return

        expected_fill = in_flight.expected_fill_quantity
        if expected_fill is None:
            return

        if in_flight.confirmed_fill_quantity > expected_fill:
            self.accounting.mark_untrusted(
                "Hedger authoritative fills exceed exchange reply volume"
            )

        if in_flight.confirmed_fill_quantity == expected_fill:
            self._clear_hedge_in_flight_locked()

    async def _fail_closed_hedge_execution(self, reason: str) -> None:
        # A request/reply failure makes the hedge outcome unknown, but it does
        # not invalidate sender-specific execution evidence that can arrive
        # later. Keep the in-flight identity so a late exact T can still be
        # validated and applied to diagnostic position. Trading remains
        # fail-closed because risk authority becomes UNKNOWN for this epoch.
        should_publish = False

        async with self._state_lock:
            if not self._hedge_outcome_uncertain:
                self._hedge_outcome_uncertain = True
                should_publish = True

            self.latest_bbo = None
            self._auto_hedge_required_bbo_generation = None
            self._cancel_confirmation_task_locked()

        if should_publish and self._transport_connected:
            await self.publish_risk()

    async def _hedge_confirmation_timeout(self, order_id: str) -> None:
        try:
            await asyncio.sleep(HEDGE_CONFIRM_TIMEOUT_SECONDS)

            should_publish = False
            async with self._state_lock:
                in_flight = self._hedge_in_flight
                if (
                    in_flight is None
                    or in_flight.order_id != order_id
                ):
                    return

                expected_fill = in_flight.expected_fill_quantity
                if (
                    expected_fill is not None
                    and in_flight.confirmed_fill_quantity
                    >= expected_fill
                ):
                    self._clear_hedge_in_flight_locked()
                    return

                if not self._hedge_outcome_uncertain:
                    self._hedge_outcome_uncertain = True
                    should_publish = True

                self.latest_bbo = None
                self._auto_hedge_required_bbo_generation = None
                # Preserve the unresolved order identity. A late exact T may
                # still be authoritative and must update diagnostic position.
                self._hedge_confirmation_task = None

            if should_publish and self._transport_connected:
                await self.publish_risk()
        except asyncio.CancelledError:
            raise

    async def _reserve_hedge_attempt(
        self,
    ) -> tuple[Optional[HedgePlan], Optional[HedgeInFlight]]:
        now = time.monotonic()

        async with self._state_lock:
            if (
                not self._startup_established
                or not self._transport_connected
                or not self.accounting.accounting_trusted
                or self._hedge_outcome_uncertain
                or self.metadata is None
                or self.latest_bbo is None
                or self._hedge_in_flight is not None
            ):
                return None, None

            if (
                self.latest_bbo.generation
                <= self._last_attempted_bbo_generation
            ):
                return None, None

            self._prune_hedge_request_times_locked(now)
            if (
                self.metadata.max_tps > 0
                and len(self._hedge_request_times)
                >= self.metadata.max_tps
            ):
                return None, None

            plan = build_hedge_plan(
                net_position=self.accounting.desk_net_position,
                hedger_position=self.accounting.get_position(
                    self.config.hedger_sender
                ),
                soft_limit=self.config.soft_limit,
                hard_limit=self.config.hard_limit,
                metadata=self.metadata,
                bbo=self.latest_bbo,
                accounting_trusted=True,
            )
            if plan is None:
                return None, None

            order_id = self._next_order_id_locked()
            in_flight = HedgeInFlight(
                order_id=order_id,
                side=plan.side,
                requested_quantity=plan.quantity,
                price=plan.price,
                bbo_generation=plan.bbo_generation,
                sent_monotonic=now,
            )

            self._hedge_in_flight = in_flight
            self._last_attempted_bbo_generation = (
                plan.bbo_generation
            )
            self._hedge_request_times.append(now)

            return plan, in_flight

    @staticmethod
    def _parse_order_reply(payload) -> tuple[str, Optional[int]]:
        if isinstance(payload, bytes):
            try:
                payload = payload.decode("ascii")
            except UnicodeDecodeError as exc:
                raise ValueError("Order reply is not valid ASCII") from exc

        if not isinstance(payload, str):
            raise ValueError("Order reply must be text")

        # Protocol v2.5 replies are prefixed by the exchange tag:
        #   <EXCHANGE> Y <immediate_fill_qty>
        #   <EXCHANGE> N <code> <text...>
        parts = payload.strip().split()
        if len(parts) < 2:
            raise ValueError("Order reply is missing exchange tag/status")

        status = parts[1]

        if status == "Y":
            if len(parts) != 3:
                raise ValueError("Y reply must contain exactly 3 fields")
            try:
                fill_quantity = int(parts[2])
            except ValueError as exc:
                raise ValueError(
                    "Y reply fill quantity must be an integer"
                ) from exc
            if fill_quantity < 0:
                raise ValueError(
                    "Y reply fill quantity must be non-negative"
                )
            return "Y", fill_quantity

        if status == "N":
            if len(parts) < 3:
                raise ValueError("N reply must contain a reject code")
            return "N", None

        raise ValueError("Unknown order reply type")

    async def execute_current_hedge_once(self) -> bool:
        """
        Submit at most one planned F hedge.

        Job 2.2C may call this from the staged auto-reduction worker.
        Position mutation remains exclusively T/E-driven, and each real
        retry still requires a newer valid BBO generation.
        """
        async with self._hedge_execution_lock:
            plan, reserved = await self._reserve_hedge_attempt()
            if plan is None or reserved is None:
                return False

            request = (
                f"{self.config.hedger_sender} A "
                f"{self.config.feed} {reserved.order_id} "
                f"{plan.side} {plan.quantity} {plan.price} F"
            )

            try:
                reply = await self.nc.request(
                    self.order_subject,
                    request.encode("ascii"),
                    timeout=HEDGE_REQUEST_TIMEOUT_SECONDS,
                )
                status, immediate_fill = self._parse_order_reply(
                    getattr(reply, "data", reply)
                )
            except asyncio.TimeoutError:
                await self._fail_closed_hedge_execution(
                    "Hedger F request timed out; order outcome is unknown"
                )
                return True
            except Exception as exc:
                await self._fail_closed_hedge_execution(
                    f"Hedger F request/reply failed: {exc}"
                )
                return True

            fail_reason = None

            async with self._state_lock:
                in_flight = self._hedge_in_flight
                if (
                    in_flight is None
                    or in_flight.order_id != reserved.order_id
                ):
                    # Authoritative fills may have completed before the
                    # request reply arrived. That is safe only when the
                    # reply confirms exactly the already-accounted fill.
                    if status == "Y" and immediate_fill == (
                        reserved.confirmed_fill_quantity
                    ):
                        return True
                    fail_reason = (
                        "Hedger F reply arrived after inconsistent "
                        "in-flight state"
                    )
                elif status == "N":
                    if in_flight.confirmed_fill_quantity != 0:
                        fail_reason = (
                            "Exchange rejected Hedger F order after "
                            "authoritative fills were observed"
                        )
                    else:
                        self._clear_hedge_in_flight_locked()
                else:
                    if immediate_fill is None:
                        fail_reason = "Hedger Y reply is missing fill volume"
                    elif immediate_fill > in_flight.requested_quantity:
                        fail_reason = (
                            "Exchange reported more immediate Hedger fill "
                            "than requested"
                        )
                    elif (
                        in_flight.confirmed_fill_quantity
                        > immediate_fill
                    ):
                        fail_reason = (
                            "Authoritative Hedger fills exceed exchange "
                            "reply volume"
                        )
                    elif immediate_fill == 0:
                        if in_flight.confirmed_fill_quantity != 0:
                            fail_reason = (
                                "Exchange reported zero Hedger fill after "
                                "authoritative fills were observed"
                            )
                        else:
                            self._clear_hedge_in_flight_locked()
                    else:
                        in_flight.expected_fill_quantity = immediate_fill

                        if (
                            in_flight.confirmed_fill_quantity
                            == immediate_fill
                        ):
                            self._clear_hedge_in_flight_locked()
                        else:
                            self._cancel_confirmation_task_locked()
                            self._hedge_confirmation_task = (
                                asyncio.create_task(
                                    self._hedge_confirmation_timeout(
                                        in_flight.order_id
                                    ),
                                    name=(
                                        "hedger-fill-confirmation-"
                                        f"{in_flight.order_id}"
                                    ),
                                )
                            )

            if fail_reason is not None:
                await self._fail_closed_hedge_execution(fail_reason)

            return True

    async def _invalidate_bbo(self) -> None:
        async with self._state_lock:
            self.latest_bbo = None

    async def _lose_accounting_trust(self, reason: str) -> bool:
        async with self._state_lock:
            if not self.accounting.accounting_trusted:
                return False

            try:
                self.accounting.mark_untrusted(reason)
            except AccountingUncertainty:
                return True

        return False

    async def _on_disconnected(self) -> None:
        self._transport_connected = False
        await self._invalidate_bbo()

        async with self._state_lock:
            self._auto_hedge_required_bbo_generation = None
            self._clear_hedge_in_flight_locked()

        if self._closing:
            return

        if self._startup_established:
            await self._lose_accounting_trust(
                "NATS disconnected after Hedger readiness"
            )

    async def _on_reconnected(self) -> None:
        self._transport_connected = True

        if self._closing:
            return

        if self._startup_established:
            await self.publish_risk()

    async def _on_closed(self) -> None:
        self._transport_connected = False
        await self._invalidate_bbo()

        async with self._state_lock:
            self._auto_hedge_required_bbo_generation = None
            self._clear_hedge_in_flight_locked()

        if self._closing:
            return

        if self._startup_established:
            await self._lose_accounting_trust(
                "NATS connection closed after Hedger readiness"
            )

    async def _on_error(self, error) -> None:
        if self._closing or not self._startup_established:
            return

        newly_lost = await self._lose_accounting_trust(
            f"NATS runtime error after Hedger readiness: {error}"
        )

        if newly_lost and self._transport_connected:
            await self.publish_risk()

    async def _publish_risk_snapshot(self) -> Optional[int]:
        if (
            self.nc is None
            or not self._startup_established
            or not self._transport_connected
        ):
            return None

        async with self._publish_lock:
            if (
                self.nc is None
                or not self._startup_established
                or not self._transport_connected
            ):
                return None

            async with self._state_lock:
                net_position = self.accounting.desk_net_position
                accounting_trusted = (
                    self.accounting.accounting_trusted
                    and not self._hedge_outcome_uncertain
                )

            assessment = classify_risk(
                net_position=net_position,
                soft_limit=self.config.soft_limit,
                hard_limit=self.config.hard_limit,
                accounting_trusted=accounting_trusted,
            )

            self._seq += 1
            published_seq = self._seq
            payload = format_risk_payload(
                ts_ns=time.time_ns(),
                seq=published_seq,
                feed=self.config.feed,
                net_position=net_position,
                soft_limit=self.config.soft_limit,
                hard_limit=self.config.hard_limit,
                assessment=assessment,
            )

            try:
                await self.nc.publish(
                    self.risk_subject,
                    payload.encode("ascii"),
                )
                await self.nc.flush()
            except Exception:
                self._transport_connected = False
                await self._invalidate_bbo()
                await self._lose_accounting_trust(
                    "desk.risk publication failed"
                )
                return None

            return published_seq

    async def publish_risk(self) -> bool:
        return await self._publish_risk_snapshot() is not None

    async def _heartbeat_loop(self) -> None:
        loop = asyncio.get_running_loop()
        next_tick = loop.time() + self._heartbeat_seconds

        try:
            while True:
                delay = max(0.0, next_tick - loop.time())
                await asyncio.sleep(delay)

                if (
                    self._startup_established
                    and self._transport_connected
                ):
                    await self.publish_risk()

                next_tick += self._heartbeat_seconds

                if next_tick <= loop.time():
                    next_tick = (
                        loop.time() + self._heartbeat_seconds
                    )
        except asyncio.CancelledError:
            raise

    async def close(self) -> None:
        self._closing = True

        auto_task = None
        async with self._state_lock:
            self._auto_hedging_enabled = False
            self._auto_hedge_required_bbo_generation = None
            auto_task = self._auto_hedge_task
            self._auto_hedge_task = None
            self._clear_hedge_in_flight_locked()

        if auto_task is not None and not auto_task.done():
            auto_task.cancel()
            try:
                await auto_task
            except asyncio.CancelledError:
                pass

        if self._heartbeat_task is not None:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass
            self._heartbeat_task = None

        if self.nc is not None and self._owns_connection:
            await self.nc.drain()


async def run() -> None:
    config = load_config()
    runtime = HedgerRuntime(config)

    await runtime.start(start_hedging=True)

    print(
        f"[hedger] {config.hedger_sender} ready "
        f"feed={config.feed} "
        f"soft={config.soft_limit} "
        f"hard={config.hard_limit}",
        flush=True,
    )

    try:
        await asyncio.Event().wait()
    finally:
        await runtime.close()


def main() -> None:
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
