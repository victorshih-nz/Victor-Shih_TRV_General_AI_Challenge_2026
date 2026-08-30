# TRV Trading Desk — Design

## 1. Design Principles

1. Capital preservation first.
2. Never take unnecessary risk for turnover.
3. Inventory control overrides valuation opportunity.
4. Desk risk reduction overrides ordinary quote economics.
5. Complexity must earn its place through measurable improvement.

Executable priority:

1. Hard Risk / Emergency
2. Soft Risk / Controlled reduction
3. Minimum Edge
4. Valuation signal
5. Spread capture / competitiveness

Ordinary quoting must not intentionally submit negative-expectancy prices merely to obtain fills.

Controlled trading cost is acceptable when reducing dangerous exposure.

## 2. Architecture

```text
                         Exchange
                            |
                  NATS / JetStream / KV
                            |
          +-----------------+-----------------+
          |                 |                 |
       Taker             Quoter            Hedger
       Python              Java             Python
          |                 |                 |
          |        own inventory only         |
          |                 |                 |
          +------ Exchange E executions ------+
                            |
                            v
                   Hedger position engine
                            |
                    Desk net position
                            |
                            v
                    desk.risk.<FEED>
                            |
                            v
                         Quoter
                    side suppression
```

Version 1 trades one contract from `TAKER_FEED`.

## 3. Desk Position Accounting Protocol v1

### 3.1 Single Source of Truth

Exchange `E` executions are authoritative.

Hedger subscribes to:

- `ex.md.<FEED>.<TAKER_SENDER>`
- `ex.md.<FEED>.<SENDER>`
- `ex.md.<FEED>.<HEDGER_SENDER>`

Hedger maintains:

```text
takerPosition
quoterPosition
hedgerPosition

deskPosition
=
takerPosition
+ quoterPosition
+ hedgerPosition
```

Quoter maintains only its own inventory.

No process republishes fills into a second accounting stream.

### 3.2 Execution Side

For:

```text
<ts> E <incoming> <resting> <qty> <price> <matchid> <aggressorSide>
```

If the tracked sender owns `incoming`:

```text
trackedSide = aggressorSide
```

If the tracked sender owns `resting`:

```text
trackedSide = opposite(aggressorSide)
```

Then:

```text
Buy  => +qty
Sell => -qty
```

The Hedger keeps bounded execution deduplication state.

Version 1 exact deduplication key:

`(trackedSender, eventTimestamp, matchId, incomingOrderId, restingOrderId, qty, price, aggressorSide)`

Rationale:

- `trackedSender` keeps the two desk sides of an accidental self-trade distinct for accounting.
- the remaining fields identify the exact exchange execution event;
- a JetStream redelivery of the same event produces the same key;
- separate partial executions remain distinct if timestamp and/or execution identity differs.

Batch 0 must still verify observed `matchId` behaviour, but implementation must not rely on `matchId` alone for deduplication.

### 3.3 Startup Gate (Desk-wide)

Hedger state begins as `UNKNOWN`.

Required clean-session sequence:

1. Hedger connects to NATS.
2. Hedger loads and validates instrument metadata.
3. Hedger subscribes to Taker, Quoter, and Hedger sender-specific execution feeds.
4. Hedger subscribes to the configured feed BBO.
5. Hedger flushes/confirms subscriptions are active.
6. Hedger publishes the first non-UNKNOWN `desk.risk.<FEED>` message, normally `SAFE` with position `0` for a fresh gated session.
7. Only after that readiness point may both Taker and Quoter create exposure.

Before readiness:

```text
Taker orders  = 0
Quoter orders = 0
Taker fills   = 0
Quoter fills  = 0
```

A timeout alone must never convert `UNKNOWN` to `SAFE`.

### Taker gate implementation priority

Preferred minimal implementation:

```text
Taker container/process starts
        |
        v
wait for desk-ready
        |
        v
exec python taker.py
```

The readiness signal is satisfied by the first valid non-UNKNOWN Hedger risk state or a deliberately equivalent explicit ready signal.

This approach keeps legacy trading logic unchanged.

Alternative:

- add a minimal Taker-side guard that refuses to submit orders while desk state is UNKNOWN,
- only if the container/startup-layer gate is insufficient,
- document it as a risk-correctness exception under the Legacy Taker policy.

The Taker must never be left completely ungated.

## 4. Desk Risk Coordination Protocol v1

