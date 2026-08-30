# GitHub Copilot Repository Instructions

## 1. Project Context

This repository contains the TRV General AI Challenge 2026 trading desk.

Primary working directory: `candidate/`

Desk components:

- `candidate/taker/` — supplied legacy Python taker
- `candidate/strategy/` — Java two-sided Quoter to be implemented
- `candidate/hedger/` — Python Hedger to be implemented
- NATS / JetStream — exchange messaging
- Docker Compose — local integration and grading runtime

Final grading command:

```bash
cd candidate
./run.sh --sim --strategy
```

The private grading market is not the supplied sample simulator.
Never implement logic that depends on undocumented behaviour in `candidate/sim/market.py`.

---

## 2. Agent Roles

### Agent A — GitHub Copilot Coding Agent (Developer)

- Implement only the currently approved Job
- Add focused tests/probes
- Run required validation
- Keep changes minimal and reviewable
- Update `candidate/NOTES.md`
- Stop before the next Job

**Hard limits (do not violate):**

- Do not invent new architecture
- Do not add features not present in the current Job
- Do not "improve" legacy Taker momentum logic
- Do not change protocols, risk limits, or startup behaviour without approved design revision
- Do not begin the next Job automatically

### Agent B — ChatGPT + GitHub (Reviewer / Architect)

- Maintain requirements/design consistency
- Review actual Git diff
- Review trading/risk consequences
- Review tests and CI
- Approve or reject progression

### Final Decision Maker

Victor.

---

## 3. Read Before Changing Code

For every Job, read the relevant sections of:

1. `candidate/REQUIREMENTS.md`
2. `candidate/DESIGN.md`
3. `candidate/DEVELOPMENT_PLAN.md`
4. `candidate/TEST_PLAN.md`
5. `candidate/FEATURE_DESIGN_IMPLEMENTATION_PLAN.md`
6. The GitHub Issue defining the current Job
7. `candidate/TASK.md`
8. `candidate/PROTOCOL.md`

If the Issue conflicts with approved requirements/design: **stop and report the conflict**. Do not invent a resolution.

---

## 4. Engineering Priority

1. Trading correctness
2. Capital preservation
3. Risk control
4. Reliability
5. Profitability
6. Delivery speed

Delivery should be fast, but never by weakening trading correctness or risk control.

---

## 5. Trading Safety Invariants (Mandatory)

### Execution / Position

- Sender-specific exchange `T` and `E` events are the authoritative fill source for tracked-seat position accounting.
- On `ex.md.<FEED>.<trackedSender>`, `T` represents the tracked sender as incoming/aggressor; `E` represents the tracked sender as resting.
- For `T`, tracked side = `aggressorSide`; for `E`, tracked side = opposite(`aggressorSide`).
- Buy → positive position change; Sell → negative position change.
- Never infer position solely from order acceptance or from the requested order quantity.
- Fill-and-Kill (`F`) may partially execute: `Y <n>` reports immediate traded volume and any remainder is cancelled. Position still comes from sender-specific `T`/`E` events.
- Multiple execution-bearing events must accumulate correctly.
- Duplicate/redelivered execution-bearing events must not double-count position.
- Production position accounting should use the three exact sender-specific subjects; wildcard subscriptions are for probes/debugging only and must not be combined with the exact subjects for accounting.

### Desk-wide Startup Gate

Before Hedger accounting readiness:

```text
Taker orders  = 0
Quoter orders = 0
Taker fills   = 0
Quoter fills  = 0
```

- Legacy Taker must not be order-active before Hedger publishes the first non-UNKNOWN `desk.risk.<FEED>` state
- Preferred implementation: container/startup-layer gate (wait for desk-ready → then `exec python taker.py`)
- Leaving the Taker completely ungated is prohibited
- A timeout alone must never convert UNKNOWN into a trusted zero position

### Market State (Fail Closed)

Do not create new exposure when:

- metadata is unavailable or invalid
- BBO is missing, invalid, or stale
- desk risk state is UNKNOWN
- order or position state is uncertain

### Pricing / Risk

- Normal valuation must not cross Minimum Edge
- Inventory control overrides valuation opportunity
- Hard / Emergency risk reduction overrides normal quote economics and may fully override Minimum Edge
- Soft / Controlled may only partially relax Minimum Edge (bounds in DESIGN.md)
- Hard / Emergency must stop the risk-increasing side immediately
- Temporary one-sided quoting is allowed for risk reduction
- Do not allow stale resting quotes to accumulate

### Exchange Limits

Respect tick size, price band, min/max volume, position limit, TPS limits.

---

