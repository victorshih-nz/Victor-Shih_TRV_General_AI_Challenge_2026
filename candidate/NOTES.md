# TRV General AI Challenge 2026 — Working Notes

This file is updated during development to record assumptions, probes, evidence, surprises, and design decisions as they occur.

## Baseline

- Local environment: Windows + WSL2 + Docker Desktop.
- `bash ./run.sh --sim` completed successfully before implementation work.
- Development baseline documents: REQUIREMENTS.md v1.3, DESIGN.md v1.3, DEVELOPMENT_PLAN.md v1.3, TEST_PLAN.md v1.3.
- Repository workflow baseline includes Copilot instructions, PR template, CI workflow, and feature design/implementation plan.

## Batch 0 — Evidence and Baseline

### Job 0.1 — Protocol and Legacy Verification

Status: IN PROGRESS

Branch: `batch0/job-0.1-protocol-legacy-verification`

Questions to prove with controlled evidence:

1. Does order entry require `ex.req.<SENDER>` as documented?
2. What are the observed accepted/rejected reply formats?
3. How are buy and sell executions represented in `E` events?
4. Does the supplied Taker's sell-side accounting incorrectly increase position?
5. How should incoming/resting sides be inferred from `aggressorSide`?
6. Can one order produce multiple `E` messages, and how should they accumulate?
7. What cancel behaviour is needed by the planned design?
8. Is `F` atomic full-or-reject in the supplied exchange?
9. Can EX_META be read as documented?
10. Is the proposed execution dedup key safe for observed events?
11. Must the legacy Taker order-entry subject be corrected?
12. Can the supplied Taker create exposure before Hedger readiness under the current startup path?

#### High-confidence hypotheses before probing

- `taker.py` appears to update both buy and sell fills with a positive signed quantity. This must be verified with one controlled sell before modification.
- `taker.py` appears to request on `ex.req` rather than `ex.req.<SENDER>`. This must be verified against the running exchange before modification.

#### Evidence

Pending controlled probe.

#### Decision

Pending.
