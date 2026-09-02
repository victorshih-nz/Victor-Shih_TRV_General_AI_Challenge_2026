## Job 1.3 investigation — JetStream own-sender lifecycle replay

### Purpose

Determine whether sender-specific Quoter lifecycle history can be replayed from
JetStream quickly and deterministically enough to support UNKNOWN reconciliation.

Probe source:

```text
candidate/probes/JetStreamReplayProbe.java
```

The probe is read-only. It does not publish orders, create consumers, purge data,
or mutate production state.

### EX_MD retention observed

The exchange market-data stream was:

```text
name=EX_MD
subjects=["ex.md.>","ex.bbo.>"]
retention=limits
max_msgs=2000000
storage=memory
num_replicas=1
discard=old
```

At the first replay attempt:

```text
firstSeq=30256541
lastSeq=32256540
```

No `PFPROBE1` history remained. This was consistent with the stream-wide
2,000,000-message retention limit; the older probe history had already been
discarded.

At the fresh replay run:

```text
firstSeq=31353612
lastSeq=33353611
firstTime=2026-08-31T21:45:57.744565477Z
lastTime=2026-08-31T22:18:38.938364942Z
```

This represented roughly 32 minutes 41 seconds of retained EX_MD history at the
observed simulator traffic rate. This is an observation, not a guaranteed
production retention duration.

### Fresh lifecycle generation

`PartialFillOrderingProbe` was rerun to create new `PFPROBE1` history.

Case 1 produced:

```text
A PFPROBE1:A473C001 S 10 537
E TAKER001:63501890 PFPROBE1:A473C001 10 537 1128516 B
C PFPROBE1:A473C001
```

Case 2 stopped intentionally because BBO was `536 / 537` with tick size `1`, so
there was no safe inside-spread probe price. The safety guard prevented an
uncontrolled experiment. Case 1 was sufficient to create fresh replayable
lifecycle data.

### Replay result

Exact subject:

```text
ex.md.AAH6.PFPROBE1
```

Matching JetStream stream:

```text
EX_MD
```

Bounded retained PFPROBE1 window:

```text
snapshotFirstSeq=33233517
snapshotLastSeq=33347852
```

The replay used JetStream stream sequence plus exact-subject filtering. Although
the global stream sequence span exceeded 114,000 messages, only the 37 matching
PFPROBE1 messages were returned.

Measured results:

```text
pass1Count=37
pass2Count=37

pass1ElapsedMs=105.4834
pass2ElapsedMs=57.5374

pass1MaxLookupMs=8.0341
pass2MaxLookupMs=6.3136

sameSequenceAndPayload=true
RESULT=REPLAYABLE
```

The two bounded passes returned identical sequence and payload data.

### Engineering conclusions

1. `EX_MD` retains sender-specific lifecycle A/E/T/C data and supports exact
   subject-filtered replay.
2. JetStream stream sequence is suitable as a bounded replay cursor.
3. Each reconciliation attempt will own an independent cursor/window:
   `floorSeq`, `highWaterSeq`, and current replay cursor. Do not use one mutable
   global reconciliation cursor.
4. A reconciliation attempt should snapshot a high-water sequence and replay
   only the bounded sender-specific window required for that attempt.
5. Replay may overlap live lifecycle delivery. Existing Quoter E/T dedup and
   idempotent/late A/C lifecycle handling make overlap acceptable.
6. Observed replay cost for 37 lifecycle events was approximately 58–105 ms.
   Therefore replay escalation does not need to wait several seconds merely
   because replay is expensive.
7. Escalation times are maximum action deadlines, not minimum waiting periods.
   Exact Cancel or replay may start earlier when evidence justifies it.
8. Tentative V1 timing for later reconciliation-state-machine design:
   request unresolved at ~1 s -> UNKNOWN; cancel escalation by ~1 s UNKNOWN
   age; replay escalation by ~2 s UNKNOWN age; recovery check by ~4 s UNKNOWN
   age. Transport-outage timing semantics remain intentionally deferred until
   the reconciliation state machine is designed.
