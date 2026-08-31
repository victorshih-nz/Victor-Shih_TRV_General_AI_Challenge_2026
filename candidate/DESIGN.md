# TRV Trading Desk — Agent Contract

Active implementation contract.

Source of truth:
- `PROTOCOL.md` = wire protocol
- `DESIGN.md` = active invariants/current task
- `NOTES.md` = completed evidence/history

## 1. Scope / priorities

V1: one `TAKER_FEED`; Taker Python, Quoter Java, Hedger Python.

```text
Capital preservation
> execution correctness
> fail-closed reliability
> trading economics
> delivery speed
```

Minimum necessary implementation. No speculative features or generic OMS.
Economic knobs may be ENV-driven but never weaken safety invariants.

## 2. Desk execution accounting

Hedger owns authoritative desk-wide position.

Subscribe exactly:
```text
ex.md.<FEED>.<TAKER_SENDER>
ex.md.<FEED>.<SENDER>
ex.md.<FEED>.<HEDGER_SENDER>
```

No production wildcard subscription.

```text
T = tracked sender is aggressor
E = tracked sender is resting owner
T side = aggressorSide
E side = opposite(aggressorSide)
Buy = +qty
Sell = -qty
```

Both E/T carry executions. Never infer position from request qty, `A.volume`, or `Y <n>`.

Dedup with:
```text
(trackedSender,eventType,eventTimestamp,matchId,
 incomingOrderId,restingOrderId,qty,price,aggressorSide)
```
Never by `matchId` alone.

## 3. Hedger / desk.risk

Publish `desk.risk.<FEED>`:
```text
<ts_ns> <seq> <feed> <net_position> <soft_limit> <hard_limit>
<UNKNOWN|SAFE|CONTROLLED|EMERGENCY> <B|S|X>
```

Publish on state/position change + heartbeat <=250 ms.
Stale >1000 ms by local monotonic receipt age.
Seq is monotonic per trusted epoch and may reset after trust loss.
Hedger uses `F`; executions are position authority.

## 4. Runtime readiness

States: `READY / WAITING / FATAL`.

READY requires trusted connection/subscriptions + valid trusted BBO + fresh valid
risk != UNKNOWN.

Lifecycle stays separate.

Quote gate:
```text
runtimeState.isReady() && orderManager.isReconciled()
```

## 5. BBO / reconnect trust

BBO is latest state, not heartbeat. Keep valid BBO trusted while connection trust holds.
Invalidate on newer invalid/empty state, trust loss, or incompatible metadata.

Disconnect:
- stop quoting
- invalidate runtime trust
- no Add/Cancel while disconnected
- local order assumptions cannot resume exposure

Reconnect/resubscription:
- restore subscriptions
- revalidate `EX_META`
- recover retained/live BBO
- require NEW valid risk
- reconcile own Quoter orders
- then allow new exposure

Metadata unavailable -> WAITING.
Malformed/incompatible metadata -> FATAL.

## 6. Quoter order lifecycle

Exactly two logical slots: `BID`, `ASK`; max one logical order each.

States:
```text
EMPTY
PENDING_ADD
ACTIVE
PENDING_CANCEL
UNKNOWN
```

Terminal -> EMPTY.

Occupied slot tracks:
```text
orderId
requestedQty
filledQty
remainingQty = requestedQty - accumulated deduplicated E/T qty
```

### Request layer

```text
beginAdd
beginCancel
markRequestUncertain
```

`Y` causes no lifecycle transition.
Rejected/timeout/disconnected request outcome is uncertain:
`PENDING_ADD/PENDING_CANCEL -> UNKNOWN`.

Timeout is not proof of failure/success.
Any unresolved UNKNOWN blocks new Quoter exposure.
Sending reconciliation cancel while UNKNOWN does not resolve it.

### Authoritative evidence

```text
A   -> onResting
E/T -> onExecution
C   -> onCancelled
```

Frozen rules:
- E/T volume = actual matched qty.
- one order may produce multiple executions.
- `A.volume` is original submitted qty in supplied runtime; never remaining authority.
- immediately fully-filled L may still emit A.
- A/E/T/C may share one exchange timestamp.
- timestamp is NOT lifecycle sequence.
- correctness must not depend on callback order.

Execution:
```text
newFilled < requested -> partial; preserve state
newFilled == requested -> full; EMPTY
newFilled > requested -> invariant violation
```

