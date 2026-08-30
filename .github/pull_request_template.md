## Summary

<!-- What does this PR change? Keep this short and specific. -->

## Job / Scope

- Batch:
- Job:
- Related Issue:
- Branch:

### In Scope

-

### Explicitly Out of Scope

-

## Trading / Risk Impact

<!-- Required for every PR, even when the answer is "none". -->

### Position / Exposure Impact

-

### Order Lifecycle Impact

-

### Pricing / PnL Impact

-

### Failure Behaviour

-

### Risk Invariants Checked

- [ ] Buy/sell signed position accounting remains correct
- [ ] Execution events are not double-counted
- [ ] No new exposure is created from UNKNOWN/stale critical state
- [ ] Risk-increasing orders are blocked where required
- [ ] No stale resting-order accumulation introduced
- [ ] Exchange metadata / TPS / position constraints remain respected
- [ ] Startup gate remains safe if this PR touches startup/runtime behaviour
- [ ] Minimum Edge rules remain correct if this PR touches quoting

## Changed Files

| File | Purpose |
|---|---|
|  |  |

## Validation

### Focused Tests / Probes

```text
command:
result:
```

### Broader Tests

```text
command:
result:
```

### Docker / Integration

```text
command:
result:
```

### Diff Check

```text
git diff --check
result:
```

## Trading Evidence

<!-- Include measured evidence when this PR changes trading behaviour. -->

- Fill behaviour:
- Position behaviour:
- Peak exposure:
- Hard-zone duration:
- PnL / spread capture:
- Rejects / timeouts:
- Other:

If not applicable, state why.

## Legacy Taker

- [ ] This PR does not modify the legacy Taker
- [ ] This PR modifies the legacy Taker due to a confirmed defect
- [ ] This PR modifies the legacy Taker only for a desk-wide safety invariant

Evidence / justification:

-

## Documentation

- [ ] `candidate/NOTES.md` updated
- [ ] Design / requirements updated if behaviour changed
- [ ] Useful probes/tests are included
- [ ] AI work for final `TRANSCRIPT.txt` has been preserved

## Reviewer Checklist

### Agent B — Reviewer / Architect

- [ ] Scope matches the approved Job
- [ ] No unnecessary feature/refactor added
- [ ] Trading correctness reviewed
- [ ] Risk consequences reviewed
- [ ] Tests are meaningful, not only happy-path
- [ ] Docker/grading compatibility reviewed
- [ ] CI green
- [ ] Manual verification identified
- [ ] Safe to progress to the next Job

## Manual Verification Required

-

## Known Risks / Follow-Up

-

## Final Decision

<!-- Agent B / Victor -->

- [ ] Approve
- [ ] Request changes
- [ ] Do not progress