9. Time alone never changes UNKNOWN to EMPTY.
10. Quoter execution dedup protects one reconciliation/order epoch from repeated
    E/T affecting `filledQty`. A whole-set `executionDedup.clear()` is allowed
    only after the entire reconciliation epoch has completed safely,
    pre-reconciliation orders are authoritatively resolved, routing is
    serialized, and before new exposure is enabled.

## Job 2.1A — Authoritative Desk Position Accounting Hardening

### Decisions

- Hedger is authoritative for combined desk position.
- Exact sender-specific execution subscriptions only; no wildcard + exact combination.
- T means tracked sender owns incoming/aggressor order.
- E means tracked sender owns resting order.
- Buy positive / Sell negative.
- Both public order IDs must be valid `<sender>:<orderId>`.
- T incoming sender must equal tracked sender.
- E resting sender must equal tracked sender.
- Valid A/C are non-position events and are ignored.
- Malformed/unknown/inconsistent authoritative evidence fails closed.
- Dedup key includes trackedSender and eventType plus all execution identity fields.
- Dedup capacity exhaustion loses accounting trust; do not silently evict.
- Once accounting trust is lost, Job 2.1 does not automatically recover SAFE.
- Unknown position must never be represented as zero.
- Fresh SAFE only permits new Taker exposure.
- Hedger readiness must precede Taker/Quoter trading.

### Controlled Findings

- Running Python tests from repo root caused `ModuleNotFoundError`; tests must run with candidate/ as import root or explicit project venv interpreter.
- Copilot shell did not inherit the activated venv; explicit `.probe-venv/Scripts/python.exe` was required.
- An E ownership test initially used the wrong wire-field interpretation; E ownership is based on the restingOrderId field.
- Parser exceptions initially did not automatically mark accounting trust lost.
- Initial self-trade fixtures did not represent the same physical match across sender-specific events.
- Missing public sender prefixes could initially be inferred instead of rejected; changed to fail closed.
- Invalid UTF-8 needed conversion to AccountingUncertainty.
- Malformed A/C needed validation rather than unconditional ignore.

### Test Evidence

- Focused Job 2.1A accounting tests: 20 tests, PASS.
- Full candidate Python suite: 28 tests, PASS.
- `python -m compileall hedger tests`: PASS.
- `git diff --check`: PASS.
- Generated `__pycache__` directories removed before final status.
- No Job 2.1B implementation started.

### Deferred / Future Work

- Full JetStream execution reconciliation/catch-up is intentionally deferred.
- Job 2.1 trust loss remains UNKNOWN for the current accounting epoch.
- Hedge order execution, F sizing, Controlled/Emergency reduction logic, and hedge TPS belong to Job 2.2.

## Job 2.1B — Hedger Runtime + Desk Risk Publisher

### Decisions

- Hedger runtime reads `NATS_URL`, `TAKER_FEED`, `TAKER_SENDER`, `SENDER`,
  `HEDGER_SENDER`, `DESK_SOFT_POS`, and `DESK_HARD_POS`.
- `TAKER_FEED` is validated as 4 characters; all sender IDs as 8 characters;
  Taker, Quoter, and Hedger senders must be distinct.
- Desk limits default to soft `6` and hard `15`, with `0 < soft < hard`.
- `EX_META` is loaded from JetStream KV bucket `EX_META` before readiness.
- Metadata requires a positive `ticksize`; optional `ref_price` and non-negative
  `band` are parsed and retained for later hedge execution work.
- Production accounting subscribes only to the three exact sender-specific
  market-data subjects plus the configured BBO subject.
- BBO subscription presence is part of readiness; valid BBO data is not required
  for Job 2.1B because hedge pricing/sizing belongs to Job 2.2.
- Hedger flushes subscription setup before establishing the fresh-session
  authoritative zero position and publishing the first non-UNKNOWN desk risk.
- Risk payload is:
  `<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <state> <direction>`.
- Sequence increments on every publication, including heartbeat.
- Heartbeat target is approximately 200 ms.
- Accounting uncertainty publishes `UNKNOWN X` while preserving the last-known
  net position.
