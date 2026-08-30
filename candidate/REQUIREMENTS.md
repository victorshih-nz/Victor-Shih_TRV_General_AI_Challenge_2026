# TRV General AI Challenge 2026 — Requirements

## 1. Objective

Build a small, reliable trading desk consisting of:

- the existing taker,
- a new two-sided quoting strategy,
- a new hedger.

The desk should make money primarily by providing liquidity while keeping combined desk exposure low.

Priority:

1. Trading correctness
2. Capital preservation
3. Risk control
4. Reliability
5. Profitability
6. Delivery speed

Only functionality that directly contributes to correctness, risk control, profitability, grading compatibility, testing, or explainability should be added.

## 2. Desk Components

### 2.1 Existing Taker

- Existing Python process supplied with the challenge.
- Runs alongside the new Quoter and Hedger.
- Treated as production-owned legacy code.

The legacy Taker will not be refactored or behaviourally changed unless:

1. controlled testing confirms a material defect; or
2. a minimal integration change is required to enforce a desk-wide safety invariant, such as preventing order entry before Hedger readiness.

The current sell-side position accounting is a high-confidence defect hypothesis and is a mandatory Batch 0 verification item. If the controlled probe confirms it, the corrective Job 0.3 is entered immediately.

Still prohibited:

- cosmetic refactoring,
- strategy rewrite,
- momentum-logic improvement unrelated to a confirmed defect,
- unrelated cleanup.

A desk-wide startup safety gate is a permitted risk-correctness exception.

### 2.2 Quoter

The Quoter shall:

- Be implemented in Java.
- Use a NATS-supported Java client.
- Provide two-sided liquidity under normal operating conditions.
- Earn primarily through spread capture rather than directional speculation.
- Read sender identity from `$SENDER`.
- Connect using `$NATS_URL`.
- Trade the desk contract from `TAKER_FEED`.
- Read exchange metadata rather than assuming simulator constants.
- Maintain only its own inventory from its own execution events.
- Consume Hedger-published desk risk state for coordination.
- Avoid stale quote accumulation.
- Respect price, volume, position and transaction-rate constraints.
- Run headless in its Docker container.

Temporary one-sided quoting is explicitly permitted during risk reduction.

### 2.3 Hedger

The Hedger shall:

- Be implemented in Python.
- Read sender identity from `$HEDGER_SENDER`.
- Connect using `$NATS_URL`.
- Subscribe to execution events for the Taker, Quoter and Hedger sender IDs.
- Be the single source of truth for combined desk position.
- Publish desk risk state for Quoter coordination.
- Reduce material exposure promptly.
- Avoid unnecessary hedging inside the safe zone.
- Use Fill-and-Kill orders as the default hedge execution mechanism.
- Escalate hedge urgency when exposure enters the Emergency zone.
- Run headless in its Docker container.

## 3. Instrument Scope

Version 1 trades one contract.

`TAKER_FEED` is the common desk contract configuration source for all three seats.

A local fallback may be provided for development, but production logic must not depend on the sample market's hard-coded feed.

## 4. Desk Position Accounting Protocol v1

### 4.1 Authoritative Fill Source

The exchange execution event is the only authoritative fill source.

No seat republishes fills as a second accounting stream.

The Hedger subscribes to these exact execution subjects for the configured feed:

- `ex.md.<FEED>.<TAKER_SENDER>`
- `ex.md.<FEED>.<SENDER>`
- `ex.md.<FEED>.<HEDGER_SENDER>`

The Hedger accumulates signed position separately for:

- Taker
- Quoter
- Hedger

and calculates:

`deskPosition = takerPosition + quoterPosition + hedgerPosition`

### 4.2 Execution Interpretation

For an execution event:

`<ts> E <incoming:17> <resting:17> <volume> <price> <matchid> <aggressorSide>`

the Hedger determines which order belongs to the sender being tracked.

- If the tracked order is the incoming order, its side is `aggressorSide`.
- If the tracked order is the resting order, its side is the opposite of `aggressorSide`.
- Buy quantity is positive.
- Sell quantity is negative.

Execution processing must be idempotent. The implementation shall retain a bounded deduplication key sufficient to avoid processing the same sender execution twice.

### 4.3 Responsibility

- Hedger owns combined desk position.
- Quoter owns only Quoter inventory.
- Taker remains responsible for its own local reporting, but that report is not authoritative for Hedger accounting.
- Exchange execution events are authoritative.

### 4.4 Startup State

Before the Hedger has installed all required execution subscriptions, desk position state is `UNKNOWN`.

While desk state is `UNKNOWN`:

- Quoter must not create new exposure.
- Quoter must not submit initial quotes.
- Taker must also be prevented from creating new exposure.
- Hedger must not infer a zero position merely because no execution has yet been seen.

After subscriptions are active and startup state is established for the fresh grading session, Hedger publishes the first desk risk state.

## 5. Desk Risk Coordination Protocol v1

Hedger publishes advisory desk risk state on:

`desk.risk.<FEED>`

ASCII payload:

`<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <UNKNOWN|SAFE|CONTROLLED|EMERGENCY> <B|S|X>`

Fields:

- `ts_ns` = Hedger wall-clock/event timestamp in nanoseconds
- `seq` = monotonically increasing Hedger risk-message sequence
- `feed` = configured desk contract
- `net_position` = Hedger's current authoritative desk net position
- `soft_limit` = effective desk soft limit
- `hard_limit` = effective desk hard limit
- `state` = `UNKNOWN`, `SAFE`, `CONTROLLED`, or `EMERGENCY`
- final field = required hedge direction:
  - `B` = desk is short; buying reduces risk
  - `S` = desk is long; selling reduces risk
  - `X` = no immediate hedge direction

