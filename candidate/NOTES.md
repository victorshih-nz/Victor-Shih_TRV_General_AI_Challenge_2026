# Job 0.1 protocol and legacy Taker evidence

## Controlled environment
- Exchange-only runtime: `cd candidate; ./run.sh`
- Message bus: NATS on `nats://127.0.0.1:4222`
- Probe script: `candidate/probes/protocol_probe.py`
- Empty book baseline used for all controlled probes.

## 1. Exact malformed-payload diagnosis

Question / assumption:
- Bare `ex.req` should not be accepted.
- Malformed add requests should be rejected with `N 100`.
- The protocol requires `ex.req.<SENDER>` and an 8-character sender / 8-character order id.

Command / probe used:
- `python -m py_compile probes/protocol_probe.py`
- `./.probe-venv/Scripts/python.exe probes/protocol_probe.py`

Observed reply:
- `REQUEST 'ex.req' 'PROBE001 A AAH6 BUY0001 B 5 600 F'`
- `REPLY 'EXCEPTION NoRespondersError: nats: no responders available for request'`
- `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY0001 B 5 600 F'`
- `REPLY 'EXCHANGE N 100 malformed request'`

Interpretation:
- Bare `ex.req` has no responder.
- `ex.req.<SENDER>` is the correct subject, but the payload itself is malformed because `BUY0001` is only 7 characters.
- The exact valid wire shape is `'<SENDER> A <FEED> <ORDER_ID_8> <B|S> <VOLUME> <PRICE> <L|M|F>'`.

Conclusion:
- Confirmed: `ex.req.<SENDER>` is required; `ex.req` is not a valid order-entry subject.
- Confirmed: 8-character senders and 8-character order ids are part of the valid wire format.

Affects DESIGN / later Job:
- Affects the legacy Taker subject bug and follow-up Job 0.3 fix.

## 2. Accepted / rejected reply structure

Observed replies:
- Accepted: `REPLY 'EXCHANGE Y 0'`
- Accepted fill: `REPLY 'EXCHANGE Y 5'`
- Rejected malformed input: `REPLY 'EXCHANGE N 100 malformed request'`

Interpretation:
- The exchange reply pattern matches the protocol: `EXCHANGE Y <n>` or `EXCHANGE N <code> <text>`.
- `Y` is for accepted order or accepted fill count; `N` is for reject.

Conclusion:
- Confirmed: accepted / rejected structure is exactly as specified.

## 3. Controlled BUY fill semantics

Question / assumption:
- A resting SELL order should fill a crossing BUY F order.
- The tracked order should behave as `incoming = aggressorSide` when the tracked order is incoming.

Command / probe used:
- `MAKER001 A AAH6 SELL0001 S 5 600 L`
- `PROBE001 A AAH6 BUY00001 B 5 600 F`

Observed events:
- `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 SELL0001 S 5 600 L'`
- `EVENT ex.md.AAH6.MAKER001 '1788099472528203079 A MAKER001:SELL0001 S 5 600'`
- `REPLY 'EXCHANGE Y 0'`
- `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY00001 B 5 600 F'`
- `EVENT ex.md.AAH6.PROBE001 '1788099472740337606 T PROBE001:BUY00001 MAKER001:SELL0001 5 600 1 B'`
- `EVENT ex.md.AAH6.MAKER001 '1788099472740337606 E PROBE001:BUY00001 MAKER001:SELL0001 5 600 1 B'`
- `REPLY 'EXCHANGE Y 5'`

Interpretation:
- The tracked incoming order is `PROBE001:BUY00001`.
- The tracked resting order is `MAKER001:SELL0001`.
- `aggressorSide` is `B`.
- Thus for the incoming tracked order, side = `aggressorSide` = `B`.

Conclusion:
- Confirmed: a BUY F fill is accepted and a real execution event is emitted.

## 4. Controlled SELL fill semantics and negative signed position

Question / assumption:
- Reverse the test: a resting BUY order should fill a crossing SELL F order.
- The tracked incoming SELL fill must contribute negative position.

Command / probe used:
- `MAKER001 A AAH6 BUY00002 B 5 610 L`
- `PROBE001 A AAH6 SELL0002 S 5 610 F`

