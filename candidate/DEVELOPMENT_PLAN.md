# TRV General AI Challenge 2026 — Development Plan

## 1. Delivery Strategy

Deliver quickly without trading shortcuts.

Jobs are deliberately meaningful rather than excessively granular.

Every Job includes:

- implementation/probe work,
- focused tests,
- relevant integration validation,
- Git diff review,
- CI before advancing.

No speculative feature work.

## 2. Roles

### Final Decision Maker
Victor

### Agent A — Developer
GitHub Copilot Coding Agent

### Agent B — Reviewer / Architect
ChatGPT + GitHub

### Automated Judge
GitHub Actions

### Local Integration
VS Code + WSL2 + Docker Desktop

## 3. Mandatory Job Workflow

Before a Job:

1. `git status`
2. confirm branch
3. confirm no unrelated changes
4. read the relevant requirement/design/test sections

During a Job:

1. implement only approved scope
2. keep trading logic explicit
3. add focused tests with implementation
4. avoid unnecessary abstraction

Before completion:

1. focused tests
2. broader relevant tests
3. Docker/integration where required
4. inspect order/execution/position consequences
5. `git diff --check`
6. update `NOTES.md`
7. preserve AI transcript
8. Agent B reviews actual diff
9. CI green

## 4. Batch 0 — Evidence and Baseline

### Job 0.1 — Protocol and Legacy Verification

Goal:

Resolve protocol uncertainty quickly and verify the high-confidence Taker defect hypotheses.

Required checks:

1. Verify order-entry subject `ex.req.<SENDER>`.
2. Verify add/reject reply structure.
3. Verify buy fill execution semantics.
4. **Verify sell fill causes negative signed position change.**
5. Verify execution fields and aggressor-side interpretation.
6. Verify multiple `E` accumulation.
7. Verify cancellation behaviour required by the planned design.
8. Verify F atomic full-or-reject behaviour relevant to Hedger.
9. Verify EX_META access.
10. Confirm observed `matchId` behaviour and validate the full v1 dedup key:
    `(trackedSender, eventTimestamp, matchId, incomingOrderId, restingOrderId, qty, price, aggressorSide)`.
11. Record a mandatory NOTES conclusion for the legacy Taker order-entry subject: unchanged or corrected, with evidence.
12. Verify whether the Taker can submit an order or receive a fill before Hedger readiness in the supplied full-desk startup path.
13. If it can, classify this as a desk-wide startup safety gap requiring a minimal gate.

Legacy Taker fast path:

- The sell-side sign issue is a high-confidence bug hypothesis.
- Do not spend time debating it abstractly.
- Prove it with one controlled sell execution.
- If confirmed, create Job 0.3 immediately.

Deliverables:

- minimal probe/helper tooling
- evidence in `NOTES.md`
- confirmed protocol facts
- explicit legacy defect result

Exit:

- execution side/sign is empirically confirmed
- position accounting rule is confirmed
- F semantics are confirmed
- Job 0.3 decision made

### Job 0.2 — Desk Accounting / Baseline Probe

Goal:

Prove the planned Hedger accounting path before Quoter implementation.

Scope:

- Subscribe to exact sender-specific execution feeds
- Verify the startup race explicitly: before Hedger readiness, Taker orders/fills must remain zero
- Select and document the minimal Taker startup-gate mechanism
- Confirm the Hedger can distinguish incoming vs resting side
- Confirm signed accumulation for three sender identities
- Confirm no duplicate accounting in controlled cases
- Confirm `deskPosition = taker + quoter + hedger`
- Confirm EX_META and BBO observation
- Establish baseline spread/movement/risk metrics
- Document startup ordering assumptions

Deliverables:

- lightweight accounting/probe code if useful
- baseline measurements
- confirmed Desk Position Protocol details

Exit:

- desk position accounting is proven enough to implement
- no custom fill-republication protocol is required

### Conditional Job 0.3 — Minimal Legacy Taker Correction

Created immediately if Job 0.1 confirms the sell-side sign defect or another material defect.

