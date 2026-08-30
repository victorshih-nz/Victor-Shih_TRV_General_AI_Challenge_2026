# GitHub Copilot Repository Instructions

Work only on the current explicitly assigned Job.

Primary working directory: `candidate/`.

## 1. Scope and Authority

- Implement only the current Job and stop when it is complete.
- Do not begin the next Job automatically.
- Do not add speculative features, abstractions, refactors, or unrelated cleanup.
- Do not change architecture, protocol interpretation, risk limits, pricing rules, or startup behaviour unless the current Job explicitly requires it.
- Do not modify legacy Taker code unless the current Job explicitly authorizes a confirmed defect fix or a required desk-wide safety integration.
- Do not commit, push, merge, rebase, reset, clean, or force-push unless explicitly instructed.
- If the current prompt conflicts with an explicitly referenced authoritative file, stop and report the conflict instead of inventing a resolution.

Authoritative challenge/runtime sources, when explicitly referenced:
- `candidate/TASK.md`
- `candidate/PROTOCOL.md`
- supplied Docker/runtime configuration
- the current approved Job prompt / Issue
- explicitly referenced sections of `candidate/REQUIREMENTS.md` or `candidate/DESIGN.md`

## 2. Context Discipline

Read only:
1. files explicitly referenced with `@file` in the current prompt; and
2. the smallest additional files directly required to compile, test, or resolve a concrete dependency.

Do not automatically read:
- `candidate/DEVELOPMENT_PLAN.md`
- `candidate/FEATURE_DESIGN_IMPLEMENTATION_PLAN.md`
- `candidate/TEST_PLAN.md`
- `candidate/NOTES.md`
- unrelated source directories
- unrelated Git history
- broad repository documentation

Do not perform broad repository exploration unless a concrete compile/test/runtime failure makes it necessary.

When extra context is required, inspect the smallest relevant file or section and explain why it was needed.

## 3. Component Constraints

- Quoter: Java.
- Hedger: Python.
- Legacy Taker: Python.
- Desk contract source: `TAKER_FEED`.
- Quoter sender: `SENDER`.
- Taker sender: `TAKER_SENDER`.
- Hedger sender: `HEDGER_SENDER`.
- NATS connection: `NATS_URL`.

Do not hard-code sample-market feed names, prices, limits, or other simulator constants when environment configuration or exchange metadata provides them.

## 4. Trading Safety Invariants

Correctness and capital preservation take priority over profitability and implementation convenience.

Fail closed when critical trading state is uncertain.

### Execution and Position Accounting

For tracked-seat accounting, sender-specific exchange `T` and `E` events are authoritative.

- `T`: tracked sender owns the incoming/aggressor order.
- `E`: tracked sender owns the resting order.
- For `T`, tracked side = `aggressorSide`.
- For `E`, tracked side = opposite(`aggressorSide`).
- Buy => positive quantity.
- Sell => negative quantity.
- Never infer authoritative position from requested order quantity.
- Fill-and-Kill (`F`) may execute partially.
- `Y <n>` is an execution acknowledgement, not a substitute for authoritative execution-event accounting.
- Accumulate multiple execution events correctly.
- Duplicate/redelivered execution events must not double-count.
- Do not combine wildcard and exact sender-specific subscriptions for production position accounting.

### Market and Risk State

Do not create new exposure when any required critical state is invalid, missing, stale, UNKNOWN, or uncertain, including:
- metadata,
- BBO,
- desk risk,
- order state,
- position state.

A timeout alone must never convert unknown state into trusted state.

Normal valuation must not override Minimum Edge.

Inventory control overrides valuation opportunity.

Hard / Emergency risk reduction overrides normal quote economics.

Risk-increasing orders must be suppressed or cancelled where required.

Temporary one-sided quoting is allowed for risk reduction.

### Exchange Constraints

Respect exchange metadata and protocol constraints, including:
- tick size,
- price band,
- min/max volume,
- position limit,
- TPS limit,
- sender-specific order-entry subjects,
- valid wire formats.

## 5. Python Environment

For local Python probes/tests from `candidate/`, use only:

`.\.probe-venv\Scripts\python.exe`

Do not:
- use global/system Python,
- use Anaconda,
- use `py`,
- search for alternate Python interpreters,
- install probe dependencies globally.

If `.probe-venv` is unavailable or broken, stop and report it.

## 6. Docker Development

Reuse a healthy running Docker stack.

Before Docker work, check:

`docker compose ps`

Do not automatically run:
- `docker compose down`
- `docker compose up`
- `./run.sh`
- `wsl bash ./run.sh`

Restart/rebuild only when:
- the current Job explicitly requires a clean runtime,
- a relevant container image must be rebuilt for the changed component,
- runtime state is unhealthy/inconsistent,
- Docker/runtime configuration changed,
- or the current prompt explicitly requests it.

Do not restart Docker merely because host-side source or probe code changed.

## 7. Job Workflow

Before editing, run:

`git status --short --branch`

Confirm:
- the expected branch is active;
- there are no unexpected unrelated changes.

During implementation:
- make the smallest necessary change;
- keep trading-critical logic explicit;
- use production code in tests;
- add focused regression coverage for changed behaviour;
- do not weaken existing tests.

Before reporting:
- run focused tests relevant to the Job;
- run the relevant build/test command;
- run `git diff --check`;
- run `git status --short`.

Run Docker/full integration only when the current Job requires it.

Do not modify `.github/workflows/ci.yml` unless a real CI portability/build failure proves a change is necessary.

## 8. Documentation and Reporting

Do not read all of `candidate/NOTES.md` by default.

If the current Job explicitly requires a NOTES update, add only concise evidence, confirmed findings, failures, or design-impacting decisions needed for that Job.

Return only the items requested by the current prompt.

Do not add:
- broad repository summaries,
- long reasoning narratives,
- speculative future work,
- next-Job implementation,
unless explicitly requested.