## 6. Language Decisions

- Quoter: Java (do not switch to Python)
- Hedger: Python
- Legacy Taker: Python

---

## 7. Python Interpreter / Local Probe Environment

For local Python probes and Python test commands, work from the `candidate/` directory and use this interpreter explicitly:

```powershell
.\.probe-venv\Scripts\python.exe
```

Examples:

```powershell
.\.probe-venv\Scripts\python.exe .\probes\protocol_probe.py
.\.probe-venv\Scripts\python.exe -m py_compile .\probes\protocol_probe.py
```

Rules:

- Do not use the global `python` command for Job probes/tests.
- Do not use Anaconda/system Python as a substitute when `.probe-venv` exists.
- Do not install probe dependencies globally.
- If `.probe-venv` is missing or broken, stop and report it instead of silently switching interpreters.
- `.vscode/settings.json` points VS Code to the same workspace interpreter.

---

## 8. Legacy Taker Policy

Treat `candidate/taker/` as owned legacy code.

Do not refactor or behaviourally change unless:

1. controlled testing confirms a material defect; or
2. a minimal integration change is required for a desk-wide safety invariant (e.g. startup gate).

Even then: smallest necessary change + regression coverage + evidence in `NOTES.md`.

Still prohibited: cosmetic refactoring, strategy rewrite, momentum-logic improvement, unrelated cleanup.

---

## 9. Scope Control

A change may enter implementation only when it satisfies at least one of:

1. challenge compliance
2. trading correctness
3. risk control
4. measured reliability
5. measured profitability
6. grading / submission compatibility

Avoid speculative features, unnecessary abstractions, enterprise frameworks, general-purpose trading-platform architecture, unrelated cleanup.

---

## 10. Job Workflow

### Before

```bash
git status --short --branch
```

Confirm expected branch, no unrelated changes, current Job scope. If unexpected changes exist → stop and report.

### During

- Work only on the current Job
- Prefer one clear implementation over many abstractions
- Keep trading-critical logic explicit
- Add focused tests/probes with the implementation
- Copilot Coding Agent may create the automatic draft PR associated with the current Job
- Do not create additional PRs for the same Job
- Do not merge the PR
- Do not begin the next Job automatically

### Before Completion

Run focused tests, relevant broader tests, build checks, Docker validation (where required), `git diff --check`.

Update `candidate/NOTES.md` with assumptions, probes, evidence, confirmed bugs, surprising findings, design-impacting decisions.

Final Job report must include:

1. changed files + purpose
2. test commands and results
3. Docker/integration result (if applicable)
4. `git diff --stat`
5. known risks
6. manual checks required
7. whether the next Job is recommended

Do not proceed until Agent B review is complete.

---

## 11. Local Docker Development Lifecycle

During normal local development, assume the Docker exchange environment may already be running and reuse it whenever it is healthy.

Before starting or restarting Docker, first run from `candidate/`:

```powershell
docker compose ps
```

If NATS and Exchange are already running and healthy, reuse them.

Do **not** automatically run any of the following before each probe, code edit, or test:

```text
docker compose down
docker compose up
./run.sh
wsl bash ./run.sh
```

Restart/recreate the Docker stack only when at least one is true:

- the current Job explicitly requires a fresh empty-book environment,
- container state is inconsistent,
- NATS or Exchange is unavailable/unhealthy,
- Docker/runtime configuration changed and requires rebuild/restart,
- Agent B explicitly requests a clean restart.

For ordinary host-side probe changes under `candidate/probes/`, changing Python code does not require rebuilding or restarting Docker.

When a test requires empty-book determinism, do not restart the full stack by default. First determine whether the current state can be cleaned or isolated safely; restart only when necessary for reliable evidence.

Never restart Docker merely because source code changed unless the changed component is running from a built container image that must be rebuilt/restarted for that test.

---

## 12. Docker Requirements

- Build from source inside Dockerfiles
- No host-only dependencies
- Support `linux/amd64`
- Work through the supplied Docker Compose stack
- Do not copy locally compiled binaries into final images
- Do not modify the supplied exchange image unless explicitly required

---

## 13. CI Requirement

GitHub Actions is the automated judge.

A Job must not progress while required CI checks are failing unless the failure is confirmed infrastructure-only and documented.

Do not weaken or remove tests merely to make CI green.

---

## 14. AI / Submission Evidence

Required:

- `candidate/NOTES.md` (live engineering evidence log)
- final `TRANSCRIPT.txt`

Preserve AI-agent work so the transcript accurately shows what was asked, assumed, tested, failed, changed, and how decisions were made.