- A post-readiness NATS disconnect permanently loses accounting trust for the
  current epoch. Reconnect may resume risk publication, but cannot recover SAFE.
- Job 2.1B sends no orders and performs no hedge execution.

### Controlled Findings

- Existing protocol probe confirmed the Python NATS access pattern:
  `nc.jetstream()`, `key_value("EX_META")`, exact `nc.subscribe(...)`, and
  `nc.flush()`.
- Quoter metadata implementation independently confirmed the expected `EX_META`
  lookup by feed and required `ticksize` validation.
- Hedger-only live validation required a clean environment with only
  `nats`, `exchange`, and `sim`; old `strategy` and placeholder `hedger`
  containers were stopped and removed to avoid ambiguous publishers.
- Full desk testing was intentionally not run because the legacy Taker SAFE gate
  is not implemented until Job 2.1C.

### Test Evidence

- Focused Job 2.1B runtime tests: 23 tests, PASS.
- Full candidate Python suite: 51 tests, PASS.
- `python -m compileall hedger tests`: PASS.
- `git diff --check`: PASS.
- Generated `__pycache__` directories removed before final status.
- Hedger-only real NATS/Exchange/Sim smoke:
  - first desk-risk sequence observed `1..8`
  - net position `0`
  - limits `6 / 15`
  - state `SAFE`
  - direction `X`
  - observed heartbeat interval approximately 200 ms
- Controlled NATS restart after readiness:
  - post-reconnect sequence observed `239..243`
  - sequence did not reset
  - net position remained last-known `0`
  - state remained `UNKNOWN`
  - direction remained `X`
  - no automatic SAFE recovery occurred

### Deferred / Future Work

- Job 2.1C implements the minimal legacy Taker fresh-SAFE gate.
- Job 2.1D provides the real Hedger Dockerfile and full fresh-start integration.
- Hedge F-order execution, BBO-based sizing, Controlled/Emergency reduction,
  TPS limiting, and repeated emergency hedging belong to Job 2.2.
- JetStream desk-accounting replay/reconciliation remains deferred; Job 2.1
  trust loss stays UNKNOWN for the current accounting epoch.

## Job 2.1C — Minimal Taker Fresh-SAFE Gate

### Decisions

- Taker starts fail-closed and may create new exposure only while:
  - NATS transport is trusted,
  - the latest `desk.risk.<FEED>` message is valid,
  - risk state is `SAFE`,
  - and the risk message is fresh (`< 1000 ms` old).
- `UNKNOWN`, `CONTROLLED`, `EMERGENCY`, stale risk, malformed risk, or transport
  disconnect blocks new Taker exposure.
- Duplicate or older risk sequence numbers are ignored and do not refresh
  freshness.
- After risk becomes stale, the old sequence epoch is discarded so a restarted
  Hedger can establish a new fresh epoch.
- Taker installs the desk-risk subscription before BBO, flushes both
  subscriptions, and only then marks transport trusted.
- Taker checks the gate before signal-to-order conversion and again immediately
  before order dispatch.
- Existing Taker order/fill mechanics remain unchanged. Legacy regression tests
  use an explicit test-only open gate; production code is never given a bypass.
- Job 2.1C intentionally remains a minimal startup/exposure gate. It does not
  attempt causal acknowledgement of every Taker execution by Hedger accounting.

### Architecture Review Interaction / Newly Discovered Risk

The live test produced a useful design review rather than a simple
implementation-and-copy workflow.

1. The first full Python run exposed three legacy regression failures. The new
   production gate was correctly blocking tests that directly called
   `Taker.take()` without any Hedger risk. The implementation was not weakened.
   Instead, the legacy tests were changed to inject a test-only
   `AlwaysOpenRiskGate`, preserving their original purpose: sender-specific order
   routing, fill quantity, and buy/sell sign behaviour.

2. The first live startup test showed only Hedger risk messages and no Taker
   orders even after SAFE. Rather than changing the gate, runtime infrastructure
   was checked. A direct `ex.bbo.AAH6` probe timed out. This showed that the
   NATS restart used for the earlier Hedger reconnect test had left
   Exchange/Simulator without a working BBO flow. `exchange` and `sim` were
   restarted; five live AAH6 BBO updates were then observed. No production code
   change was made for this infrastructure issue.

