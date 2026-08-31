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