Observed events:
- `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 BUY00002 B 5 610 L'`
- `REPLY 'EXCHANGE Y 0'`
- `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 SELL0002 S 5 610 F'`
- `EVENT ex.md.AAH6.PROBE001 '1788099473376261416 T PROBE001:SELL0002 MAKER001:BUY00002 5 610 2 S'`
- `EVENT ex.md.AAH6.MAKER001 '1788099473376261416 E PROBE001:SELL0002 MAKER001:BUY00002 5 610 2 S'`
- `REPLY 'EXCHANGE Y 5'`

Interpretation:
- Here the tracked incoming order is `PROBE001:SELL0002` and the aggressor side is `S`.
- For an incoming SELL, the executed position change must be negative: `position += -qty`.
- The `E` event is authoritative and proves the sell-side fill is a negative position change.

Conclusion:
- Confirmed: sell-side execution is a negative signed position change.

## 5. Incoming vs resting `E` interpretation

Observed real example:
- `EVENT ex.md.AAH6.MAKER001 '... E PROBE001:BUY00001 MAKER001:SELL0001 5 600 1 B'`
- `EVENT ex.md.AAH6.PROBE001 '... T PROBE001:SELL0002 MAKER001:BUY00002 5 610 2 S'`

Interpretation:
- In `'<ts> E <incoming> <resting> <qty> <price> <matchid> <aggressorSide>'`, the tracked order side is:
  - incoming tracked order: `aggressorSide`
  - resting tracked order: opposite(aggressorSide)

Examples:
- BUY incoming + aggressorSide `B` means incoming side is `B`.
- SELL incoming + aggressorSide `S` means incoming side is `S` and the resting side is `B`.

Conclusion:
- Confirmed: the rule matches the actual exchange data.

## 6. Multiple executions / accumulation

Question / assumption:
- One incoming order may generate more than one execution event.
- Position must accumulate all `E` records.

Command / probe used:
- `MAKER001 A AAH6 SELL0003 S 2 600 L`
- `MAKER001 A AAH6 SELL0004 S 3 600 L`
- `PROBE001 A AAH6 BUY00003 B 5 600 F`

Observed events:
- `EVENT ex.md.AAH6.PROBE001 '... T PROBE001:BUY00003 MAKER001:SELL0003 2 600 3 B'`
- `EVENT ex.md.AAH6.PROBE001 '... T PROBE001:BUY00003 MAKER001:SELL0004 3 600 4 B'`
- `REPLY 'EXCHANGE Y 5'`

Interpretation:
- One incoming order generated two `E` / `T` events.
- `2 + 3 = 5` and the incoming order quantity is fully accumulated across both executions.
- Position must add both fills; no execution may be dropped.

Conclusion:
- Confirmed: multiple `E` events must be accumulated; one order can produce several fills.

## 7. Cancel behaviour

Question / assumption:
- A resting `L` order can be cancelled and should emit a `C` event.

Command / probe used:
- `MAKER001 A AAH6 CXL00001 S 10 620 L`
- `MAKER001 C AAH6 CXL00001`

Observed:
- `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 CXL00001 S 10 620 L'`
- `EVENT ex.md.AAH6.MAKER001 '1788099475543472200 A MAKER001:CXL00001 S 10 620'`
- `REPLY 'EXCHANGE Y 0'`
- `REQUEST 'ex.req.MAKER001' 'MAKER001 C AAH6 CXL00001'`
- `EVENT ex.md.AAH6.MAKER001 '1788099475760825088 C MAKER001:CXL00001'`
- `REPLY 'EXCHANGE Y 1'`

Conclusion:
- Confirmed: valid cancel requests accept and produce `C` events.

## 8. F behaviour and actual exchange result

Question / assumption:
- The checked-in runtime must be compared directly to the documented `PROTOCOL.md` semantics and the stale changelog wording.
- The design baseline must record the live behavior rather than assume atomic `F` semantics.

Command / probe used:
- `MAKER001 A AAH6 SELL0005 S 3 600 L`
- `PROBE001 A AAH6 BUY00004 B 5 600 F`

Observed:
- `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 SELL0005 S 3 600 L'`
- `REPLY 'EXCHANGE Y 0'`
- `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY00004 B 5 600 F'`
- `EVENT ex.md.AAH6.PROBE001 '... T PROBE001:BUY00004 MAKER001:SELL0005 3 600 5 B'`
- `REPLY 'EXCHANGE Y 3'`