3. The repeated live startup test then passed the intended Job 2.1C invariant:
   no Taker order was seen before the first SAFE risk, and orders began only
   after SAFE. However, it also exposed a new race: after the first SAFE message,
   Taker submitted five F orders within a few milliseconds, before Hedger could
   incorporate the resulting fills into a changed risk state. Taker finished the
   run at position `+9`.

4. Reviewer initially proposed a small hardening rule: allow at most one order
   per Hedger risk sequence. Victor challenged this design with two failure
   cases:
   - reject / zero-fill / cancelled orders could interact badly with a one-use
     authorization model;
   - more importantly, a newer risk sequence can be only a heartbeat based on
     the same old accounting state. Therefore `seq=11 > seq=10` does **not**
     prove that Hedger has incorporated the execution caused by the previous
     Taker order.

5. The one-sequence/one-order proposal was therefore rejected before being
   adopted. The important invariant is now explicit:

   **Hedger risk sequence is a publication/heartbeat sequence. It MUST NOT be
   treated as causal acknowledgement that a previous Taker execution has been
   accounted for.**

6. Victor then proposed a stronger design combining:
   - a bounded local pending/in-flight exposure mechanism, and
   - Hedger publication of an acknowledged Taker position/accounting point.

   Review found this direction substantially stronger, but a correct version
   must distinguish at least:
   - request pending,
   - reject,
   - accepted zero-fill,
   - positive immediate fill,
   - authoritative execution evidence,
   - Hedger accounting acknowledgement,
   - timeout/ambiguous outcome,
   - and transport loss.

   It may also require a separate Hedger accounting acknowledgement/version
   rather than overloading the existing eight-field `desk.risk` protocol.

7. Because that becomes a broader order-lifecycle and causal-accounting protocol
   change, the team decided not to expand Job 2.1C further. The risk is recorded
   and intentionally deferred rather than hidden or patched with a timing-based
   shortcut.

### Test Evidence

- Focused Taker fresh-SAFE gate tests: 14 tests, PASS.
- Legacy Taker regression tests: 8 tests, PASS after test-only gate injection.
- Full candidate Python suite: 65 tests, PASS.
- `python -m compileall taker hedger tests`: PASS.
- First live startup attempt:
  - Hedger SAFE risk flowed correctly.
  - no Taker orders were observed,
  - direct BBO probe timed out,
  - root cause was runtime infrastructure rather than Taker gate logic.
- After restarting `exchange` and `sim`, live AAH6 BBO updates were restored.
- Final controlled startup test:
  - first SAFE observed at `12.387 s`,
  - first Taker order observed at `12.425 s`,
  - `orders_before_safe = 0`,
  - `orders_after_safe = 5`,
  - `RESULT=PASS`.
- Taker live run finished with `position=9`, `fills=5`. This result triggered the
  causal-exposure-pacing review above; it was not treated as evidence that the
  burst behaviour is fully solved.

### Deferred / Future Work — Causal Taker Exposure Pacing

The newly discovered burst race is deliberately deferred.

Potential future direction:

```text
fresh SAFE
+ trusted transport
+ no ambiguous request
+ bounded local pending exposure
+ Hedger has causally acknowledged the Taker accounting point
-> permit next exposure
```

A future design may track:

- request-pending state,
- worst-case reserved exposure,
- Taker local expected position,
- Hedger acknowledged Taker position or accounting version,
- and explicit UNKNOWN handling for timeout / ambiguous lifecycle.

Reject and accepted-zero-fill paths should release quickly. Positive fills must
not release simply because a newer heartbeat sequence arrived. Timeout or
ambiguous order outcome must fail closed until authoritative evidence resolves
the uncertainty.

Do **not** implement this future mechanism using `desk.risk` heartbeat sequence
alone.

### Job 2.1C Scope Decision

Job 2.1C is considered complete for its original scope: prevent Taker exposure
before fresh Hedger SAFE readiness and fail closed on stale/unsafe risk.