Hedger publishes on every position/state change and also emits a heartbeat at least every 250 ms.

Quoter ignores any `desk.risk` message with a sequence lower than the last accepted sequence.

If no valid desk-risk message is received for 1000 ms, Quoter treats desk state as `UNKNOWN`, cancels all resting quotes, and creates no new exposure.

This subject is advisory for coordination only. It is not an authoritative accounting source.

Quoter subscribes to this subject.

When desk state is CONTROLLED or EMERGENCY:

- If hedge direction is `S`, Quoter cancels/suppresses bids that could increase long desk exposure or absorb the Hedger's sell.
- If hedge direction is `B`, Quoter cancels/suppresses asks that could increase short desk exposure or absorb the Hedger's buy.
- Temporary one-sided quoting is allowed.
- On `EMERGENCY`, Quoter must dispatch cancellation of the risk-increasing resting side in the same processing cycle, with a local implementation target of <= 100 ms from receipt of the valid risk message.
- After recognising `EMERGENCY`, Quoter must not submit another risk-increasing order while that state remains active.

## 6. Exchange Protocol Requirements

The implementation must correctly support relevant Exchange Protocol v2.5 functions.

### Order Entry

Orders use:

`ex.req.<SENDER>`

The sender in the subject must match the sender in the message.

Required operations:

- Add
- Cancel
- Cancel-many where justified
- Self-trade prevention where appropriate

### Market Data

Consume:

- BBO
- execution events
- required order lifecycle events
- EX_META metadata

### Execution Accounting

One order may execute across multiple execution events.

Position must therefore be derived from actual execution events, never solely from add-order acceptance.

## 7. Exchange Metadata

Use instrument metadata where relevant:

- tick size
- reference price
- price band
- minimum volume
- maximum volume
- position limit
- maximum TPS

Sample-market absolute prices and limits are not production constants.

## 8. Trading-Critical Engineering Requirements

Mandatory candidate-defined safety requirements:

- Correct buy/sell signs
- Correct multiple-execution accumulation
- Execution deduplication
- Unique order IDs per sender
- Safe cancel/replace behaviour
- Stale-order protection
- Bounded position exposure
- Bounded pricing parameters
- TPS protection
- Exchange rejection handling
- Timeout handling
- Startup gating
- Stale-market-data protection
- Deterministic tick rounding
- Sufficient trading/risk logging
- No new risk while critical state is unknown

## 9. Risk Limit Configuration

Version 1 uses explicit environment configuration:

- `QUOTER_SOFT_POS` (initial local default: `6`)
- `QUOTER_HARD_POS` (initial local default: `12`)
- `DESK_SOFT_POS` (initial local default: `6`)
- `DESK_HARD_POS` (initial local default: `15`)

These are starting safety defaults, not final tuned values.

Required relationships:

- `0 < QUOTER_SOFT_POS < QUOTER_HARD_POS`
- `0 < DESK_SOFT_POS < DESK_HARD_POS`
- `QUOTER_HARD_POS <= DESK_HARD_POS`
- Quoter effective hard limit must not exceed the exchange instrument position limit.

Invalid configurations must fail closed rather than silently widening risk.

Batch 1/3 may tune these values using measured evidence, but the relationship rules remain mandatory.

## 10. Market Readiness Requirements

The Quoter must not place orders until:

1. instrument metadata is loaded and valid,
2. a valid two-sided BBO has been received,
3. Hedger desk state is no longer `UNKNOWN`,
4. market data is not stale.

Initial default:

`MARKET_DATA_STALE_MS = 3000`

This is a configurable defensive threshold, not a market-model constant.

If the BBO becomes stale or invalid:

- cancel all Quoter resting orders,
- submit no new quote,
- wait for valid fresh state.

## 11. Risk Priority

The executable priority is:

1. Hard Risk / Emergency
2. Soft Risk / Controlled reduction
3. Minimum Edge
4. Valuation signal
5. Spread capture / competitiveness

Rules:

- Normal: valuation may not cross Minimum Edge.
- Soft / Controlled:
  - risk-increasing side still requires the full Minimum Edge;
  - risk-reducing side may relax to `max(1 tick, ceil(normalMinimumEdge / 2))`.
- Hard / Emergency: risk-reducing action may fully override Minimum Edge; risk-increasing action is prohibited.

## 12. Docker and Runtime Requirements

Each process runs in its own Docker container.

Version 1 must provide a concrete desk-wide startup gate that prevents the legacy Taker from submitting orders until Hedger has established accounting readiness and published the first non-UNKNOWN `desk.risk.<FEED>` state (or an equivalent explicit ready signal).

Leaving the Taker completely ungated is not acceptable.

Preferred implementation is to gate Taker process activation at the container/startup layer so that `taker.py` does not become order-active until desk readiness is observed. A minimal Taker-side guard is acceptable only if required and documented as a risk-correctness exception.

Final clean-checkout command:

`./run.sh --sim --strategy`

Grading builds for `linux/amd64`.

Source must be built inside Dockerfiles. No host-only runtime dependency is permitted.

## 13. Grading-Relevant Qualities

Evaluate:

- profitability
- risk
- trading correctness
- code quality
- design quality
- reliability
- AI-assisted engineering process

The private grading market is not the supplied sample market.

Do not depend on simulator implementation details not guaranteed by task/protocol.

## 14. Deferred / Out of Scope

Unless evidence shows a clear need:

- multi-contract market making
- cross-expiry arbitrage
- ML prediction
- complex order-book forecasting
- dynamic quote sizing
- GUI
- database
- dashboard
- enterprise framework layers
- general-purpose trading platform abstractions
- cosmetic Taker refactoring