Normal larger contra order is not overfill:
```text
our remaining 1 + contra incoming 5
-> our execution qty 1
-> our order EMPTY
-> contra remainder 4 remains exchange-owned
```

Convergence:
```text
PENDING_ADD + A -> ACTIVE
PENDING_ADD + partial E/T -> PENDING_ADD
PENDING_ADD + full E/T -> EMPTY
ACTIVE + partial E/T -> ACTIVE
ACTIVE + full E/T -> EMPTY
PENDING_CANCEL + partial E/T -> PENDING_CANCEL
PENDING_CANCEL + full E/T -> EMPTY
UNKNOWN + partial E/T -> UNKNOWN
UNKNOWN + full E/T -> EMPTY
same-current C -> EMPTY
late old-order A/E/T/C -> lifecycle ignore; never reopen
```

Lifecycle ignore != execution ignore. Legitimate deduplicated E/T must still be accounted.

### Replacement / uncertainty

```text
ACTIVE
-> send exact C
-> PENDING_CANCEL
-> authoritative terminal evidence
-> EMPTY
-> replacement
```

Never replace because cancel was merely sent or `Y` arrived.

Defaults:
```text
ADD_REQUEST_TIMEOUT_MS=1000
CANCEL_REQUEST_TIMEOUT_MS=1000
```

Emergency cancel dispatch target = 100 ms SLA, not request timeout.
Use exact C normally. Use sender-scoped X only after selector semantics are proven.

## 7. Order IDs

8 chars, unique per sender.
Do not use restart-resetting simple counters.
Use compact session/process-unique prefix + local counter.

## 8. Exchange essentials

```text
Order entry: ex.req.<SENDER>
Add: <SENDER> A <FEED> <id:8> <B|S> <volume> <price> <M|L|F>
Cancel: <SENDER> C <FEED> <id:8>
Cancel-many: <SENDER> X <FEED> <B|S|X> <price>
Replies: Y <n> | N <code> <text>
```

Subject/payload sender must match.
Quoter normally uses passive L; Hedger uses F.

## 9. Failure rules

When uncertain: stop new exposure, preserve accounting, reconcile from authoritative
evidence, resume only after trust is restored.

Never treat timeout, disconnect, missing event, `A.volume`, or `Y <n>` as proof an order
is gone or proof of remaining qty.

Risk reduction outranks profit.

## 10. Legacy Taker

Modify only after controlled proof of material correctness/risk defect or minimum
desk-wide safety change. No broad refactor.

## 11. Current Micro Task — Quoter lifecycle MD integration

Wire the Quoter's own sender-specific stream into `OrderManager`.

Subscribe:
```text
ex.md.<FEED>.<SENDER>
```

Handle only Quoter lifecycle evidence:
```text
A / E / T / C
```

Requirements:
- read configured `SENDER`; subject identity must be exact
- map public `<sender>:<orderId>` to the current 8-char order id
- A side comes from A event B/S
- for T, tracked order is incoming; side = aggressorSide
- for E, tracked order is resting; side = opposite(aggressorSide)
- E/T qty uses actual event volume
- never use A.volume to update remaining
- do not depend on A preceding E/T
- old/stale lifecycle evidence must not reopen orders
- malformed own-sender lifecycle input must fail closed
- runtime readiness and lifecycle remain separate
- keep file/class count minimal

Do NOT add here:
- quote generation/economic tuning
- Add/Cancel request sending
- timeout scheduler
- X recovery
- STP
- reconnect purge/reconciliation
- Hedger changes
- generic OMS

Validation:
```text
focused integration tests
all Java tests
mvn package
git diff --check
```

## 12. Deferred

Without evidence defer: multi-contract, directional prediction, persistent DB, generic
OMS, complex crash recovery, exactly-once framework, multiple quote levels.

## 13. Agent rules

1. Work only on current Micro Task.
2. DESIGN owns invariants; PROTOCOL owns wire details; NOTES owns evidence/history.
3. Do not revive rejected assumptions or guess unresolved runtime behaviour.
4. Stop on unexpected repo changes.
5. Prefer existing architecture; keep file/class count minimal.
6. Run focused tests, full relevant tests, and build validation.
7. Record new controlled findings in NOTES.
8. Do not start next Micro Task without reviewer approval.
