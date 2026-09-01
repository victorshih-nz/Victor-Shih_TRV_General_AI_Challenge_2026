"""Minimal Hedger runtime foundation for Job 2.1B."""

from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Mapping, Optional


DEFAULT_DESK_SOFT_POS = 6
DEFAULT_DESK_HARD_POS = 15

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