# Feature Design & Implementation Plan

## 1. Purpose

This document defines how a feature or corrective change moves from an idea to
approved implementation.

It exists to prevent:

- Agent A inventing architecture during coding,
- requirements drifting between Jobs,
- large unreviewable changes,
- unnecessary feature work,
- trading-risk behaviour being implemented without explicit design.

The workflow is intentionally lightweight because delivery speed matters.

## Relationship to Other Documents

This document does not replace `DEVELOPMENT_PLAN.md`.

`DEVELOPMENT_PLAN.md` remains the authoritative Job sequence and batch roadmap.
This document defines how a new feature or corrective change is designed, scoped,
and approved before it enters that roadmap.

---

## 2. Roles

### Victor — Final Decision Maker

Approves feature scope, major design choices, risk trade-offs, and progression.

### Agent B — ChatGPT + GitHub (Reviewer / Architect)

- Identify whether a feature is necessary
- Produce / update the design
- Define acceptance criteria
- Split work into meaningful Jobs
- Review actual diffs and trading/risk consequences
- Approve next-step recommendation

### Agent A — GitHub Copilot Coding Agent (Developer)

- Implement the approved Job
- Add tests/probes
- Collect evidence
- Keep scope contained
- Stop after the Job

### GitHub Actions

Automated test judge.

---

## 3. Feature Entry Rule

A new feature or change may enter the plan only when at least one is true:

1. required by the challenge
2. required for trading correctness
3. required for risk control
4. fixes a measured reliability problem
5. produces a clear measured profitability improvement
6. required for grading/submission compatibility

Otherwise: **defer it**.

---

## 4. Feature Lifecycle

```text
Need / Evidence
      |
      v
Agent B review
      |
      v
Feature design
      |
      v
Victor decision
      |
      v
Meaningful Job
      |
      v
Agent A implementation
      |
      v
Focused tests / probes
      |
      v
Docker / integration if applicable
      |
      v
Agent B diff review
      |
      v
GitHub Actions green
      |
      v
Manual verification
      |
      v
Approve / revise / stop
```

Do not skip directly from idea to implementation when the change affects:

- order entry, fills, position accounting
- pricing, inventory, desk risk
- startup gating, hedging
- Docker / grading runtime

---

## 5. Feature Design Template

Before implementation, define:

### Feature / Change

Name:

### Why It Is Needed

Which entry rule justifies it?

### Problem / Evidence

What is currently wrong or insufficient?

### Scope

What will change?

### Out of Scope

What will explicitly not change?

### Acceptance Criteria

How do we know it is done and correct?

### Risk Impact

Can this create unintended exposure, double-count fills, leave risk-increasing
orders resting, or fail open?

### Test Expectations

Unit / probe / Docker / repeated trading evidence required?

### Proposed Job Split

How is this broken into reviewable Jobs?

---

## 6. Trading-Critical Review Gate

Any change touching the following requires explicit Agent B review before progression:

- order side, fill accounting, execution deduplication, position sign
- quote price / size, Minimum Edge, inventory skew, risk limits
- Hedger logic, startup readiness, NATS subjects/payloads
- stale-state behaviour, cancellation/replacement, TPS handling

Reviewer must answer:

1. Can this create unintended exposure?
2. Can this double-count or miss a fill?
3. Can stale state produce an order?
4. Can it leave risk-increasing orders resting?
5. Can Quoter and Hedger work against each other?
6. Does failure mode create risk or fail closed?
7. Does it still work under private grading market assumptions?

---

## 7. Test Gate

| Change type              | Minimum expectation                          |
|--------------------------|----------------------------------------------|
| Pure calculation         | Unit tests                                   |
| Protocol behaviour       | Controlled exchange probe                    |
| Order / fill / position  | Unit tests + controlled probe where practical |
| Runtime / startup / Docker | Docker integration                         |
| Trading behaviour        | Repeated simulator evidence                  |

Positive PnL alone is not enough.

---

## 8. Progression Rule

A Job progresses only when:

- scope is complete
- required tests pass
- relevant Docker validation passes
- `git diff --check` passes
- Agent B has reviewed the actual diff
- required CI is green
- manual checks are identified / completed as appropriate

If a material defect is found: fix only that Job; do not proceed and compensate later.

---

## 9. Documentation Rule

Update `candidate/NOTES.md` during work, not after the project.

Record assumptions, experiments, failed attempts, confirmed protocol behaviour,
unexpected code behaviour, bug evidence, and why a design changed.

---

## 10. Current Approved Roadmap

### Batch 0 — Evidence and Baseline

- Job 0.1 — Protocol and Legacy Verification
- Job 0.2 — Desk Accounting / Baseline Probe
- Conditional Job 0.3 — Minimal Legacy Taker Correction

### Batch 1 — Java Quoter

- Job 1.1 — Complete Java Quoter
- Job 1.2 — Quoter Integration / Tuning

### Batch 2 — Python Hedger

- Job 2.1 — Authoritative Desk Position + Risk Publisher
- Job 2.2 — Hedge Execution + Full Desk Integration

### Batch 3 — Hardening

- Job 3.1 — Repeated Trading Evaluation
- Job 3.2 — CI and Submission