Interpretation:
- The exchange did not reject the 5-unit `F` order when only 3 units were available.
- It executed the 3 units immediately and canceled the remainder.
- This matches the Fill-and-Kill semantics in `PROTOCOL.md`: execute immediately available quantity and cancel the remainder.
- This runtime behavior contradicts `CHANGELOG.md v2.3`, which states that `F` is atomic full-or-reject.

Conclusion:
- CONFIRMED PARTIAL
- "The supplied running exchange matches the Fill-and-Kill semantics described in PROTOCOL.md: execute immediately available quantity and cancel the remainder. This runtime behaviour contradicts CHANGELOG.md v2.3, which states that F is atomic full-or-reject."
- Affects DESIGN / later Job:
  - design and tests must treat `F` as partial execution with immediate remainder cancellation, not atomic full-or-reject.

Final P0 F-order conclusion:
- B. CONFIRMED PARTIAL
- Raw evidence from the fresh empty-book rerun:
  - `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 SELL0001 S 3 600 L'`
  - `REPLY 'EXCHANGE Y 0'`
  - `EVENT ex.md.AAH6.MAKER001 '1788100471434451330 A MAKER001:SELL0001 S 3 600'`
  - `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY00001 B 5 600 F'`
  - `EVENT ex.md.AAH6.PROBE001 '1788100471854335567 A PROBE001:BUY00001 B 5 600'`
  - `EVENT ex.md.AAH6.PROBE001 '1788100471854335567 T PROBE001:BUY00001 MAKER001:SELL0001 3 600 1 B'`
  - `EVENT ex.md.AAH6.PROBE001 '1788100471854335567 C PROBE001:BUY00001'`
  - `EVENT ex.md.AAH6.MAKER001 '1788100471854335567 E PROBE001:BUY00001 MAKER001:SELL0001 3 600 1 B'`
  - `EVENT ex.md.AAH6.MAKER001 '1788100471854335567 C MAKER001:SELL0001'`
  - `REPLY 'EXCHANGE Y 3'`
- Requested F qty: 5
- Total E qty for PROBE001: 3
- Remaining qty rests: no remaining resting qty, the order was cancelled immediately after partial fill
- Resulting signed position: +3 for the incoming BUY side
- Control case with full liquidity:
  - `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 SELL0002 S 5 600 L'`
  - `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY00002 B 5 600 F'`
  - `EVENT ex.md.AAH6.PROBE001 '1788100498654795439 T PROBE001:BUY00002 MAKER001:SELL0002 5 600 1 B'`
  - `REPLY 'EXCHANGE Y 5'`

## Final sender-specific E vs T conclusion

Question / assumption:
- The observed raw trade stream appears to show the incoming aggressor owner receiving `T` while the resting owner receives `E`.
- We must verify whether a desk subscriber that listens only for `E` would miss aggressor fills.

Command / probe used:
- Fresh empty-book runtime, two 8-character senders: `PROBE001` and `MAKER001`
- Subscriptions: `ex.md.AAH6.PROBE001`, `ex.md.AAH6.MAKER001`, `ex.md.AAH6.*`

Raw evidence from the fresh empty-book run:
- `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 SELL0001 S 5 600 L'`
- `REPLY 'EXCHANGE Y 0'`
- `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY00001 B 5 600 F'`
- `EVENT ex.md.AAH6.PROBE001 '1788100982674942248 A PROBE001:BUY00001 B 5 600'`
- `EVENT ex.md.AAH6.PROBE001 '1788100982674942248 T PROBE001:BUY00001 MAKER001:SELL0001 5 600 1 B'`
- `EVENT ex.md.AAH6.PROBE001 '1788100982674942248 C PROBE001:BUY00001'`
- `EVENT ex.md.AAH6.MAKER001 '1788100982674942248 E PROBE001:BUY00001 MAKER001:SELL0001 5 600 1 B'`
- `EVENT ex.md.AAH6.MAKER001 '1788100982674942248 C MAKER001:SELL0001'`
- `REPLY 'EXCHANGE Y 5'`
- `REQUEST 'ex.req.PROBE001' 'PROBE001 A AAH6 BUY00002 B 5 610 L'`
- `REPLY 'EXCHANGE Y 0'`
- `REQUEST 'ex.req.MAKER001' 'MAKER001 A AAH6 SELL0002 S 5 610 F'`
- `EVENT ex.md.AAH6.MAKER001 '1788100983795197873 A MAKER001:SELL0002 S 5 610'`
- `EVENT ex.md.AAH6.MAKER001 '1788100983795197873 T MAKER001:SELL0002 PROBE001:BUY00002 5 610 2 S'`
- `EVENT ex.md.AAH6.MAKER001 '1788100983795197873 C MAKER001:SELL0002'`
- `EVENT ex.md.AAH6.PROBE001 '1788100983795197873 E MAKER001:SELL0002 PROBE001:BUY00002 5 610 2 S'`
- `EVENT ex.md.AAH6.PROBE001 '1788100983795197873 C PROBE001:BUY00002'`
- `REPLY 'EXCHANGE Y 5'`