The newly discovered causal-pacing problem is a controlled, documented deferred
issue. It should be revisited after Job 2.1 integration, or earlier only if new
runtime evidence shows it must block delivery.


## Job 2.2D — Real-Exchange Integration Findings

### AI review / debugging episode: Emergency hedge did not dispatch

**Scenario**

A controlled real-Exchange test was used instead of publishing synthetic T/E
events. A real TESTMK01 resting ask was crossed by a real PYTKR001 F order,
creating an authoritative +15 desk position.

Observed:

- Exchange emitted real T for PYTKR001.
- Hedger accounting moved desk position to +15.
- Hedger published `EMERGENCY S`.
- A real resting bid with executable depth was then added.
- Hedger initially sent no F order despite executable liquidity.

**Investigation**

The first diagnostic confirmed that the desk remained `EMERGENCY S`, but the
monitor had subscribed after the initial BBO update, so this was not enough to
prove a Hedger BBO problem.

A second live diagnostic subscribed first and then changed the real top of book.

Observed real Exchange BBO:

`AAH6 600 1 - 0`

The monitor received the fresh executable BBO, but Hedger still sent no order.

Reviewing the production parser and replaying the exact observed Exchange
payload exposed the root cause:

- Hedger accepted empty BBO sides only as `- -`.
- The actual Exchange represents an empty side as `- 0`.
- `_on_bbo()` treated the legitimate one-sided BBO as invalid and silently
  cleared `latest_bbo`.
- `build_hedge_plan()` itself already supported one-sided executable liquidity,
  so this was a parser/planner contract mismatch.

**Fix**

The Hedger BBO parser was changed to accept the actual Exchange empty-side
representation `- 0` while continuing to reject inconsistent malformed pairs
such as `- 1` or `<price> 0`.

Regression tests were added before rebuilding the Docker runtime.

**Real-Exchange verification**

The same controlled Emergency scenario was repeated without synthetic
executions.

Observed:

- Real Taker Buy F 15 -> desk `+15 EMERGENCY S`.
- Fresh BBO `599 x 2 / empty ask`.
- Hedger sent Sell F qty 2, respecting BBO depth.
- Exchange emitted authoritative Hedger T qty 2.
- Desk moved `15 -> 13`.
- Successive fresh BBOs caused staged F reductions:
  `13 -> 10 -> 9 -> 8 -> 7 -> 5`.
- Hedger stopped at `desk=5 SAFE`; it did not flatten unnecessarily.
- All Hedger orders were F.
- Hard-zone exposure duration observed: ~22.6 ms.
- Emergency-to-SAFE duration observed: ~205.3 ms.

Result: **PASS**

**Engineering takeaway**

Unit tests had not represented the actual Exchange one-sided BBO wire format.
The controlled real-Exchange scenario exposed an integration defect that the
logic tests and the earlier high-activity full simulation had masked. Real
Exchange T/E remains the authority for integration evidence; synthetic market
executions are not used to claim trading correctness.


### Job 2.2D — Taker Causal Exposure Pacing

**Real-Exchange failure discovery**

A high-resolution controlled Exchange scenario was used to determine whether
the Taker could create another exposure before the previous order had been
authoritatively reflected in desk risk.

Initial result:

- No second Taker order was sent before the previous authoritative T.
- However, one Taker order was dispatched after the previous real T but before
  Hedger had published the desk position containing that execution.
- The observed race was approximately 122 microseconds.
- Result: `FAIL-CAUSAL`.

This demonstrated that serialising `nc.request()` with `send_lock` protects
request concurrency but does not provide causal exposure serialization.

**Design review**

A first proposal considered waiting for Own T plus a newer risk sequence.

This was rejected as insufficient because:

- `desk.risk` sequence is a publication sequence, not an execution ACK.
- A delayed or heartbeat risk publication could have a newer sequence while not
  proving that a particular Taker execution had been accounted.
- `seq + 2` was therefore explicitly rejected as an acknowledgement heuristic.
- Aggregate desk-position direction was also not treated as definitive proof,
  because simultaneous Quoter/Hedger executions can offset the Taker fill.

