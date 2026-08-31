# TRV Trading Desk — Design Contract

This document defines the implementation-critical architecture and safety invariants for the candidate trading desk.

It intentionally avoids repeating exchange wire details already defined in `PROTOCOL.md`.

Sources of truth:

- `PROTOCOL.md` — exchange protocol and external wire semantics
- `DESIGN.md` — desk architecture, internal contracts, and safety invariants
- `NOTES.md` — investigation evidence, probes, rejected assumptions, and engineering decisions

Version 1 trades one contract configured by `TAKER_FEED`.

---

## 1. Design Priorities

Priority order:

1. Capital preservation and hard risk control
2. Correct execution accounting
3. Runtime reliability and fail-closed behaviour
4. Positive trading economics
5. Delivery speed
6. Architectural elegance

Minimum implementation principle:

> Implement only what is required for a correct, safe, measurable trading desk.

Do not add speculative features or premature abstractions.

Trading correctness and risk controls must not be simplified merely to reduce code.

---

## 2. Runtime Architecture

The desk contains three trading processes:

| Process | Language | Primary responsibility |
|---|---|---|
| Taker | Python | Existing opportunistic taker strategy |
| Quoter | Java | Passive market making and inventory control |
| Hedger | Python | Desk-wide position accounting and risk reduction |

Communication uses the supplied NATS / JetStream / KV infrastructure.

High-level flow:

```text
Exchange
   |
NATS / JetStream / KV
   |
   +---- Taker
   |
   +---- Quoter
   |
   +---- Hedger
            |
            +---- authoritative desk position
            |
            +---- desk.risk.<FEED>
                         |
                         v
                      Quoter
```

The Hedger is the authority for desk-wide net position.

The Quoter also tracks its own inventory for local quote control.

---

## 3. Execution Accounting Invariants

Exchange execution events are authoritative.

The Hedger subscribes to exactly:

```text
ex.md.<FEED>.<TAKER_SENDER>
ex.md.<FEED>.<SENDER>
ex.md.<FEED>.<HEDGER_SENDER>
```

Production accounting must not combine these exact subscriptions with:

```text
ex.md.<FEED>.*
```

because the same physical trade may otherwise be observed more than once.

For sender-specific execution events:

```text
T = tracked sender owns the incoming/aggressor order
E = tracked sender owns the resting order
```

Therefore:

```text
T tracked side = aggressorSide
E tracked side = opposite(aggressorSide)
```

Position delta:

```text
Buy  -> +qty
Sell -> -qty
```

Both `E` and `T` are execution-bearing and must be processed.

Desk position:

```text
deskPosition
=
takerPosition
+ quoterPosition
+ hedgerPosition
```

Requested order quantity and order acknowledgements are not authoritative position changes.

### Execution deduplication

Version 1 deduplication key:

```text
(
  trackedSender,
  eventType,
  eventTimestamp,
  matchId,
  incomingOrderId,
  restingOrderId,
  qty,
  price,
  aggressorSide
)
```

Do not deduplicate using `matchId` alone.

---

## 4. Fill-and-Kill Accounting

The runtime behaviour verified against the supplied exchange is:

```text
F may execute partially.
Y <n> reports immediately traded volume.
The unfilled remainder is cancelled.
```

Position accounting must still use authoritative `E` / `T` execution events.

Never infer that the requested `F` quantity was fully executed.

After a hedge attempt:

1. observe authoritative executions,
2. recompute desk position,
3. use fresh BBO,
4. only then decide whether another hedge is required.

---

## 5. Desk Startup Gate

The desk starts fail closed.

Initial Hedger state:

```text
UNKNOWN
```

Required clean-session sequence:

```text
1. Connect to NATS
2. Load and validate metadata
3. Subscribe to Taker execution feed
4. Subscribe to Quoter execution feed
5. Subscribe to Hedger execution feed
6. Subscribe to BBO
7. Flush / confirm subscriptions
8. Hedger establishes authoritative initial desk position
9. Hedger publishes first valid non-UNKNOWN desk.risk message
10. Taker and Quoter may begin creating exposure
```

Before step 9:

```text
Taker orders  = 0
Quoter orders = 0
```

A timeout alone must never convert:

```text
UNKNOWN -> SAFE
```

Preferred Taker implementation is a startup/process gate outside the legacy trading logic.

Legacy Taker code should only be changed when:

- controlled evidence proves a material correctness bug, or
- a minimal integration change is required for a desk-wide safety invariant.

---

## 6. Desk Risk Coordination Protocol

Internal subject:

```text
desk.risk.<FEED>
```

Payload:

```text
<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <state> <direction>
```

Where:

```text
state =
UNKNOWN
SAFE
CONTROLLED
EMERGENCY
```

and:

```text
direction =
B   buy reduces desk risk
S   sell reduces desk risk
X   no immediate hedge required
```

Example:

```text
1234567890123 42 ABCD 7 6 15 CONTROLLED S
```

### Publishing

The Hedger publishes:

- on desk-position change,
- on risk-state change,
- and as a heartbeat at least every 250 ms.