Interpretation:
- For the incoming/aggressor owner, the sender-specific stream receives `T`.
- For the resting owner, the sender-specific stream receives `E`.
- The wildcard stream sees both event types for the same underlying trade, but the message is not duplicated by the exchange itself when deduplicated by identical message body; the apparent duplication here is due to both specific and wildcard subscriptions being active in the same process.
- Therefore, a desk-position subscriber that listens only for `E` would miss aggressor fills on the incoming owner.

Explicit conclusion:
- E_AND_T_REQUIRED

Docker runtime provenance:
- `docker image inspect sim-exchange:candidate`
- `sha256:dc7cbbe2012c6a916d47232ed6bec9c2d27d942d6c4d76f9ad9ad622aea60d61 | 2026-07-06T12:59:39.217196616+09:00 | amd64 | linux`

Affects DESIGN / later Job:
- Important: do not assume a single execution event type for tracking; account for both incoming `T` and resting `E` in any desk-side position logic.

## 9. EX_META confirmation

Command / probe used:
- `EX_META` KV read via the probe.

Observed values for `AAH6`:
- `ticksize=1 ref_price=600 band=5000 min_volume=1 max_volume=10000000 position_limit=1000000000 max_tps=0 last_traded_price=600`

Conclusion:
- Confirmed: EX_META is available and includes the required fields for tick size, ref_price, band, min_volume, max_volume, position_limit, and max_tps.

## 10. MatchId / deduplication assessment

Observed examples:
- `matchid 1` for `PROBE001:BUY00001` against `MAKER001:SELL0001`
- `matchid 2` for `PROBE001:SELL0002` against `MAKER001:BUY00002`
- `matchid 3` and `matchid 4` for the two pieces of the multi-execution fill

Observed key candidate:
- `(trackedSender, eventType, eventTimestamp, matchId, incomingOrderId, restingOrderId, qty, price, aggressorSide)`

Interpretation:
- In the observed executions, `matchId` is unique per fill and the tuple distinguishes each event.
- `eventType` is required because the same physical trade can appear once as `T` for the incoming/aggressor side and once as `E` for the resting side.
- The updated key correctly distinguishes a real execution from a duplicate replay and from a same-match event on the opposite tracked sender.

Conclusion:
- Confirmed: the design-safe v1 dedup key includes `eventType` and is consistent with the observed sender-specific execution accounting.

## 11. Legacy Taker subject bug

Question / assumption:
- The supplied Taker may still be sending orders to `ex.req` rather than `ex.req.<SENDER>`.

Observed code in `candidate/taker/taker.py`:
- `reply = await self.nc.request("ex.req", order.encode(), timeout=1.0)`

Interpretation:
- This is the wrong subject for the live exchange protocol.
- Therefore the legacy Taker is currently not using the required `ex.req.<SENDER>` subject.

Conclusion:
- Confirmed: the legacy Taker must be fixed in a later Job to send to `ex.req.<SENDER>`.
- This is a material defect and is not fixed in Job 0.1.

## 12. Legacy Taker sell-sign bug

Observed code in `candidate/taker/taker.py`:
- `signed = qty if side == "B" else qty`
- `self.position += signed`

Control check:
- `taker.apply_fill("S", 7, 600)` produced `position == 7` and `cash == -4200.0`.

Interpretation:
- For a SELL fill, `signed` should be negative, not positive.
- The actual code increases `position` instead of decreasing it.

Conclusion:
- CONFIRMED MATERIAL DEFECT.
- This should be recorded for conditional Job 0.3.
- No change to `candidate/taker/taker.py` was made in Job 0.1.

