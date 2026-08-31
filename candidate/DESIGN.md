# TRV Trading Desk — Agent Contract

Use this file as the implementation contract attached to Copilot/Qwen.

Source of truth:
- `PROTOCOL.md` = external exchange protocol
- `DESIGN.md` = internal invariants
- `NOTES.md` = probes/evidence/history

Do not use old design drafts.

## 1. Scope and priorities

V1:
- one contract from `TAKER_FEED`
- Taker = Python
- Quoter = Java
- Hedger = Python

Priority:
```text
Capital preservation
> execution correctness
> fail-closed reliability
> trading economics
> delivery speed
```

Minimum necessary implementation only. No speculative features or premature abstraction.

## 2. Desk position accounting

Hedger owns authoritative desk-wide position.

Subscribe exactly:
```text
ex.md.<FEED>.<TAKER_SENDER>
ex.md.<FEED>.<SENDER>
ex.md.<FEED>.<HEDGER_SENDER>
```

Do not combine with `ex.md.<FEED>.*` in production.

Execution semantics:
```text
T = tracked sender owns incoming/aggressor order
E = tracked sender owns resting order

T side = aggressorSide
E side = opposite(aggressorSide)

Buy  = +qty
Sell = -qty
```

Both `T` and `E` are execution-bearing.

```text
deskPosition
= takerPosition
+ quoterPosition
+ hedgerPosition
```

Never infer position from requested quantity or acknowledgements.

Dedup key:
```text
(trackedSender,eventType,eventTimestamp,matchId,
 incomingOrderId,restingOrderId,qty,price,aggressorSide)
```

Never deduplicate by `matchId` alone.

## 3. Fill-and-Kill

Verified runtime:
```text
F may partially execute
Y <n> = immediately traded volume
unfilled remainder = cancelled
```

Position remains based on `T` / `E`.

After each hedge:
```text
execution events
-> recompute position
-> require fresh BBO
-> decide next hedge
```

## 4. Startup gate

Initial Hedger state:
```text
UNKNOWN
```

Required sequence:
```text
1 connect NATS
2 load/validate EX_META
3 subscribe Taker execution feed
4 subscribe Quoter execution feed
5 subscribe Hedger execution feed
6 subscribe core ex.bbo.<FEED>
7 flush subscriptions
8 retrieve retained latest BBO from EX_MD
9 validate retained BBO
10 establish initial desk position
11 publish first valid non-UNKNOWN desk.risk
12 allow Taker/Quoter exposure
```

Before step 11:
```text
Taker orders  = 0
Quoter orders = 0
```

Timeout never causes `UNKNOWN -> SAFE`.

Prefer Taker startup gating outside legacy strategy logic.

Legacy Taker changes only when:
- controlled evidence proves a material defect, or
- minimal integration is required for desk safety.

## 5. desk.risk

Subject:
```text
desk.risk.<FEED>
```

Payload:
```text
<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <state> <direction>
```

State:
```text
UNKNOWN | SAFE | CONTROLLED | EMERGENCY
```

Direction:
```text
B = buy reduces risk
S = sell reduces risk
X = no immediate hedge
```

Hedger publishes:
- position change
- state change
- heartbeat <=250 ms

`seq` is monotonic for one Hedger process lifetime.

Quoter:
- requires exactly 8 fields
- rejects malformed/wrong-feed messages
- freshness uses local monotonic receipt time
- stale after 1000 ms without valid local receipt

Remote `ts_ns` is diagnostic only.

Stale risk or known NATS loss:
```text
UNKNOWN
not ready
```

Sequence handling:
```text
trusted epoch:
seq <= lastAcceptedSeq -> ignore

after stale/disconnect/reconnect/startup reset:
UNKNOWN
clear seq baseline
next valid message establishes new baseline
```

## 6. Quoter readiness

Ready only when:
```text
valid config
AND valid metadata
AND NATS connected
AND valid fresh two-sided BBO
AND valid fresh desk.risk
AND desk.risk != UNKNOWN
```

Thresholds:
```text
MARKET_DATA_STALE_MS = 3000
RISK_STALE_MS        = 1000
```

Reconnect:
```text
invalidate old BBO
invalidate old risk
readiness=false
require newly trusted BBO + desk.risk
```

## 7. BBO startup

Verified:
- core NATS receives future BBO updates
- startup cannot rely on a new publication while book is unchanged
- `EX_MD` retains `ex.bbo.>`
- retained latest may be empty/invalid

Startup:
```text
subscribe core ex.bbo.<FEED>
-> flush
-> get latest retained ex.bbo.<FEED> from EX_MD
-> validate
-> keep core subscription for live updates
```

