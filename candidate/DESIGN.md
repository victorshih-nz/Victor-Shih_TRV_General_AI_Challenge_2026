# TRV Trading Desk — Agent Contract

Use this file as the implementation contract for Copilot/Qwen.

Source of truth:
- `PROTOCOL.md` = exchange wire protocol
- `DESIGN.md` = internal invariants
- `NOTES.md` = evidence/probes/history

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

Minimum necessary implementation. No speculative features, generic OMS, or premature abstraction.

### Strategy tunability

Keep architecture/safety stable while exposing about 10-15 centralized ENV-driven knobs
for rapid simulation tuning.

Typical knobs include quote size/edge/improvement, valuation/microprice/band/EWMA,
Quoter/desk position limits, and Add/Cancel request timeouts.

Tune economic defaults from simulation evidence; Add/Cancel timeouts start at `1000 ms`.
Tuning must not change `timeout != failure`, one-order-per-side, cancel-before-replace,
UNKNOWN exposure blocking, or desk-risk priority. Optimize safe fills + spread capture,
not fill rate alone.

## 2. Desk position accounting

Hedger owns authoritative desk-wide position.

Subscribe exactly:
```text
ex.md.<FEED>.<TAKER_SENDER>
ex.md.<FEED>.<SENDER>
ex.md.<FEED>.<HEDGER_SENDER>
```

Do not also subscribe to `ex.md.<FEED>.*` in production.

Execution:
```text
T = tracked sender is aggressor
E = tracked sender is resting owner

T side = aggressorSide
E side = opposite(aggressorSide)

Buy  = +qty
Sell = -qty
```

Both `T` and `E` carry executions.

```text
deskPosition = takerPosition + quoterPosition + hedgerPosition
```

Never infer position from requested quantity or `Y <n>`.

Dedup executions using the full tracked-sender/event/order/match/qty/price/side tuple;
never by `matchId` alone.

## 3. Hedger / desk.risk

Publish:
```text
desk.risk.<FEED>
```

Payload fields are timestamp, seq, feed, net position, soft/hard limits, state
(`UNKNOWN|SAFE|CONTROLLED|EMERGENCY`) and hedge direction (`B|S|X`).

Rules: publish on state/position change + heartbeat <=250 ms; stale after >1000 ms
without valid local receipt; use local monotonic freshness; seq is monotonic per trusted
epoch and resets after trust loss; `B` buy reduces risk, `S` sell reduces risk, `X` none.

Hedger uses `F`. `Y <n>` is not position authority.

After each hedge:
```text
execution events
-> recompute position
-> require valid BBO
-> decide next hedge
```

## 4. Runtime readiness

Conceptual states:
```text
READY
WAITING
FATAL
```

`READY` requires trusted connection/subscription + valid trusted BBO + fresh valid
risk != `UNKNOWN`. `WAITING` is recoverable fail-closed; `FATAL` requires restart.

Order lifecycle does not belong in `RuntimeState`.

Future quote gate:
```text
runtimeState.isReady() && orderManager.isReconciled()
```

## 5. BBO and reconnect trust

BBO is latest authoritative state, not a heartbeat.

A valid BBO stays trusted while the connection remains trusted.
Do not expire it merely because no newer BBO arrives.

Invalidate BBO on newer empty/invalid/malformed authoritative state, connection trust
loss, or incompatible metadata. Risk freshness remains heartbeat-based at 1000 ms.

On disconnect:
- stop quoting immediately
- invalidate runtime trust
- no Add/Cancel while disconnected
- local order knowledge becomes untrusted for quoting

On reconnect/resubscription: restore subscriptions, revalidate `EX_META`, recover
retained/live BBO, require new valid risk, then reconcile own Quoter orders before
new exposure.

Metadata unavailable -> `WAITING`.
Malformed/incompatible metadata -> `FATAL`.
Lower risk seq is allowed after a new trust epoch.

## 6. Quoter order lifecycle

Exactly two logical quote slots:
```text
BID
ASK
```

Each slot holds at most one logical order.

