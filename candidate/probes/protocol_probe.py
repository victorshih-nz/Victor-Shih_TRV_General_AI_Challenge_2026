#!/usr/bin/env python3
"""Controlled exchange protocol probe for Job 0.1.

This helper is intentionally narrow: it logs exact request payloads with repr(),
exercises valid add/cancel flow against the live exchange, and records the
observed execution semantics used to verify the protocol and legacy Taker defects.
"""

import asyncio
import os

import nats

NATS_URL = os.environ.get("NATS_URL", "nats://127.0.0.1:4222")
FEED = os.environ.get("FEED", "AAH6")
PROBE_SENDER = os.environ.get("PROBE_SENDER", "PROBE001")
MAKER_SENDER = os.environ.get("MAKER_SENDER", "MAKER001")


def print_section(title: str) -> None:
    print(f"\n=== {title} ===")


async def connect() -> nats.NATS:
    nc = await nats.connect(NATS_URL, connect_timeout=5)
    await nc.flush()
    return nc


async def print_meta(nc: nats.NATS) -> None:
    js = nc.jetstream()
    kv = await js.key_value("EX_META")
    print_section("EX_META")
    keys = await kv.keys()
    print("keys:", keys)
    for key in keys:
        value = await kv.get(key)
        print(f"{key}={value.value.decode()}")


async def subscribe_events(nc: nats.NATS) -> None:
    async def handler(msg):
        print("EVENT", msg.subject, repr(msg.data.decode()))

    async def bbo_handler(msg):
        print("BBO", repr(msg.data.decode()))

    for sender in (PROBE_SENDER, MAKER_SENDER):
        await nc.subscribe(f"ex.md.{FEED}.{sender}", cb=handler)
    await nc.subscribe(f"ex.bbo.{FEED}", cb=bbo_handler)
    await nc.flush()


async def request_reply(nc: nats.NATS, subject: str, payload: str) -> str:
    print("REQUEST", repr(subject), repr(payload))
    try:
        reply = await nc.request(subject, payload.encode(), timeout=2.0)
        text = reply.data.decode()
    except Exception as exc:  # include timeout / no responder
        text = f"EXCEPTION {type(exc).__name__}: {exc}"
    print("REPLY", repr(text))
    return text


async def add_order(nc: nats.NATS, sender: str, oid: str, side: str, qty: int, price: int, typ: str) -> str:
    payload = f"{sender} A {FEED} {oid} {side} {qty} {price} {typ}"
    return await request_reply(nc, f"ex.req.{sender}", payload)


async def cancel_order(nc: nats.NATS, sender: str, oid: str) -> str:
    payload = f"{sender} C {FEED} {oid}"
    return await request_reply(nc, f"ex.req.{sender}", payload)


async def main() -> None:
    nc = await connect()
    await print_meta(nc)
    await subscribe_events(nc)

    print_section("ORDER SUBJECT CHECK")
    bare_payload = f"{PROBE_SENDER} A {FEED} BUY0001 B 5 600 F"
    await request_reply(nc, "ex.req", bare_payload)
    malformed_payload = f"{PROBE_SENDER} A {FEED} BUY0001 B 5 600 F"
    await request_reply(nc, f"ex.req.{PROBE_SENDER}", malformed_payload)

    print_section("CONTROLLED BUY FILL")
    maker_sell = f"{MAKER_SENDER} A {FEED} SELL0001 S 5 600 L"
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", maker_sell)
    await asyncio.sleep(0.2)
    probe_buy = f"{PROBE_SENDER} A {FEED} BUY00001 B 5 600 F"
    await request_reply(nc, f"ex.req.{PROBE_SENDER}", probe_buy)
    await asyncio.sleep(0.4)

    print_section("CONTROLLED SELL FILL")
    maker_buy = f"{MAKER_SENDER} A {FEED} BUY00002 B 5 610 L"
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", maker_buy)
    await asyncio.sleep(0.2)
    probe_sell = f"{PROBE_SENDER} A {FEED} SELL0002 S 5 610 F"
    await request_reply(nc, f"ex.req.{PROBE_SENDER}", probe_sell)
    await asyncio.sleep(0.4)

    print_section("MULTIPLE EXECUTIONS")
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", f"{MAKER_SENDER} A {FEED} SELL0003 S 2 600 L")
    await asyncio.sleep(0.2)
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", f"{MAKER_SENDER} A {FEED} SELL0004 S 3 600 L")
    await asyncio.sleep(0.2)
    await request_reply(nc, f"ex.req.{PROBE_SENDER}", f"{PROBE_SENDER} A {FEED} BUY00003 B 5 600 F")
    await asyncio.sleep(0.6)

    print_section("F ATOMIC FULL-OR-REJECT")
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", f"{MAKER_SENDER} A {FEED} SELL0005 S 3 600 L")
    await asyncio.sleep(0.2)
    await request_reply(nc, f"ex.req.{PROBE_SENDER}", f"{PROBE_SENDER} A {FEED} BUY00004 B 5 600 F")
    await asyncio.sleep(0.5)

    print_section("CANCEL BEHAVIOUR")
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", f"{MAKER_SENDER} A {FEED} CXL00001 S 10 620 L")
    await asyncio.sleep(0.2)
    await request_reply(nc, f"ex.req.{MAKER_SENDER}", f"{MAKER_SENDER} C {FEED} CXL00001")
    await asyncio.sleep(0.4)

    print_section("LEGACY TAKER SIGN CHECK")
    import importlib.util

    spec = importlib.util.spec_from_file_location("legacy_taker", os.path.join(os.getcwd(), "taker", "taker.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    taker = module.Taker(None)
    taker.apply_fill("S", 7, 600)
    print("legacy_taker_position_after_sell", taker.position)
    print("legacy_taker_cash_after_sell", taker.cash)

    await nc.drain()


if __name__ == "__main__":
    asyncio.run(main())