`seq` increases monotonically for the lifetime of one Hedger process.

Exchange executions remain authoritative.

`desk.risk` is advisory coordination state.

### Quoter consumption

The Quoter must:

- validate the complete eight-field message,
- reject malformed messages,
- reject a message for the wrong feed,
- reject duplicate or lower sequence numbers while the current risk-message epoch remains trusted,
- track freshness using local monotonic receipt time,
- treat risk as stale after 1000 ms without a valid locally received message.

Risk staleness:

```text
time since last valid local receipt > 1000 ms
=> UNKNOWN
=> fail closed
```

The remote `ts_ns` field is parsed and retained for diagnostics and investigation, but it is not authoritative for staleness calculation.

Known NATS connection loss also immediately invalidates readiness.

### Sequence epoch reset

`seq` is guaranteed monotonic only for one Hedger process lifetime.

Within a trusted producer epoch:

```text
seq <= lastAcceptedSeq
=> ignore
```

If runtime trust is lost because of:

- risk staleness,
- NATS disconnect,
- reconnect,
- or equivalent startup re-establishment,

the Quoter must:

```text
enter UNKNOWN
invalidate the previous sequence epoch
require a new valid desk.risk message
use that message to establish a new sequence baseline
```

This allows a restarted Hedger to begin a new sequence epoch without allowing lower sequence numbers during an otherwise healthy epoch.

---

## 7. Quoter Readiness

The Quoter may quote only when all required runtime state is valid.

Required:

```text
valid configuration
AND
valid instrument metadata
AND
NATS connected
AND
valid fresh two-sided BBO
AND
valid fresh desk-risk message
AND
desk-risk state != UNKNOWN
```

BBO freshness threshold:

```text
MARKET_DATA_STALE_MS = 3000
```

Desk-risk freshness threshold:

```text
RISK_STALE_MS = 1000
```

Any required state becoming invalid immediately makes the Quoter not ready.

### Reconnect policy

Reconnect starts a clean runtime trust state.

After disconnect or reconnect:

```text
old BBO trust       = invalid
old desk-risk trust = invalid
readiness           = false
```

The Quoter must receive:

```text
a new valid fresh BBO
AND
a new valid fresh desk-risk message
```

before becoming ready again.

Old runtime state is not reused.

This policy may be reconsidered only if measured evidence shows a material performance problem.

---

## 8. BBO Startup Behaviour

Version 1 uses the minimum live-subscription approach first.

Startup sequence:

```text
subscribe ex.bbo.<FEED>
flush subscription registration
wait for first valid live BBO
```

The Quoter remains not ready until a valid BBO is received.

Startup BBO wait threshold:

```text
3000 ms
```

If no valid BBO arrives within 3000 ms:

```text
remain not ready
emit a warning log
do not auto-promote readiness
```

The 3000 ms threshold matches the existing market-data freshness threshold.

A timeout is diagnostic only. It never causes fail-open behaviour.

If controlled runtime evidence shows that live BBO updates can legitimately take longer, investigate JetStream latest/replay semantics before changing this rule.

---

## 9. Quoter Fair Value

Primary reference:

```text
mid = (bestBid + bestAsk) / 2
```

Top-book imbalance:

```text
imbalance
=
(bidQty - askQty)
/
(bidQty + askQty)
```

Raw microprice adjustment:

```text
rawAdjustment
=
0.5
* (ask - bid)
* imbalance
```

Configured bound:

```text
bound
=
maxMicroPriceAdjustmentTicks
* tickSize
```

Final adjustment:

```text
boundedAdjustment
=
clamp(rawAdjustment, -bound, +bound)
```

Fair value:

```text
fair
=
mid
+ boundedAdjustment
```

This is a bounded market-making adjustment, not a directional prediction model.

---

## 10. Adaptive Value Band

Define:

```text
cheapValue
=
fairValue - valueBand

expensiveValue
=
fairValue + valueBand
```

Value band:

```text
valueBandTicks
=
baseBand
+ spreadComponent
+ volatilityComponent
```

and remains bounded by configured minimum and maximum values.

Short-term movement uses EWMA of absolute fair-value movement.

Valuation signal:

```text
Cheap      = -1
Fair       =  0
Expensive  = +1
```

Valuation adjustment:

```text
valuationAdjustmentTicks
=
-valuationSignal
* maxValuationAdjustmentTicks
```

Therefore:

```text
cheap market      -> reservation price moves upward
expensive market  -> reservation price moves downward
```

---

## 11. Quoter Inventory and Risk Priority

Target Quoter inventory is approximately zero.

Inventory control dominates valuation opportunity.

Conceptually:

```text
reservationPrice
=
fairValue
+ valuationAdjustment
- inventoryAdjustment
```

Inventory skew may be nonlinear and should strengthen as position approaches limits.

Risk priority:

```text
EMERGENCY / HARD RISK
>
CONTROLLED / SOFT RISK
>
Minimum Edge
>
Valuation
>
Quote competitiveness
```

---

## 12. Quoter Risk Behaviour

### Normal / SAFE

Normal two-sided quoting is allowed.

Minimum Edge remains mandatory.