Scope:

- minimal confirmed fix only
- regression test
- rerun controlled probe
- rerun baseline

No unrelated cleanup.

## 5. Batch 1 — Java Quoter

### Job 1.1 — Complete Java Quoter

Scope:

- Java build
- NATS client
- environment config
- `TAKER_FEED`
- metadata loading
- BBO subscription
- own execution subscription
- own inventory
- `desk.risk.<FEED>` subscription and exact payload parsing
- risk sequence/staleness handling
- startup gating
- Fair Value
- bounded microprice
- EWMA movement
- adaptive valueBand
- valuation signal
- non-linear inventory skew
- Normal/Soft/Hard behaviour
- explicit one-sided Hard-risk behaviour
- Minimum Edge priority rules
- fixed bounded clip
- tick rounding
- price-band enforcement
- order IDs
- cancel/replace
- stale BBO fail-closed behaviour
- rejection/timeout handling
- Dockerfile
- focused tests

Exit:

- builds in Docker
- no quote before metadata + BBO + Hedger readiness
- two-sided normal quoting works
- risk-increasing side is cancelled in Hard state
- one-sided risk reduction works
- stale BBO cancels resting orders
- position is execution-driven
- tests pass

### Job 1.2 — Quoter Integration / Tuning

Scope:

- sample-market integration
- moving-market quote lifecycle
- inventory skew behaviour
- Minimum Edge priority
- desk-risk advisory response
- TPS behaviour
- stale threshold behaviour
- repeated runs
- measured parameter tuning only

Exit:

- bounded inventory
- no stale accumulation
- no material correctness errors
- unexplained rejects absent/low
- metrics recorded

## 6. Batch 2 — Python Hedger

### Job 2.1 — Authoritative Desk Position + Risk Publisher

Scope:

- subscribe exact execution subjects for all three seats
- connect/load metadata before readiness
- subscribe configured BBO
- flush/confirm all required subscriptions are active
- establish Hedger readiness/health only after the above steps
- publish first non-UNKNOWN desk-risk state only after readiness
- participate in Docker/startup gating so Taker and Quoter do not trade before Hedger accounting is ready
- execution side inference
- signed accounting
- execution dedup
- maintain per-seat positions
- net desk position
- UNKNOWN/SAFE/CONTROLLED/EMERGENCY states
- publish exact `desk.risk.<FEED>` payload with seq, position, limits, state and direction
- heartbeat at least every 250 ms
- unit tests

Exit:

- accounting correct in controlled tests
- Quoter can consume risk state
- no double count in tested scenarios
- startup UNKNOWN behaviour works

### Job 2.2 — Hedge Execution + Full Desk Integration

Scope:

- F hedge execution
- controlled-zone hedge toward soft limit
- emergency repeated F on fresh BBO
- strict F sizing no larger than current opposite BBO volume
- never use resting L merely to finish an Emergency reduction
- immediate re-evaluation after each F result
- bounded clips
- TPS protection
- risk-reducing Minimum Edge override
- `./run.sh --sim --strategy`
- verify Quoter suppression before/while hedge
- measure hard-exposure duration
- prevent hedge/quote oscillation

Exit:

- full stack clean-build starts
- large exposure leaves Hard zone promptly when executable liquidity exists
- target is toward/inside Soft, not exact zero
- Quoter does not materially absorb Hedger risk-reduction flow
- no unexplained position divergence

## 7. Batch 3 — Hardening

### Job 3.1 — Repeated Trading Evaluation

- repeated runs
- multiple seeds where practical
- compare profitability/risk
- tune bounded parameters
- investigate adverse selection
- no new complexity without measured benefit

### Job 3.2 — CI and Submission

- CI
- Java tests
- Python tests
- Docker builds
- compose validation
- clean checkout
- final full-stack run
- `NOTES.md`
- `TRANSCRIPT.txt`
- submission archive

## 8. Scope Control

New work enters only if required for:

1. challenge compliance
2. correctness
3. risk
4. measured reliability
5. measured profitability
6. grading/submission