## 13. Startup exposure / readiness gate

Observation from `candidate/docker-compose.yml`:
- `taker:` is started with `depends_on: [exchange]` and no Hedger readiness gate.
- `TAKER_RUN: "86400"` means the provided Taker is intended to run immediately whenever the strategy profile is started.
- There is no `desk.risk.<FEED>` or Hedger readiness gate in the runtime.

Interpretation:
- The Taker process and order logic can start before any future Hedger startup gate exists.
- This means the startup architecture is exposed; the wrong `ex.req` subject may prevent fills in the short term, but that does not make the startup path safe.

Conclusion:
- Confirmed: the supplied Taker can become order-active before a future Hedger readiness/startup gate exists.
- Follow-up: add the startup gate in a later Job; do not fix it in Job 0.1.

## Final assessment
- `ex.req.<SENDER>`: CONFIRMED
- accepted/rejected replies: CONFIRMED
- BUY fill semantics: CONFIRMED
- SELL fill semantics: CONFIRMED
- incoming/resting side interpretation (`T` vs `E`): CONFIRMED; `E_AND_T_REQUIRED`
- multiple `T` / `E` accumulation: CONFIRMED
- cancel: CONFIRMED
- `F` fill-and-kill semantics: CONFIRMED PARTIAL
- `EX_META`: CONFIRMED
- dedup key with `eventType`: CONFIRMED for the v1 design key
- startup exposure: CONFIRMED
- legacy Taker SELL sign defect: CONFIRMED MATERIAL DEFECT
- legacy Taker subject bug: CONFIRMED

## Job 0.2 — Desk accounting baseline

Implemented a minimal, deterministic desk-accounting core in `candidate/hedger/accounting.py`.

- It accepts a tracked sender and a raw sender-specific execution event.
- It recognizes execution-bearing `T` and `E` records and ignores other event types for position changes.
- It resolves the tracked side as `aggressorSide` for `T` and `opposite(aggressorSide)` for `E`.
- It applies signed buys as `+qty` and sells as `-qty`.
- It maintains per-sender positions and a desk-net total.
- It rejects exact duplicates using the v1 key `(trackedSender, eventType, eventTimestamp, matchId, incomingOrderId, restingOrderId, qty, price, aggressorSide)`.
- It keeps same-`matchId` events distinct when the tracked sender or event type differs, so self-trade and cross-sender accounting are both preserved.

Focused validation:
- `./.probe-venv/Scripts/python.exe -m unittest tests.test_desk_accounting_baseline tests.test_taker_legacy_regressions`
- Result: `Ran 17 tests in 0.006s` / `OK`

## Job 0.3 — Legacy Taker correctness fix

The confirmed legacy defects fixed in this Job were intentionally limited to the three material corrections already proven in the live exchange checks:

1. order requests changed from `ex.req` to `ex.req.<configured sender>` with the message sender matching the subject sender
2. SELL local position accounting changed from `+qty` to `-qty`
3. accepted `F` orders now use the actual `Y <n>` immediately traded quantity instead of assuming `filled = CLIP`

Focused regression evidence using the project interpreter:
- `./.probe-venv/Scripts/python.exe -m unittest tests.test_taker_legacy_regressions`
- Result: `Ran 8 tests in 0.005s` / `OK`

Corrected interpretation of the earlier live trace:
- The earlier `REPLY 'EXCHANGE Y 5'` trace was a full `F` order that happened to be filled by multiple executions (`3 + 2`), not a partial-fill rejection.
- The final controlled proof is the relevant partial-`F` regression evidence: requested `F` qty is `5`, total executable opposite liquidity is exactly `3`, the reply is `EXCHANGE Y 3`, and the Taker local position changes by exactly `+3` on the BUY side, with the remainder canceled immediately.

Controlled live regression proof (final recorded evidence):
- `MAKER_REQUEST_SUBJECT='ex.req.MKRCTRL6'`
- `MAKER_REQUEST_PAYLOAD='MKRCTRL6 A AAH6 SELL6001 S 3 600 L'`
- `MAKER_REPLY='EXCHANGE Y 0'`
- `REQUEST_SUBJECT='ex.req.TKRCTRL6'`
- `REQUEST_PAYLOAD='TKRCTRL6 A AAH6 BUY60001 B 5 600 F'`
- `REPLY='EXCHANGE Y 3'`
- `matchId=12`
- execution event: `T TKRCTRL6:BUY60001 MKRCTRL6:SELL6001 3 600 12 B`
- counterparty execution event: `E TKRCTRL6:BUY60001 MKRCTRL6:SELL6001 3 600 12 B`
- `TAKER_POSITION_BEFORE=0`
- `TAKER_POSITION_AFTER=3`
- `ACTUAL_SIGNED_DELTA=+3`
- remainder cancel: `C TKRCTRL6:BUY60001`