Partial-fill handling was retained:

- Own authoritative T quantities are accumulated by order/match.
- The barrier does not resolve on the first partial execution.
- Completion is based on total authoritative T quantity matching the
  Exchange-confirmed final fill quantity.

Hedger already performs event-driven risk publication when an authoritative
T/E changes position, so no additional heartbeat mechanism was required.

**Causal accounting acknowledgement**

A small internal coordination path was added:

`desk.accounted.<FEED>.<TAKER_SENDER>`

Hedger publishes an acknowledgement only after:

1. the matching Taker T has passed authoritative accounting;
2. desk position has been updated;
3. the corresponding desk-risk snapshot has been published and flushed.

ACK payload:

`<order_id> <match_id> <qty> <risk_seq>`

Risk sequence remains ordering/freshness evidence only. The execution identity
is the order/match pair.

Taker now permits the causal exposure barrier to resolve only after:

- Exchange reply establishes the expected final fill quantity;
- cumulative Own T equals that quantity;
- cumulative matching Hedger accounting ACK equals that quantity;
- Taker has processed the desk-risk sequence associated with the ACK.

The normal DeskRiskGate remains the final exposure gate. A reconciled
CONTROLLED, EMERGENCY, or UNKNOWN state therefore still prevents new exposure.

Request timeout remains fail-closed because the Exchange outcome is unknown.

**Verification**

Focused deterministic tests:

- Hedger runtime: 76 PASS.
- Taker risk/causal tests: 23 PASS.
- Covered T-before-reply, partial T, duplicate T/ACK, delayed risk, newer
  heartbeat without ACK, ACK-before-risk, risk-before-T, and timeout.

Controlled real-Exchange rerun:

- Taker orders: 4
- Real Taker T: 4
- Real Hedger T: 3
- Orders before prior authoritative T: 0
- Orders before accounted desk risk: 0
- Final desk position: +5
- Final state: SAFE

Result: **PASS**

**Engineering takeaway**

Request serialization is not exposure serialization. For cross-subject HFT
coordination, a newer publication sequence alone is not sufficient causal
evidence. Explicit execution/accounting identity is required.

**Remaining verification**

The full unit-test suite must be rerun after updating the legacy
`AlwaysOpenRiskGate` test double with the new minimal `last_seq` read contract.


### Job 2.2D — NATS Disconnect / Reconnect Fail-Closed

A controlled Docker fault-injection test stopped only the NATS container while
Exchange, Hedger, and Taker processes remained running.

Pre-fault state was established using real Exchange execution:

- authoritative desk position: +3
- Hedger risk state: SAFE

Fault:

`docker compose stop nats`

Observed:

- probe and strategy clients detected the real NATS disconnect;
- no synthetic execution/risk events were injected.

Recovery:

`docker compose start nats`

After reconnect:

- Hedger preserved the last known diagnostic position `desk=3`;
- Hedger published `UNKNOWN`, not SAFE;
- repeated heartbeat publications remained UNKNOWN;
- no false SAFE publication was observed;
- a strong real BBO momentum sequence was generated after reconnect;
- Taker emitted zero new Add requests.

Observed result:

- `unknown_seen = True`
- `false_safe_seen = False`
- `diagnostic_position_preserved = True`
- `post_reconnect_taker_adds = 0`

Result: **PASS**

**Engineering takeaway**

Transport recovery is not accounting recovery. After a mid-session NATS gap,
the desk must not infer that its position is again trustworthy merely because
the connection has returned. The last known position may be retained for
diagnostics, but trading authority remains UNKNOWN.


### Job 2.2D — Quoter NATS Disconnect / Reconnect Fail-Closed

The NATS transport fault test was repeated with a live Quoter.

Before fault injection:

- Hedger published SAFE.
- A real wide Exchange BBO was established.
- Quoter created two real resting quotes.
- Both quotes were confirmed through real Quoter A lifecycle events.

Fault:

`docker compose stop nats`

Recovery:

`docker compose start nats`

Observed after reconnect:

