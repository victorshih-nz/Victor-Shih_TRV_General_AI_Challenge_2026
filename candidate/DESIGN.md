# TRV Trading Desk — Agent Contract

Active implementation contract.

Source hierarchy:
- `TASK.md` = official requirement
- `PROTOCOL.md` = wire protocol
- `DESIGN.md` = active invariants/current task
- `NOTES.md` = evidence/history

If DESIGN conflicts with runtime evidence, stop and report before changing production code.

## 1. Scope / priorities

V1 trades one `TAKER_FEED`.

Seats: Taker = Python legacy; Quoter = Java liquidity provider; Hedger = Python desk-risk authority.

```text
Capital preservation
> execution correctness
> fail-closed reliability
> trading economics
> delivery speed
```

Minimum necessary implementation only. No speculative features, generic frameworks, multi-contract support, persistent DB, or unrelated cleanup.

## 2. Authoritative desk accounting

Hedger owns combined desk position.

Subscribe exactly:

```text
ex.md.<FEED>.<TAKER_SENDER>
ex.md.<FEED>.<SENDER>
ex.md.<FEED>.<HEDGER_SENDER>
```

No production wildcard accounting subscription.

```text
deskPosition = takerPosition + quoterPosition + hedgerPosition
```

Only `T/E` executions are position authority. Never infer position from request qty, `A.volume`, `Y <n>`, or seat status reports.

```text
T = tracked sender is aggressor
E = tracked sender is resting owner

T side = aggressorSide
E side = opposite(aggressorSide)

Buy = +qty
Sell = -qty
```

Public order ID is `<sender>:<orderId>`.

```text
T -> incoming sender == tracked sender
E -> resting sender  == tracked sender
```

Ownership mismatch means accounting uncertainty.

## 3. Dedup

Use:

```text
(trackedSender, eventType, eventTimestamp, matchId,
 incomingOrderId, restingOrderId, qty, price, aggressorSide)
```

Never dedup by `matchId` alone. A self-trade may create one valid event for each tracked desk sender; account each independently.

Bounded dedup rule:

```text
exact duplicate -> ignore
new key with capacity -> account + retain
new key when capacity exhausted -> trust lost; do not count
```

Do not silently evict old keys.

## 4. Accounting trust

Accounting starts unready.

After readiness, evidence that an execution may have been missed or misread causes:

```text
Accounting Trust = LOST
preserve last-known positions for diagnostics
desk state = UNKNOWN
Quoter/Taker create no new exposure
no automatic SAFE recovery
```

Examples: post-ready NATS disconnect, execution subscription trust loss, malformed/inconsistent `T/E`, sender mismatch, unknown sender-specific event, dedup exhaustion.

Unknown position must never be represented as zero. Time alone never recovers UNKNOWN.

Normal stream handling:

```text
valid A/C -> ignore for position
valid T/E -> validate + account
malformed/inconsistent T/E -> trust lost
unknown/unrecognisable event -> trust lost
```

## 5. Desk risk protocol

Publish `desk.risk.<FEED>`:

```text
<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <UNKNOWN|SAFE|CONTROLLED|EMERGENCY> <B|S|X>
```

Defaults:

```text
DESK_SOFT_POS = 6
DESK_HARD_POS = 15
0 < soft < hard
```

Mapping:

```text
abs(pos) < soft         -> SAFE
soft <= abs(pos) < hard -> CONTROLLED
abs(pos) >= hard        -> EMERGENCY

SAFE / UNKNOWN -> X
long risky desk -> S
short risky desk -> B
```

Publish on position/state change plus heartbeat about every 200 ms.

## 6. Hedger readiness

Before first non-UNKNOWN `desk.risk`, Hedger requires:
- trusted NATS connection
- valid metadata for `TAKER_FEED`
- exact Taker, Quoter, Hedger execution subscriptions installed
- BBO subscription installed
- subscription setup flushed/confirmed
- accounting trust intact

Only then may the fresh-session position be established as zero and first SAFE/non-UNKNOWN state be published.

This zero start is valid only because Taker and Quoter cannot create exposure before Hedger readiness. A timeout alone never establishes readiness.

Job 2.1 sends no hedge orders. Hedge execution readiness belongs to Job 2.2.