Evidence scope:
- live integration proves BUY partial `F` 5 -> `Y3` -> `+3`
- unit regression proves SELL signed accounting is negative
- do not claim live SELL partial-`F` proof unless one was actually executed

Interpretation:
- `requested = 5`
- `Y = 3`
- `actual execution = 3`
- `local position delta = +3` for a BUY-side immediate fill
- `remainder does not rest` because the leftover order is canceled immediately

This Job does not change the future Hedger startup gate. The startup gate remains deferred and separate from the three Taker correctness fixes, as required by the approved scope.

## Job 1.2A — Quoter Runtime Contract Review

### Question
What minimum runtime state must the Quoter trust before becoming ready?

### Check
Asked the implementation agent to independently review PROTOCOL.md,
DESIGN.md and the existing Job 1.1 readiness classes.

### Observation
The agent correctly identified EX_META, ex.bbo.<FEED>, TAKER_FEED and
fail-closed BBO readiness, but incorrectly reported that desk.risk.<FEED>
had no defined wire schema.

### Verification
Re-read DESIGN.md. The desk-risk payload is explicitly defined as:

<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit>
<UNKNOWN|SAFE|CONTROLLED|EMERGENCY> <B|S|X>

The design also requires monotonic sequence handling and a 1000 ms
risk-staleness threshold.

### Decision
Job 1.2 will parse and validate the complete risk message.

Readiness will directly use timestamp, sequence, feed and state.
Position/limits/hedge direction will be retained for the existing wire
contract but will not yet drive quoting behaviour.

Known NATS disconnection will fail readiness closed immediately rather
than waiting for freshness timeouts.

Qwen 7B
→ produced bounded parser draft
→ first draft had constructor/test contradiction
→ reviewer rejected it
→ second draft corrected invariants
→ final code accepted after human/reviewer inspection

Real repo / Maven
→ will provide actual verification evidence


## Job 1.2B — Quoter runtime investigation and agent-review record

### 1. Desk-risk parser task routing

Question:
- Could the bounded desk-risk wire parser be delegated to the local Qwen2.5-Coder 7B model while keeping runtime/NATS integration with the higher-confidence agent path?

Decision:
- Yes. Qwen was used only as a bounded draft generator for `DeskRiskMessage`, `HedgeDirection`, and parser tests.
- Qwen terminal/build claims were not treated as authoritative evidence because the Continue terminal was previously shown to run in a sandbox rather than the real Windows repository.
- Real repository modification, Maven validation, Git validation, and GitHub CI remained the evidence sources.

Agent interaction:
- First Qwen draft made the `DeskRiskMessage` constructor private while its own tests instantiated the constructor directly.
- The first constructor also failed to enforce the invariants that the tests expected.
- Reviewer rejected the draft before integration.
- A correction prompt required a public constructor and constructor-level invariant enforcement.
- The second draft corrected those issues.

Human/reviewer decision:
- Accept the corrected production parser.
- Keep sequence tracking, staleness, NATS integration, readiness, and quoting logic out of the parser.
- Preserve the exact eight-field internal wire contract:
  `<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit> <state> <direction>`.

### 2. Unexpected-change stop rule during parser integration

Observation:
- The first real Maven run failed because `HedgeDirection.java` existed as an empty file.
- `git status` also showed unrelated modifications:
  - `strategy/pom.xml`
  - `QuoterFoundationTests.java`
  - an untracked `QuoterRuntime.java`
  - `NOTES.md`
  - generated `strategy/target/`

Investigation:
- `pom.xml` had unexpectedly gained `io.nats:jnats:2.20.5`.
- `QuoterFoundationTests.java` contained new runtime-readiness tests.
- `QuoterRuntime.java` contained an ad-hoc desk-risk parser accepting forms such as `SAFE` and `state=SAFE`.
- That parser conflicted with the approved complete eight-field `desk.risk.<FEED>` contract.