- Quoter issued Cancel requests for both pre-fault active quotes.
- Real C lifecycle events confirmed both quotes were removed.
- Hedger published UNKNOWN continuously after reconnect.
- No false SAFE risk was observed.
- Quoter emitted zero new Add requests while risk remained UNKNOWN.
- No pre-fault quote remained active.

Observed result:

- pre-fault active quotes: 2
- post-reconnect UNKNOWN observed: yes
- post-reconnect false SAFE: no
- post-reconnect Quoter Adds: 0
- post-reconnect Quoter Cancels: 2
- old active quotes remaining: 0
- current active quotes: 0

Result: **PASS**

**Engineering takeaway**

Reconnect is treated as a recovery epoch, not permission to resume quoting.
Existing lifecycle uncertainty is reconciled first, old exposure is removed,
and UNKNOWN desk risk prevents new quote creation.

Combined with the Taker reconnect test, the NATS transport guardrail is now
verified for both exposure-creating seats.


### Job 2.2D — Malformed Execution Evidence Fail-Closed

An explicit fault-injection test published one intentionally malformed T event
to the tracked Taker execution subject.

This event was not treated as trading/integration evidence. Its only purpose was
to verify fail-closed behaviour when authoritative execution input is corrupt.

Pre-fault state:

- Hedger risk: SAFE
- desk position: 0
- Quoter had two real active resting quotes

Injected malformed tracked execution:

`T PYTKR001:BAD00001 TESTMK01:BAD00002 BROKEN 600 999999 B`

Observed:

- Hedger immediately transitioned to `UNKNOWN`.
- Diagnostic desk position remained unchanged at 0.
- No false SAFE publication occurred.
- Quoter issued two Cancel requests.
- Real C lifecycle events confirmed both old quotes were removed.
- Taker emitted zero new Adds after trust loss.
- Quoter emitted zero new Adds after trust loss.
- Hedger emitted zero F orders after trust loss.

Observed result:

- unknown_seen = True
- false_safe_seen = False
- diagnostic_position_preserved = True
- post_fault_taker_adds = 0
- post_fault_quoter_adds = 0
- post_fault_quoter_cancels = 2
- old_quotes_remaining = 0
- post_fault_hedger_orders = 0

Result: **PASS**

**Engineering takeaway**

Malformed tracked execution evidence is never guessed through or ignored while
trading continues. Accounting uncertainty immediately propagates to desk
UNKNOWN, removes resting Quoter exposure, and prevents all seats from creating
new exposure.


### Job 2.2D — Hedger Request Timeout + Late Authoritative T

A deterministic ordering probe tested the following failure sequence:

1. Desk authoritative position was +15.
2. Hedger dispatched a Sell F for quantity 10.
3. The request/reply path timed out.
4. The matching authoritative Hedger T arrived later.

Observed before fix:

- request timeout correctly stopped further hedging;
- accounting trust was immediately set to false;
- the in-flight Hedge order identity was cleared;
- later matching authoritative T could therefore no longer mutate position.

Observed values:

- pre-timeout desk: +15
- Hedger F side: S
- requested quantity: 10
- expected desk after late T: +5
- actual desk after late T: +15
- Hedger position after late T: 0
- late authoritative T accounted: false

Result: **FAIL-LATE-T-DROPPED**

**Root cause**

The runtime used the same trust state for two different concerns:

1. permission to continue trading after an uncertain order outcome;
2. permission to accept later authoritative sender-specific execution evidence.

A request timeout makes the order outcome uncertain and must stop further
trading, but it does not make a later exact T/E non-authoritative.

Clearing the in-flight identity also removed the information required to
validate a late execution against the original F order.

**Design decision**

Separate hedge outcome uncertainty from execution-accounting integrity.

After request/reply or fill-confirmation uncertainty:

- desk trading authority becomes UNKNOWN;
- no retry or new F is permitted;
- BBO execution eligibility is invalidated;
- the unresolved Hedger order identity is retained;
- authoritative matching T/E may still update diagnostic desk position;
- risk remains UNKNOWN even if the late execution fully reconciles the order.

Malformed or inconsistent authoritative T/E remains a true accounting-trust
failure and continues to fail closed.


