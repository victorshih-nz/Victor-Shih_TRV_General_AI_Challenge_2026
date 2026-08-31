## Job 1.3 — Partial-fill ordering and quantity semantics probe

### Question

Before wiring live Quoter market-data events into `OrderManager`, verify the exchange
semantics that could otherwise create quantity or lifecycle races:

- Does E/T `volume` mean actual matched quantity or incoming requested quantity?
- Is `A.volume` original submitted quantity or remaining resting quantity?
- Does an immediately fully-filled L order still emit A?
- Can one incoming order produce multiple execution records?
- Can A/E/T/C timestamps be used as lifecycle sequence?
- If our resting remainder is smaller than the contra request, what quantity is reported
  for our execution?

### Probe

Source:
- `candidate/probes/PartialFillOrderingProbe.java`

Runtime:
- feed `AAH6`
- tracked sender `PFPROBE1`
- contra sender `PFPROBE2`
- tick size `1`

The probe used sender-specific market-data subscriptions and separately recorded local
callback receipt sequence and exchange event timestamp.

### Controlled observations

#### Single partial

Contra SELL 3 rested, then tracked BUY L 10 crossed it.

Observed:
```text
reply: EXCHANGE Y 3
A PFPROBE1:... B 10 ...
E ... volume=3 ...
T ... volume=3 ...
```

The tracked A reported `10`, while the actual execution reported `3`.

Later external orders filled the remaining tracked quantity as separate E events
(`1` and `6`). This confirms the remainder continued resting and remained executable.

Conclusion:
- `A.volume` is original submitted quantity in the observed runtime, not current
  remaining quantity.
- E/T volume is actual matched quantity.
- remaining quantity must be calculated from requested quantity minus accumulated
  deduplicated E/T quantities.

#### Immediate full fill

Contra SELL 10 rested, then tracked BUY L 10 crossed it.

Observed:
```text
reply: EXCHANGE Y 10
A ... volume=10
E ... volume=10
T ... volume=10
C ...
```

Conclusion:
- An L order can emit A even when it immediately fills fully.
- A therefore cannot mean "this full quantity is currently resting".
- Full E/T is terminal lifecycle evidence; later A/C must not resurrect a terminal order.

#### Multiple partial matches

Two contra SELL orders of 3 and 2 rested; tracked BUY L 10 crossed both.

Observed:
```text
reply: EXCHANGE Y 5
A ... volume=10
E/T ... volume=3 matchId=778333
E/T ... volume=2 matchId=778334
```

Conclusion:
- One order can generate multiple execution records.
- Each deduplicated E/T quantity must be accumulated independently.
- Do not assume one request produces one execution callback.

#### Contra quantity larger than our resting quantity

Tracked SELL L 1 rested. Contra BUY L 5 crossed it.

Observed:
```text
contra reply: EXCHANGE Y 1
tracked E ... volume=1
contra T ... volume=1
tracked C ...
```

The contra remainder then remained on the exchange and later traded `4` against another
participant.

Conclusion:
- The execution reported for our order is the quantity actually matched to our order.
- Our remaining `1` becomes FULL/EMPTY; the contra's extra `4` is not an overfill of our
  order and is handled by the exchange.
- `newFilled > requestedQty` remains an invariant violation, not a normal matching case.

### Ordering / timestamp finding

In the observed cases, local callback receipt order was A before E/T and then C where
applicable. However, A/E/T/C created by the same matching action often shared the exact
same exchange timestamp.

Conclusion:
- exchange timestamp is not a lifecycle sequence number.
- production correctness must not depend on timestamp ordering.
- lifecycle must converge correctly even if callback delivery order changes.

### Probe limitation

The intended `CASE1_NO_FILL` was not a controlled no-fill result: external `MOVER001`
traded the entire resting order shortly after A. It must not be cited as proof of
long-lived no-fill behavior.

The active simulator also produced transient empty-side BBO updates, which
`QuoterIntegration` correctly rejected as invalid BBO state. Those warnings do not alter
the execution conclusions above.

### Frozen lifecycle consequences

```text
remainingQty = requestedQty - accumulated deduplicated E/T qty

A:
lifecycle evidence only
never quantity authority

partial E/T:
preserve PENDING_ADD / ACTIVE / PENDING_CANCEL / UNKNOWN

full E/T:
EMPTY

same-current C:
EMPTY

late old-order A/E/T/C:
ignore for lifecycle; never reopen
```

Position/exposure accounting remains separate:
- deduplicate E/T first
- account the authoritative execution
- then apply lifecycle matching
- lifecycle ignore must never cause a legitimate execution to be discarded

### Validation around the probe

Before the probe:
- Maven package: BUILD SUCCESS
- full Java suite: 84 tests, 0 failures/errors/skipped
- `OrderManagerTest`: 31 tests, 0 failures/errors/skipped

The probe source is retained as submission evidence; generated `target/`, `out/`, jar,
and class files remain non-source artifacts and are not submission evidence.