Decision:
- Apply the stop rule: inspect unexpected changes before continuing.
- Restore the out-of-scope `pom.xml` and `QuoterFoundationTests.java` changes.
- Remove the unapproved `QuoterRuntime.java`.
- Keep the intended `NOTES.md` investigation record.
- Populate the real `HedgeDirection.java` with the approved `B`, `S`, `X` enum.
- Do not treat an agent investigation task as permission to modify production code.

### 3. Real parser validation

Real Windows repository validation:
- `mvn -B test`
- Result:
  - `DeskRiskMessageTest`: 17 tests, 0 failures
  - `QuoterFoundationTests`: 24 tests, 0 failures
  - total: 41 tests, 0 failures
  - `BUILD SUCCESS`
- `git diff --check`: clean.

Repository hygiene:
- VS Code automatically added `java.configuration.updateBuildConfiguration = interactive` to `.vscode/settings.json`.
- This was unrelated to Job 1.2 and was restored instead of committed.
- Maven `strategy/target/` output was treated as generated build output and removed before commit.

Remote verification:
- Branch: `batch1/job-1.2-quoter-runtime-foundation`
- Commit: `9765ebd` — `feat: add desk risk message parser`
- Branch was one commit ahead of `main` and zero commits behind at verification time.
- Remote commit scope was limited to:
  - `candidate/NOTES.md`
  - `DeskRiskMessage.java`
  - `HedgeDirection.java`
  - `DeskRiskMessageTest.java`
- GitHub Actions `TRV CI` run #26 completed successfully.

Interpretation:
- Qwen can be useful for bounded deterministic drafts, but reviewer inspection and real-repository evidence remain mandatory.
- Agent-generated tests are not evidence until they compile and pass in the real project environment.

### 4. Job 1.2B-1 — jnats API investigation

Question:
- What minimum verified jnats API surface is required for Quoter runtime readiness, metadata access, BBO subscription, desk-risk subscription, connection-loss handling, and subscription registration?

Agent investigation:
- Copilot selected `io.nats:jnats:2.20.5` and identified APIs including:
  - `Nats.connect(...)`
  - `Options.Builder.server(...)`
  - connection listeners
  - dispatcher/core subscriptions
  - `Connection.flush(Duration)`
  - `Connection.keyValue(...)`
  - `KeyValue.get(...)`
  - `Connection.getStatus()`
- Copilot recommended a core live subscription for `ex.bbo.<FEED>` and a core subscription for `desk.risk.<FEED>`.

Reviewer challenge:
- Copilot incorrectly listed the `desk.risk` payload format as requiring a runtime probe even though the internal design contract already defines the exact eight-field schema.
- Copilot also classified `ConnectionListener.Events` enum constants as a runtime probe even though they can be verified directly from the selected client bytecode/source.
- BBO reasoning was refined: the unresolved issue is not whether a core subscriber can receive future publications, but whether startup needs retained/latest-message semantics rather than waiting for the next live BBO.
- `flush(Duration)` is only a subscription-registration barrier; it does not prove that valid BBO or risk data has been received and does not itself satisfy readiness.

### 5. jnats bytecode verification

Initial issue:
- A `javap` command used backslash escaping for nested Java classes in PowerShell and failed with `class not found`.

Correction:
- Use single-quoted nested class names in PowerShell.

Commands:
- `jar tf D:\temp\jnats-2.20.5.jar | Select-String 'ConnectionListener|Connection\$Status'`
- `javap -classpath D:\temp\jnats-2.20.5.jar 'io.nats.client.ConnectionListener$Events'`
- `javap -classpath D:\temp\jnats-2.20.5.jar 'io.nats.client.Connection$Status'`

Verified `ConnectionListener.Events` constants:
- `CONNECTED`
- `CLOSED`
- `DISCONNECTED`
- `RECONNECTED`
- `RESUBSCRIBED`
- `DISCOVERED_SERVERS`
- `LAME_DUCK`

Verified `Connection.Status` constants:
- `DISCONNECTED`
- `CONNECTED`
- `CLOSED`
- `RECONNECTING`
- `CONNECTING`