## 7. Taker safety gate

Legacy Taker change must stay minimal.

```text
fresh SAFE -> may send new Taker order

UNKNOWN / CONTROLLED / EMERGENCY
stale/no risk
transport trust lost
-> no new order
```

Freshness uses local monotonic receipt age `< 1000 ms`; sequence must not move backwards.

Check the gate:
1. before deciding to trade
2. again immediately before `ex.req` dispatch

No broad Taker refactor.

## 8. Quoter / recovery

Existing Job 1 Quoter behaviour is frozen unless concrete evidence shows a correctness defect.

If desk risk is stale or UNKNOWN: no new exposure; cancel resting exposure as required.

```text
direction S -> suppress/cancel risk-increasing bids
direction B -> suppress/cancel risk-increasing asks
```

Job 2.1 does not implement Hedger replay or automatic recovery:

```text
post-readiness trust loss -> UNKNOWN for remaining accounting epoch
```

Future memo: evaluate bounded JetStream exact-subject catch-up to establish a new trusted accounting epoch. Do not implement until separately approved.

## 9. Job 2.1 boundary

```text
2.1A Accounting hardening
2.1B Hedger runtime + risk publisher
2.1C Minimal Taker SAFE gate
2.1D Docker/startup integration
```

Deferred: hedge order execution/F sizing, controlled/emergency reduction logic, hedge TPS, oscillation tuning, JetStream accounting reconciliation, profitability tuning.

# 10. Current Micro Task — Job 2.1A

Goal: harden existing `hedger/accounting.py` into a production-safe accounting foundation.

Reuse `DeskPositionAccounting`. Do not create a second accounting architecture.

Required:
1. distinguish valid `A/C` from `T/E`
2. strict `T/E` parsing
3. sender ownership validation
4. signed side interpretation
5. per-sender and desk net position
6. full dedup key
7. fail-closed accounting trust
8. fail-closed dedup-capacity exhaustion
9. focused tests

Malformed/inconsistent execution evidence must not silently return zero.

Trust-loss cases include malformed `T/E`, invalid qty/side/required fields, wrong sender ownership, unknown sender-specific event, and dedup exhaustion.

Required response:

```text
mark accounting untrusted
raise clear accounting-uncertainty exception
do not apply uncertain execution
```

After trust loss, do not restore trust automatically and do not mutate authoritative position from later executions.

Valid `A/C`:

```text
delta = 0
position unchanged
trust unchanged
```

Dedup:

```text
exact duplicate -> delta 0
new event within capacity -> apply
new event when capacity exhausted -> trust lost
```

Minimum tests:
- incoming buyer/seller `T`
- resting buyer/seller `E`
- T/E ownership validation
- three-sender aggregation
- self-trade once per tracked sender
- exact duplicate ignored
- same matchId for legitimate distinct sender/event not collapsed
- valid `A/C` ignored
- malformed `T/E` -> trust lost
- ownership mismatch -> trust lost
- unknown/malformed event -> trust lost
- dedup exhaustion -> trust lost
- after trust loss, later execution does not mutate position

Prefer changing only:

```text
hedger/accounting.py
tests/test_desk_accounting_baseline.py
```

A tiny exception type may stay in `accounting.py`.

Do NOT add NATS runtime/subscriptions, metadata/BBO handling, risk publisher/heartbeat, Dockerfile changes, Taker/Quoter changes, hedge orders, startup gate, JetStream replay, or generic frameworks.

Validation:

```text
git status --short --branch
focused Hedger accounting tests
all existing Python tests
python compile check where relevant
git diff --check
git diff --stat
```

Inspect final diff. Do not commit, push, merge, create PR, or start Job 2.1B.

Return:
1. files changed
2. purpose
3. exact tests
4. results
5. git diff --stat
6. unresolved concern, if any

## 11. Agent rules

1. Work only on Current Micro Task.
2. Read source-of-truth files before editing.
3. Stop on unexpected unrelated changes.
4. Prefer existing code and minimum necessary implementation.
5. Risk/accounting uncertainty fails closed.
6. Never turn unknown position into assumed zero.
7. Run focused and broader relevant validation.
8. Do not commit, push, merge, or start next Micro Task without approval.