Subject:

`desk.risk.<FEED>`

Payload:

```text
<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <UNKNOWN|SAFE|CONTROLLED|EMERGENCY> <B|S|X>
```

Hedger sends the message:

- on every desk-position or risk-state change, and
- as a heartbeat at least every 250 ms.

`seq` increases monotonically for the lifetime of the Hedger process.

Quoter:

- ignores a sequence lower than the last accepted sequence,
- treats the risk feed as stale after 1000 ms without a valid message,
- on stale risk state, immediately enters `UNKNOWN`, cancels all resting quotes, and submits no new orders.

Last field means required hedge direction:

- `B`: buy reduces desk risk
- `S`: sell reduces desk risk
- `X`: no immediate hedge

This message is advisory. Exchange executions remain authoritative.

### Quoter response

If state is `SAFE`:

- normal quoting rules apply.

If state is `CONTROLLED` and hedge direction is `S`:

- immediately cancel any active bid that could increase long desk exposure or absorb the Hedger's sell,
- suppress new bids until the risk state improves,
- keep or improve risk-reducing ask subject to controlled-risk rules.

If hedge direction is `B`, apply the opposite behaviour.

If state is `EMERGENCY`:

- the risk-increasing side must be cancelled immediately,
- cancellation must be dispatched in the same event-processing cycle, with a local target of <= 100 ms from receipt,
- after EMERGENCY is recognised, no new risk-increasing order may be submitted,
- temporary one-sided quoting is explicitly allowed,
- risk-reducing quoting may fully override Minimum Edge.

This coordination also reduces the chance that the Hedger trades against the desk's own Quoter.

## 5. Quoter Pricing Model

```text
Cheap Value < Fair Value < Expensive Value
```

- Fair Value: central estimate.
- Cheap/Expensive: adaptive opportunity/uncertainty band.

```text
                 Market BBO
                     |
                     v
                 Fair Value
                     |
        +------------+------------+
        |                         |
      Spread                Recent Movement
        |                         |
        +------------+------------+
                     |
                     v
              Adaptive Value Band
              /                 \
         Cheap Value       Expensive Value
              \                 /
               Valuation Signal
                      |
                      v
            Valuation Adjustment
                      |
Inventory ------------+
                      |
                      v
               Inventory Skew
                      |
                      v
              Reservation Price
                      |
               Minimum Edge
                      |
                Risk Controls
                      |
               +------+------+
               |             |
               v             v
              Bid           Ask
```

## 6. Fair Value

Primary anchor:

```text
mid = (bestBid + bestAsk) / 2
```

A bounded top-of-book microprice/imbalance adjustment may shift Fair Value slightly.

It must remain small relative to the adaptive value band.

The Quoter remains a market maker, not a directional prediction engine.

## 7. Adaptive Value Band

```text
cheapValue     = fairValue - valueBand
expensiveValue = fairValue + valueBand
```

Tick-based band:

```text
valueBandTicks
=
baseBand
+ spreadComponent
+ volatilityComponent
```

Bounded:

```text
MIN_VALUE_BAND <= valueBandTicks <= MAX_VALUE_BAND
```

## 8. Short-Term Volatility

Use a simple EWMA of absolute Fair Value movement:

```text
vol_t
=
alpha * abs(fair_t - fair_t-1)
+ (1 - alpha) * vol_t-1
```

Use tick scale, not absolute-price percentage.

## 9. Valuation Signal

Continuous bounded signal:

```text
Cheap              Fair               Expensive
 -1                 0                    +1
```

```text
valuationAdjustmentTicks
=
valuationSignal
* maxValuationAdjustmentTicks
```

All offsets are tick-based, normalized and bounded.

In NORMAL state, valuation can never push a quote through Minimum Edge.

## 10. Inventory Control

Target Quoter inventory is approximately zero.

Signed ratio:

```text
inventoryRatio
=
position / softPositionLimit
```

### Non-linear skew

Conceptually:

```text
skewMagnitude
=
K1 * abs(inventoryRatio)
+
K2 * abs(inventoryRatio)^3
```

Required behaviour:

| Inventory | Behaviour |
|---|---|
| Small | Mild skew |
| Medium | Meaningful skew |
| Large | Aggressive skew |
| Near hard limit | Risk dominates |

## 11. Reservation Price

```text
reservationPrice
=
fairValue
+ valuationAdjustment
- inventoryAdjustment
```