Runtime policy derived from the verified API:
- `DISCONNECTED` => readiness false immediately.
- `CLOSED` => readiness false immediately.
- `LAME_DUCK` => fail closed.
- `RECONNECTED` does not restore readiness by itself.
- `RESUBSCRIBED` does not restore readiness by itself.
- Only connected runtime with newly valid market/risk state can become ready.

### 6. DESIGN.md context reduction

Problem:
- The previous `DESIGN.md` had become too large for efficient repeated agent attachment.
- Important implementation contracts, especially `desk.risk`, were surrounded by duplicated rationale, diagrams, pricing details, and repeated risk rules.
- During Job 1.2A/1.2B investigation, this contributed to an agent missing an already-defined internal contract when the full design file was not attached.

Decision:
- Refactor `DESIGN.md` into an agent-friendly implementation contract rather than a design narrative.
- Keep:
  - design priorities,
  - runtime architecture,
  - execution-accounting invariants,
  - startup gate,
  - full desk-risk internal wire contract,
  - Quoter readiness,
  - pricing/risk invariants,
  - Hedger risk states,
  - NATS runtime decisions,
  - explicitly unresolved runtime questions.
- Avoid duplicating detailed external exchange protocol already owned by `PROTOCOL.md`.
- Keep investigation history and rejected assumptions in `NOTES.md`.

Sources-of-truth split:
- `PROTOCOL.md` => supplied exchange/external wire semantics.
- `DESIGN.md` => desk architecture, internal contracts, safety invariants, current runtime decisions.
- `NOTES.md` => questions, probes, agent mistakes, reviewer challenges, evidence, and decisions.

### 7. Runtime decisions confirmed during DESIGN review

#### Sequence epoch after Hedger restart

Problem:
- `seq` is monotonic only for one Hedger process lifetime.
- If Quoter permanently remembers a high sequence number, a restarted Hedger beginning from a low sequence could otherwise be ignored indefinitely.

Decision:
- During a currently trusted/fresh risk epoch:
  - `seq <= lastAcceptedSeq` is ignored.
- If trust is lost because of risk staleness, NATS disconnect/reconnect, or equivalent startup re-establishment:
  - enter `UNKNOWN`,
  - invalidate the previous sequence epoch,
  - accept the next valid risk message as the baseline of a new epoch.
- This is the minimal v1 solution; no producer-session identifier is added unless evidence later requires it.

#### BBO startup wait

Decision:
- Start with a core live subscription.
- After subscription registration/flush, wait for the first valid live BBO.
- Startup wait threshold: 3000 ms, matching `MARKET_DATA_STALE_MS`.
- If no valid BBO arrives within 3000 ms:
  - remain not ready,
  - emit a warning,
  - never auto-promote readiness.
- If controlled evidence shows normal BBO updates can exceed this threshold, investigate JetStream latest/replay semantics before changing the design.

#### Reconnect policy

Decision:
- Treat reconnect as a clean runtime trust state.
- Do not reuse pre-disconnect BBO or desk-risk state.
- Require a newly received valid BBO and newly received valid desk-risk message before restoring readiness.
- Revisit only if measured evidence later demonstrates a material performance problem.

#### Risk freshness time base

Decision:
- Risk staleness is based on time since the last valid local receipt using a local monotonic clock.
- Do not compute staleness from `now - remote ts_ns`.
- Remote/process clock differences are expected and make cross-process timestamp subtraction unsuitable as the freshness authority.
- `ts_ns` is still parsed and retained for diagnostics/investigation.

#### Desk-risk override logging

Decision:
- Desk risk takes priority over normal Quoter economics.
- When risk causes an actual suppression/override transition, record a meaningful log.
- Minimum useful context:
  - risk state,
  - direction,
  - net position,
  - suppressed side or overridden action,
  - reason.
- Avoid logging every unchanged heartbeat.
- Log meaningful transitions such as state change, suppression change, actual override action, staleness, disconnect, and reconnect.

### 8. Current Job 1.2B-1 remaining investigation

Still requires controlled supplied-stack verification:
- Confirm `EX_META` can be accessed through the selected Java client path and that the `TAKER_FEED` metadata value matches the current Java metadata parser contract.
- Confirm a core live subscription receives a valid `ex.bbo.<FEED>` message within the 3000 ms startup threshold under normal supplied-stack conditions.
- If not, investigate JetStream retained/latest-message semantics.

Do not implement the production NATS runtime until these remaining questions are resolved and recorded.