Subscribe before retained lookup.

Retained BBO establishes market state only if:
```text
present
correct feed
correctly formed
two-sided valid
fresh
```

Otherwise remain not ready and wait for valid live BBO.

3000 ms is warning/staleness only; never auto-ready.

## 8. Metadata

```text
bucket = EX_META
key = configured feed value from TAKER_FEED
```

Example:
```text
TAKER_FEED=AAH6 -> key AAH6
```

Existing `Metadata.parse(feed,payload)` matches supplied runtime data.

Never use literal `"TAKER_FEED"` as KV key.

## 9. Fair value

```text
mid = (bid + ask) / 2

imbalance =
(bidQty - askQty) / (bidQty + askQty)

rawAdj =
0.5 * (ask - bid) * imbalance

bound =
maxMicroPriceAdjustmentTicks * tickSize

fair =
mid + clamp(rawAdj,-bound,+bound)
```

Adjustment must remain bounded.

## 10. Value and inventory

```text
cheapValue     = fairValue - valueBand
expensiveValue = fairValue + valueBand
```

Value band = base + spread + EWMA movement, bounded.

Signal:
```text
Cheap=-1
Fair=0
Expensive=+1
```

```text
valuationAdjustmentTicks
= -valuationSignal * maxValuationAdjustmentTicks
```

Reservation price:
```text
fairValue
+ valuationAdjustment
- inventoryAdjustment
```

Inventory risk overrides valuation opportunity.

## 11. Risk priority

```text
EMERGENCY/HARD
> CONTROLLED/SOFT
> Minimum Edge
> Valuation
> Competitiveness
```

SAFE: normal quoting.

Local hard long inventory:
```text
cancel/prohibit BID
retain/improve risk-reducing ASK
allow one-sided quoting
```
Short inventory: reverse sides.

Desk CONTROLLED:
```text
direction=S -> suppress BID
direction=B -> suppress ASK
```

Desk EMERGENCY:
- cancel risk-increasing side
- no new risk-increasing orders
- allow one-sided quoting
- risk reduction may override Minimum Edge

Emergency cancel dispatch target: `<=100 ms`.

Desk risk overrides local economics.

## 12. Risk logging

Log meaningful transitions only:
- state change
- suppression change
- actual override
- stale risk
- disconnect/reconnect

Include:
```text
state
direction
net position
suppressed/overridden action
reason
```

Do not log every heartbeat.

## 13. Hedger states

SAFE:
```text
abs(deskPosition) <= deskSoftLimit
direction=X
```

CONTROLLED:
```text
deskSoftLimit < abs(deskPosition) < deskHardLimit
```
Goal: reduce toward safe boundary.

Default hedge: Fill-and-Kill.

Each hedge:
- fresh BBO
- bounded quantity
- never exceed required reduction
- wait for authoritative executions before next decision

EMERGENCY:
```text
abs(deskPosition) >= deskHardLimit
```
Capital preservation dominates economics.

## 14. Risk config

Environment:
```text
QUOTER_SOFT_POS
QUOTER_HARD_POS
DESK_SOFT_POS
DESK_HARD_POS
```

Validation:
```text
0 < QUOTER_SOFT_POS < QUOTER_HARD_POS
0 < DESK_SOFT_POS   < DESK_HARD_POS
QUOTER_HARD_POS <= DESK_HARD_POS
```

Effective limits must respect exchange limits.

Invalid config = fail closed.

## 15. NATS lifecycle

Verified jnats 2.20.5 events:
```text
CONNECTED
CLOSED
DISCONNECTED
RECONNECTED
RESUBSCRIBED
DISCOVERED_SERVERS
LAME_DUCK
```

Policy:
```text
DISCONNECTED -> not ready
CLOSED       -> not ready
LAME_DUCK    -> not ready
RECONNECTED  -> still not ready
RESUBSCRIBED -> does not restore readiness
```

Only newly trusted runtime state restores readiness.

## 16. Deferred

Do not implement without evidence:
- multi-contract
- directional prediction
- complex dynamic sizing
- persistent local trading state
- speculative abstraction
- extra accounting streams
- unrelated Taker refactoring

## 17. Agent execution rules

1. Work only on the current Job.
2. Treat this file as the internal contract.
3. Use `PROTOCOL.md` for external wire details.
4. Use `NOTES.md` only for evidence/history when needed.
5. Do not revive old assumptions.
6. Do not guess unresolved runtime behaviour.
7. Stop on unexpected repo changes.
8. Prefer existing architecture over new abstractions.
9. Run focused tests, full relevant tests, and build validation.
10. Record new runtime findings in `NOTES.md`.