### Quoter soft risk

When local Quoter inventory reaches its soft threshold:

- strengthen inventory skew,
- make the risk-increasing side less competitive,
- allow limited relaxation only on the risk-reducing side.

### Quoter hard risk

For excessive long inventory:

```text
cancel bid
prohibit new bids
retain or improve risk-reducing ask
allow one-sided quoting
```

For excessive short inventory, reverse the sides.

Risk reduction may override ordinary Minimum Edge rules.

### Desk CONTROLLED

If:

```text
direction = S
```

selling reduces desk risk.

The Quoter must suppress bids that could increase long desk exposure or compete with the Hedger's sell.

If:

```text
direction = B
```

apply the opposite behaviour.

### Desk EMERGENCY

Immediately:

- cancel the risk-increasing quote side,
- prohibit new risk-increasing orders,
- allow one-sided quoting,
- permit risk-reducing action to override Minimum Edge.

Emergency cancellation should be dispatched in the same event-processing cycle.

Local target:

```text
<= 100 ms from receiving valid EMERGENCY state
```

### Risk override logging

Desk risk takes priority over normal Quoter economics.

Whenever desk risk causes an actual suppression or override transition, log enough information to explain the decision.

At minimum:

```text
risk state
direction
net position
suppressed side or overridden action
reason
```

Example:

```text
RISK_OVERRIDE state=CONTROLLED direction=S net=8 suppressed=BID reason=desk_risk_reduction
```

Avoid logging every unchanged heartbeat.

Log on meaningful transitions such as:

- risk-state change,
- suppression-state change,
- actual risk override action,
- risk staleness,
- disconnect,
- reconnect.

---

## 13. Hedger Risk States

Desk position:

```text
deskPosition
=
Taker
+ Quoter
+ Hedger
```

### SAFE

```text
abs(deskPosition) <= deskSoftLimit
```

Behaviour:

```text
publish SAFE
direction = X
no hedge required
```

### CONTROLLED

```text
deskSoftLimit < abs(deskPosition) < deskHardLimit
```

Goal:

```text
reduce exposure toward the safe boundary
```

Default execution mechanism:

```text
Fill-and-Kill
```

Each hedge attempt must:

- use fresh BBO,
- use bounded quantity,
- never exceed required risk reduction,
- wait for authoritative execution accounting before the next hedge decision.

### EMERGENCY

```text
abs(deskPosition) >= deskHardLimit
```

Capital preservation dominates normal trading economics.

The Hedger may cross the spread aggressively when necessary to reduce dangerous exposure.

---

## 14. Risk Configuration

Initial environment configuration:

```text
QUOTER_SOFT_POS
QUOTER_HARD_POS
DESK_SOFT_POS
DESK_HARD_POS
```

Required validation:

```text
0 < QUOTER_SOFT_POS < QUOTER_HARD_POS

0 < DESK_SOFT_POS < DESK_HARD_POS

QUOTER_HARD_POS <= DESK_HARD_POS
```

Effective limits must also respect the exchange instrument position limit.

Invalid configuration is a fail-closed startup error.

---

## 15. NATS Runtime Contract

Metadata:

```text
KV bucket: EX_META
key: TAKER_FEED
```

Desk risk:

```text
core NATS subject:
desk.risk.<FEED>
```

BBO:

```text
ex.bbo.<FEED>
```

`PROTOCOL.md` identifies BBO as JetStream-published.

Version 1 initially prefers a core live subscription.

The unresolved runtime question is not whether future publications can be received, but whether startup requires retained/latest-message semantics instead of waiting for the next live BBO.

Connection lifecycle is fail closed.

Known connection states other than:

```text
CONNECTED
```

must not satisfy readiness.

Verified jnats 2.20.5 lifecycle events include:

```text
CONNECTED
CLOSED
DISCONNECTED
RECONNECTED
RESUBSCRIBED
DISCOVERED_SERVERS
LAME_DUCK
```

Fail-closed handling:

```text
DISCONNECTED -> readiness false
CLOSED       -> readiness false
LAME_DUCK    -> readiness false
RECONNECTED  -> readiness remains false until fresh BBO and desk-risk arrive
RESUBSCRIBED -> does not by itself restore readiness
```

---

## 16. Deferred Features

Do not implement without measured evidence:

- multi-contract trading,
- predictive directional models,
- complex dynamic quote sizing,
- persistent local trading state,
- speculative abstraction layers,
- additional accounting streams,
- automatic changes to legacy Taker behaviour.

---

## 17. Remaining Runtime Investigation

The following still require controlled investigation against the supplied stack.

### 17.1 Metadata KV

Verify that the supplied environment allows:

```text
EX_META
```

to be accessed as expected and that the `TAKER_FEED` metadata value matches the current Java metadata parser contract.

### 17.2 BBO startup semantics

Verify that a core live subscription receives a valid new:

```text
ex.bbo.<FEED>
```

message within the 3000 ms startup threshold under normal supplied-stack conditions.

If not, investigate JetStream latest/replay semantics before changing the design.

Resolved runtime findings must be recorded in `NOTES.md`.