Inventory risk overrides valuation opportunity.

## 12. Quoter Position Zones

### NORMAL

```text
abs(position) < softLimit
```

- two-sided quoting
- Minimum Edge is mandatory
- valuation and spread capture active

### SOFT RISK

```text
softLimit <= abs(position) < hardLimit
```

- inventory skew strengthens
- risk-increasing side becomes less competitive and still requires full Minimum Edge
- risk-reducing side may relax Minimum Edge only to:

```text
max(1 tick, ceil(normalMinimumEdge / 2))
```

- desk CONTROLLED state may suppress the risk-increasing side entirely

### HARD RISK

```text
abs(position) >= hardLimit
```

For long Quoter inventory:

1. immediately cancel active bid(s),
2. prohibit new bids that increase long exposure,
3. retain or actively improve ask(s) that reduce long inventory,
4. allow temporary one-sided quoting,
5. fully permit risk-reducing orders to override Minimum Edge.

For short inventory, reverse the sides.

Hard-risk behaviour is explicit and immediate; it is not merely an additional skew.

## 13. Minimum Edge

Normal quoting requires a minimum expected edge relative to Fair Value.

Initial form:

```text
minimumEdgeTicks
=
max(
    minimumTickEdge,
    volatilityBuffer,
    hedgeCostBuffer
)
```

Priority rules:

### NORMAL
Minimum Edge cannot be crossed by valuation.

### SOFT / CONTROLLED
Risk-increasing side retains full Minimum Edge.

Risk-reducing side may relax only to:

```text
max(1 tick, ceil(normalMinimumEdge / 2))
```

### HARD / EMERGENCY
Risk-reducing action may fully ignore Minimum Edge.

Risk-increasing action is prohibited.

## 14. Quote Construction

1. Confirm metadata ready
2. Confirm Hedger state not UNKNOWN
3. Validate fresh two-sided BBO
4. Calculate Fair Value
5. Apply bounded microprice adjustment
6. Update EWMA volatility
7. Calculate adaptive valueBand
8. Derive Cheap/Expensive
9. Calculate normalized valuation signal
10. Calculate valuation adjustment
11. Calculate non-linear inventory skew
12. Calculate reservation price
13. Apply risk-state rules
14. Apply Minimum Edge according to risk priority
15. Round to valid tick
16. Enforce metadata price band
17. Cancel/suppress prohibited side first
18. Submit/cancel/replace remaining quotes safely

## 15. Risk Limit Configuration

Version 1 reads risk thresholds from environment variables:

```text
QUOTER_SOFT_POS=6
QUOTER_HARD_POS=12
DESK_SOFT_POS=6
DESK_HARD_POS=15
```

These are initial local defaults and may be tuned only from measured evidence.

Validation:

```text
0 < QUOTER_SOFT_POS < QUOTER_HARD_POS
0 < DESK_SOFT_POS < DESK_HARD_POS
QUOTER_HARD_POS <= DESK_HARD_POS
```

The Quoter effective hard limit must also not exceed the exchange instrument position limit.

Invalid configuration is a fail-closed startup error.

## 16. Order Size

Version 1 uses bounded fixed quote clip size.

Dynamic quote sizing is deferred unless measured evidence justifies it.

## 17. Hedger Design

```text
Desk Position
=
Taker + Quoter + Hedger
```

Hedger uses three zones.

### SAFE

```text
abs(deskPosition) <= deskSoftLimit
```

- publish SAFE
- no hedge
- avoid unnecessary spread crossing

### CONTROLLED

```text
deskSoftLimit < abs(deskPosition) < deskHardLimit
```

Goal:

- reduce exposure back to the safe boundary,
- not necessarily to zero.

Default execution:

- Fill-and-Kill (`F`)
- fresh opposite-side BBO
- bounded clip
- clip no larger than required reduction and preferably no larger than observed executable top-of-book volume
- paced retry only while exposure remains above soft limit
- TPS limits respected

Before sending the hedge, publish CONTROLLED risk direction so Quoter suppresses the side that could work against the hedge.

### EMERGENCY

```text
abs(deskPosition) >= deskHardLimit
```

Goal:

- leave the hard zone as fast as executable liquidity allows,
- continue toward or inside the soft limit,
- not achieve perfect zero.

Execution:

1. publish EMERGENCY risk state immediately,
2. Quoter cancels risk-increasing resting side,
3. use Fill-and-Kill (`F`) as the hedge order type,
4. compute each hedge clip as:

```text
min(
    exposureNeededToSoft,
    configuredEmergencyClip,
    currentOppositeBboVolume,
    exchangeMaxVolume
)
```

5. if current opposite BBO volume is smaller than total required reduction, hedge only the currently executable amount,
6. after each F result, immediately re-evaluate desk position and require a fresh BBO before the next hedge,
7. never place a resting `L` order merely to wait for the remaining emergency reduction,
8. if no opposite executable BBO exists, send no order and wait only for fresh market data,
9. respect exchange TPS protection,
10. ignore Minimum Edge for risk reduction,
11. accept bounded adverse execution price when necessary.

Because protocol v2.3+ F is atomic, the Hedger must not assume a partial F fill. A rejected/unfilled F causes no position change and should be retried only from fresh market state.

## 18. Market Readiness and Staleness

Quoter order entry is disabled until:

- EX_META for the feed is valid,
- valid two-sided BBO exists,
- Hedger desk state is not UNKNOWN.

Initial configurable threshold:

```text
MARKET_DATA_STALE_MS = 3000
```

If:

```text
now - lastValidBboTime > MARKET_DATA_STALE_MS
```

or the BBO becomes invalid:

1. cancel all Quoter resting orders,
2. submit no new quote,
3. remain fail-closed until fresh valid state returns.

The threshold is configurable and must be tuned cautiously; it is a safety mechanism, not an alpha parameter.

## 19. Legacy Taker Policy

The legacy Taker will not be refactored or behaviourally changed unless:

1. controlled testing confirms a material defect; or
2. a minimal integration change is required to enforce a desk-wide safety invariant, such as preventing order entry before Hedger readiness.

Still prohibited:

- cosmetic refactoring,
- strategy rewrite,
- unrelated momentum-logic changes,
- unrelated cleanup.

Batch 0 must immediately verify:

1. order-entry subject behaviour,
2. sell-side position sign,
3. whether Taker can create exposure before Hedger readiness under the supplied startup path.

The current sell-side implementation is treated as a high-confidence defect hypothesis.

If the sell-sign probe confirms that sell fills increase rather than decrease reported position, Conditional Job 0.3 begins immediately with a minimal correction and regression test.

Preferred startup-gate solution remains outside `taker.py` trading logic: gate process activation until Hedger readiness. If that is insufficient, the smallest evidence-based Taker-side guard is permitted as a risk-correctness exception.

## 20. Failure Behaviour

Safe default:

**Do not create new risk when critical state is uncertain.**

Fail-closed conditions include:

- metadata unavailable/invalid,
- Hedger state UNKNOWN,
- no valid BBO,
- BBO stale,
- uncertain order state,
- uncertain position state,
- repeated request failures.

The process may stay alive, but new exposure is prohibited until trusted state returns.

## 21. Confirmed Decisions

| Decision | Version 1 |
|---|---|
| Quoter | Java |
| Hedger | Python |
| Instrument | Single contract |
| Contract source | `TAKER_FEED` |
| Authoritative desk fills | Exchange `E` events |
| Desk position owner | Hedger |
| Internal coordination | `desk.risk.<FEED>` with seq/position/limits/state/direction |
| Fair Value | midpoint + bounded microprice |
| Cheap / Expensive | adaptive band |
| Value Band | spread + EWMA movement, bounded |
| Price adjustments | normalized tick-based |
| Inventory | non-linear skew |
| Quoter zones | Normal / Soft / Hard |
| Desk zones | Safe / Controlled / Emergency |
| Hedge order type | Fill-and-Kill (`F`) |
| Emergency target | leave hard zone, then move toward soft |
| Temporary one-sided quote | Allowed for risk reduction |
| Market stale default | configurable 3000 ms |
| Risk heartbeat | <=250 ms interval; stale after 1000 ms |
| Emergency cancel dispatch | local target <=100 ms |
| Initial Q soft/hard | 6 / 12 contracts |
| Initial Desk soft/hard | 6 / 15 contracts |
| Dynamic sizing | Deferred |
| Legacy Taker | verify fast, fix immediately if confirmed |
| Primary objective | Capital preservation |
| Normal valuation vs Minimum Edge | Minimum Edge wins |
| Risk reduction vs Minimum Edge | Risk reduction wins |
