# TRV General AI Challenge 2026 — Test Plan

## 1. Purpose

Testing must prove:

- protocol interpretation,
- exact signed position accounting,
- order lifecycle safety,
- desk-position coordination,
- risk reduction speed,
- profitable liquidity provision,
- Docker reproducibility.

Levels:

1. Unit
2. Controlled exchange probes
3. Docker/integration
4. Trading evaluation

## 2. Level 1 — Unit Tests

### Quoter Pricing

Test:

- midpoint
- bounded microprice
- invalid BBO
- EWMA movement
- adaptive band
- band bounds
- Cheap < Fair < Expensive
- valuation signal bounds
- tick adjustment
- tick rounding
- price band
- Minimum Edge

Assertions:

- Normal valuation never crosses Minimum Edge.
- Controlled risk reduction can only use its configured relaxation.
- Hard/Emergency risk reduction can override Minimum Edge.

### Quoter Inventory / Risk

Test:

- buy => positive
- sell => negative
- multiple E accumulation
- small/medium/large skew progression
- long inventory lowers risk-increasing bid behaviour
- short inventory opposite
- Soft zone stronger skew
- Hard long => bid cancellation/suppression
- Hard short => ask cancellation/suppression
- temporary one-sided quoting
- exact `desk.risk` payload parsing
- older risk sequence ignored
- risk heartbeat stale (>1000 ms) => UNKNOWN + cancel-all + no new quotes
- `desk.risk` long => bid suppression
- `desk.risk` short => ask suppression
- EMERGENCY risk-increasing cancel dispatch target <=100 ms in local integration

### Startup / Staleness

Test:

No quote when:

- metadata absent
- metadata invalid
- BBO absent
- BBO one-sided
- Hedger state UNKNOWN
- BBO age > `MARKET_DATA_STALE_MS`

When BBO becomes stale:

- resting quotes are cancelled
- no replacement is submitted
- quoting resumes only after valid fresh state

### Order Lifecycle

Test:

- unique IDs
- sender correctness
- add/cancel format
- cancel-before-risk-increase
- safe replace state
- rejects
- timeouts
- no duplicate logical active quote

### Hedger Accounting

Test execution events where tracked sender is:

- incoming buyer
- incoming seller
- resting buyer
- resting seller

Test:

- Taker signed position
- Quoter signed position
- Hedger signed position
- combined desk position
- full v1 execution dedup key
- duplicate exact event does not change position twice
- same match involving two tracked desk senders is accounted once per tracked sender, not globally suppressed

### Hedger Zones / Orders

Test:

- UNKNOWN startup
- SAFE => no hedge
- CONTROLLED long => sell
- CONTROLLED short => buy
- target reduction toward soft limit
- EMERGENCY long => sell F
- EMERGENCY short => buy F
- bounded clip
- clip limited by remaining excess
- clip never exceeds current opposite BBO volume
- if opposite BBO volume < required reduction, hedge only executable amount and re-evaluate
- Emergency never uses resting L merely to wait for remaining reduction
- no position change on rejected/unfilled atomic F
- retry requires fresh market state
- TPS guard

## 3. Level 2 — Controlled Exchange Probes

Required:

- `ex.req.<SENDER>`
- sender mismatch
- add reply
- cancel reply
- malformed reject
- reused ID reject
- buy fill
- sell fill
- execution field meanings
- incoming/resting side inference
- multiple E
- F atomic full-or-reject
- cancel-many if used
- STP if used
- EX_META
- safe TPS behaviour if practical

### Legacy Taker Fast Verification

Mandatory startup conclusion:

- record in `NOTES.md` whether the supplied Taker order-entry subject must be corrected,
- record whether a Taker startup gate was required and which minimal mechanism was selected.

Mandatory controlled sell:

1. force/observe one sell fill,
2. compare expected signed change with Taker report,
3. if Taker increases position on sell, record confirmed defect,
4. enter Job 0.3 immediately.

## 4. Level 3 — Docker / Integration

### Baseline

Already established:

```bash
bash ./run.sh --sim
```

### Startup Gate

Verify:

