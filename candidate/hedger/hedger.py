"""Hedger runtime and desk-risk publisher for Job 2.1B."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import os
import re
import time
from typing import Mapping, Optional

import nats

from hedger.accounting import AccountingUncertainty, DeskPositionAccounting


DEFAULT_DESK_SOFT_POS = 6
DEFAULT_DESK_HARD_POS = 15
HEARTBEAT_SECONDS = 0.2
MAX_EXECUTION_DEDUP_ENTRIES = 4096

RISK_UNKNOWN = "UNKNOWN"
RISK_SAFE = "SAFE"
RISK_CONTROLLED = "CONTROLLED"
RISK_EMERGENCY = "EMERGENCY"

DIRECTION_BUY = "B"
DIRECTION_SELL = "S"
DIRECTION_NONE = "X"


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
    raw_values: Mapping[str, str]


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

    if tick_size is None or tick_size <= 0:
        raise ValueError("Metadata ticksize must be > 0")

    if band is not None and band < 0:
        raise ValueError("Metadata band must be >= 0")

    return InstrumentMetadata(
        feed=feed.strip(),
        tick_size=tick_size,
        ref_price=ref_price,
        band=band,
        raw_values=dict(values),
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
        self._transport_connected = False
        self._owns_connection = connection is None
        self._closing = False
        self._seq = 0

    @property
    def risk_subject(self) -> str:
        return f"desk.risk.{self.config.feed}"

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
        )

    @property
    def sequence(self) -> int:
        return self._seq

    async def start(self, *, start_heartbeat: bool = True) -> None:
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

        async with self._state_lock:
            if not self.accounting.accounting_trusted:
                raise RuntimeError(
                    "Hedger startup trust was lost before readiness"
                )
            self._startup_established = True

        published = await self.publish_risk()
        if not published:
            raise RuntimeError(
                "Hedger could not publish first authoritative desk risk"
            )

        if start_heartbeat:
            self._heartbeat_task = asyncio.create_task(
                self._heartbeat_loop(),
                name="hedger-risk-heartbeat",
            )

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

        async with self._state_lock:
            if not self._startup_established:
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
                should_publish = delta != 0

        if should_publish:
            await self.publish_risk()

    async def _on_bbo(self, _msg) -> None:
        # Job 2.1B only requires that the configured BBO subscription is live.
        # BBO pricing and hedge sizing belong to Job 2.2.
        return

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

    async def publish_risk(self) -> bool:
        if (
            self.nc is None
            or not self._startup_established
            or not self._transport_connected
        ):
            return False

        async with self._publish_lock:
            if (
                self.nc is None
                or not self._startup_established
                or not self._transport_connected
            ):
                return False

            async with self._state_lock:
                net_position = self.accounting.desk_net_position
                accounting_trusted = (
                    self.accounting.accounting_trusted
                )

            assessment = classify_risk(
                net_position=net_position,
                soft_limit=self.config.soft_limit,
                hard_limit=self.config.hard_limit,
                accounting_trusted=accounting_trusted,
            )

            self._seq += 1
            payload = format_risk_payload(
                ts_ns=time.time_ns(),
                seq=self._seq,
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
                await self._lose_accounting_trust(
                    "desk.risk publication failed"
                )
                return False

            return True

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

    await runtime.start()

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