### Job 2.2D — Hedger Crash / Restart False-SAFE

A controlled real-Exchange scenario tested Hedger process restart while the
desk already had authoritative exposure.

Pre-restart evidence:

- one real Taker T established desk position +8;
- Hedger published `desk=8 CONTROLLED S`.

Fault injection:

`docker compose restart hedger`

NATS and Exchange remained running, so the authoritative execution history
remained in the same trading session.

Observed after the new Hedger process started:

- risk sequence restarted at 1;
- desk position restarted at 0;
- Hedger repeatedly published SAFE;
- no authoritative position recovery occurred.

Observed post-restart risk:

- seq=1 desk=0 SAFE
- seq=2 desk=0 SAFE
- seq=3 desk=0 SAFE
- seq=4 desk=0 SAFE

Result: **FAIL-FALSE-SAFE**

**Root cause**

Hedger desk accounting is currently process-local memory.

Startup installs sender-specific execution subscriptions and then immediately
establishes readiness using the empty in-memory position. It does not rebuild
the current session position from retained authoritative execution history.

Therefore a process restart inside a live Exchange/NATS session is
indistinguishable from a truly fresh zero-position start.

**Correctness consequence**

A live desk exposure can be forgotten and incorrectly reopened as SAFE after
Hedger restart.

This is not acceptable as a trading-authority state.


### Engineering Review — Hedger Recovery Across Uncertain Runtime States

During Job 2.2D guardrail testing, two related accounting/recovery defects were
found through fault injection rather than from the original happy-path tests.

#### 1. Request timeout followed by late authoritative execution

The first probe showed that a Hedger F request timeout caused accounting trust
to be disabled immediately. When the exact authoritative T arrived later, the
real position change was therefore discarded.

This exposed an important distinction:

- loss of trading authority does not necessarily mean loss of authoritative
  accounting evidence;
- a request timeout makes the order outcome uncertain, but a later exact T/E
  remains authoritative.

After discussion, the runtime state was separated into:

- accounting trust; and
- hedge outcome uncertainty.

The selected behaviour was deliberately conservative:

- timeout or ambiguous reply -> UNKNOWN;
- no retry or new hedge;
- unresolved client order identity retained;
- matching late T/E may still update diagnostic position;
- even successful late reconciliation does not reopen trading in that process
  session.

A deterministic regression confirmed:

`desk +15 -> timeout -> late Sell T 10 -> desk +5`

while risk remained UNKNOWN and no second hedge was allowed.

#### 2. Hedger process restart forgot live desk exposure

A later real-Exchange scenario exposed a wider startup issue.

Before restart:

- a real Taker execution established desk position +8;
- Hedger correctly published `desk=8 CONTROLLED S`.

After restarting only the Hedger container, the new process initially rebuilt
its accounting from empty local memory and published `desk=0 SAFE`.

This was a real false-SAFE defect.

We reviewed whether to solve this with a simple permanent UNKNOWN restart flag
or with authoritative recovery. A restart flag was rejected because the new
process cannot reliably distinguish a genuinely fresh grading session from a
mid-session process restart using local memory alone.

The selected minimal solution reused the existing retained `EX_MD` JetStream
history:

1. install the exact live execution subscriptions first;
2. keep startup trading authority closed;
3. snapshot and replay retained Taker, Quoter and Hedger execution subjects;
4. route replay through the existing desk accounting and deduplication logic;
5. allow replay/live overlap to be absorbed by the existing execution dedup;
6. publish the first desk risk only after recovery completes successfully.

The implementation was intentionally kept inside the existing Hedger runtime
rather than introducing persistence services or a general recovery framework.

Real-Exchange verification after the fix:

- pre-restart authoritative desk = +8;
- Hedger container restarted while NATS and Exchange remained live;
- new Hedger process recovered +8 from retained authoritative execution;
- its first risk message was `desk=8 CONTROLLED S`;
- no false SAFE occurred.

Result: **PASS-RECOVERED**

This review reinforced the project rule that trading authority should fail
closed when state is uncertain, while authoritative execution evidence should
be preserved whenever it can still be validated.