- Hedger is not marked ready before NATS connection, metadata validation, all three execution subscriptions, BBO subscription, and subscription flush/confirmation are complete.
- No Taker order may be submitted, and therefore no Taker fill may occur, before Hedger has established authoritative accounting readiness and published the first non-UNKNOWN desk-risk state.
- No Quoter order may be submitted, and therefore no Quoter fill may occur, before the same readiness point.
- Full-desk startup sequence must demonstrate:

```text
before Hedger ready:
  Taker orders  = 0
  Quoter orders = 0
  Taker fills   = 0
  Quoter fills  = 0

Hedger publishes first SAFE/non-UNKNOWN state

after ready:
  trading may begin
```

- A timeout alone never changes UNKNOWN to SAFE.
- Quoter remains fail-closed until the first valid desk-risk message.
- Hedger heartbeat loss forces Quoter back to UNKNOWN.
- If the preferred container/startup-layer Taker gate is used, test that `taker.py` is not order-active before readiness.

### Quoter

Verify:

- Java builds from source
- metadata readiness
- Hedger readiness gating
- BBO
- quotes
- movement updates
- own execution inventory
- risk-state side suppression
- stale cancellation
- no quote accumulation

### Full Desk

```bash
./run.sh --sim --strategy
```

Verify:

- all containers
- distinct senders
- same feed
- Hedger exact execution subscriptions
- `desk.risk.<FEED>` flow
- Quoter risk suppression
- Hedger F execution
- no host dependency

## 5. Level 4 — Trading Evaluation

### Profitability

Record:

- Quoter fills
- buy/sell balance
- realised/marked PnL where reliable
- approximate spread capture
- hedge cost
- total desk PnL where reliable

### Quoter Risk

Record:

- Quoter position
- peak absolute position
- time in Normal
- time in Soft
- time in Hard
- Hard transitions

### Desk Risk

Record:

- desk position
- peak absolute desk position
- time above soft limit
- time above hard limit
- controlled hedge count
- emergency hedge count
- hedge quantities

### Hard-Exposure Survival Time

For every event where:

```text
abs(deskPosition) >= deskHardLimit
```

record:

- breach start timestamp
- timestamp leaving Hard zone
- timestamp returning to or inside Soft zone
- duration in milliseconds

Primary operational target:

- when executable opposite-side liquidity exists, leave Hard zone in less than 1 second,
- continue toward/inside Soft as quickly as liquidity and TPS constraints permit.

Also report:

- maximum Hard-zone duration
- p95 Hard-zone duration where enough samples exist
- maximum time to return inside Soft

A breach that cannot be reduced because executable liquidity is absent must be logged separately rather than silently counted as normal performance.

### Reliability

Record:

- rejects
- timeouts
- malformed events
- stale BBO incidents
- stale order incidents
- unexpected active-order accumulation
- execution dedup hits
- unexplained position mismatch
- risk-state/quoter coordination failures

## 6. Acceptance Principles

Positive PnL in one run is insufficient.

Acceptance also requires:

- correct signed accounting
- no double counting
- bounded inventory
- prompt dangerous-exposure reduction
- no persistent stale orders
- no unexplained reject storm
- no dependence on one simulator path

Emergency risk reduction may accept a small controlled loss.

Capital preservation dominates an individual trade.

## 7. Repeated-Run Tuning

Where practical:

- vary simulator seeds
- compare calm/fast periods
- compare different inventory states
- use consistent metrics

Do not change unrelated parameters simultaneously without explicit reason.

No added strategy complexity without measured benefit.

## 8. CI Gates

As applicable:

1. Java unit tests
2. Python Hedger tests
3. legacy/probe regression tests
4. build sanity
5. strategy Docker build
6. hedger Docker build
7. compose config

Full supplied-exchange integration may remain local if hosted CI cannot practically run the provided image.

## 9. Final Pre-Submission

- clean git status
- tests pass
- CI green
- Docker rebuild from source
- full stack starts
- no hard-coded grader-sensitive configuration
- `NOTES.md` current
- `TRANSCRIPT.txt` complete
- useful probes/tests included
- submission archive minimal and correct