States:
```text
EMPTY
PENDING_ADD
ACTIVE
PENDING_CANCEL
UNKNOWN
```

Terminal:
```text
remove logical order -> EMPTY
```

No long-lived `CLOSED`.

### Replacement invariant

Mandatory:
```text
ACTIVE
-> send cancel
-> PENDING_CANCEL
-> confirm old order terminal
-> EMPTY
-> submit replacement
```

Prohibited:
```text
cancel request sent -> immediately submit replacement
```

Sending cancel does not prove the order is gone.

### Timeout semantics

```text
ADD timeout    -> UNKNOWN
CANCEL timeout -> UNKNOWN
```

Timeout means outcome unknown, not failure.

Never infer timeout means Add rejected, Cancel succeeded/failed, or the order cannot
still fill.

Any unresolved `UNKNOWN` blocks all new Quoter exposure.
Never blindly retry Add with a new order id after Add timeout.

Defaults:
```text
ADD_REQUEST_TIMEOUT_MS    = 1000
CANCEL_REQUEST_TIMEOUT_MS = 1000
```

Emergency cancel dispatch target:
```text
EMERGENCY_CANCEL_DISPATCH_TARGET_MS = 100
```

This is an SLA, not request timeout. Missing it does not permit abandoning cancellation.

Fills may still arrive while:
```text
ACTIVE
PENDING_CANCEL
UNKNOWN
```
and must still be accounted.

Reconnect must not resume from BBO+risk alone; own-order reconciliation must also pass.

Use exact `C` for normal replacement.
Use sender-scoped `X` for startup/reconnect recovery only after controlled probe confirms
selector semantics.

## 7. Order IDs

Order ids are 8 chars and unique per sender.

Do not use a restart-resetting simple counter.
Use a compact V1 session/process-unique prefix plus local counter, simple enough to test
and explain.

## 8. Exchange rules

Order entry:
```text
ex.req.<SENDER>
```

Sender in subject and payload must match. A sender manages only its own orders.

Add:
```text
<SENDER> A <FEED> <id:8> <B|S> <volume> <price> <M|L|F>
```

Exact cancel:
```text
<SENDER> C <FEED> <id:8>
```

Cancel-many:
```text
<SENDER> X <FEED> <B|S|X> <price>
```

Replies:
```text
Y <n>
N <code> <text>
```

`Y <n>` confirms request handling, not authoritative position.

Quoter normally uses passive `L`.
Hedger uses `F`.

## 9. Failure rules

When uncertain: stop adding exposure, preserve accounting, reconcile, and resume only
from authoritative evidence.

Never treat request timeout, disconnect, missing local event, or stale local assumption as
proof an order is gone.

High transient position is dangerous even if brief.
Risk-reducing actions outrank profit opportunities.

## 10. Legacy Taker

Modify only after controlled proof of:
- material correctness/risk bug, or
- minimal change required for desk-wide safety

No broad refactor.

## 11. Current Job 1.3 Micro Task 1

Implement only pure deterministic two-slot lifecycle core + unit tests:

```text
BID / ASK
max one logical order per slot
EMPTY / PENDING_ADD / ACTIVE / PENDING_CANCEL / UNKNOWN
terminal -> EMPTY
```

Do not implement NATS Add/Cancel, timers, cancel-many recovery, reconnect purge, STP,
quote generation, persistence, or generic OMS in this micro task.

## 12. Deferred

Defer without evidence: multi-contract, directional prediction, persistent trading DB,
generic OMS, complex crash recovery, exactly-once framework, multiple quote levels per
side, and unrelated Taker refactoring.

## 13. Agent execution rules

1. Work only on current Job/Micro Task.
2. This file is the internal contract; `PROTOCOL.md` owns wire details.
3. Use `NOTES.md` for evidence/history, not implementation rules.
4. Do not revive rejected assumptions or guess unresolved runtime behaviour.
5. Stop on unexpected repo changes.
6. Prefer existing architecture; keep file/class count minimal.
7. Run focused tests, full relevant tests, and build validation.
8. Record new controlled runtime findings in `NOTES.md`.
9. Do not start the next Micro Task without reviewer approval.
