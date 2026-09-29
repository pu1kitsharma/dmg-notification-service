# AI workflow

Tool: Claude Code (CLI/VS Code) working inside this repository. Everything below happened in that loop; nothing was generated outside the repo.

## 1. Design first, code second
- `CLAUDE.md` holds the rules the assistant must follow on every change: scope limits from the brief, package-by-feature, Flyway-only schema changes, *every state transition goes through `NotificationStateMachine` and writes an audit row in the same transaction*, *no blocking I/O inside a DB transaction*, *concurrency-sensitive code must have a multi-threaded test*, small commits in `type(scope): summary` form.
- `docs/DESIGN.md` was written before the implementation: queue-as-table with CAS claim + lease, per-channel bounded pools, GCRA token bucket, idempotency layers, state machine, data model, API sketch. The implementation follows it, and later fixes update it.

## 2. Skills (`.claude/skills/`)
- `new-state-transition`: checklist for touching `NotificationStatus` / `NotificationStateMachine` without breaking the audit invariant (used for the DEAD → PENDING replay).
- `concurrency-test`: recipe for the latch-gated multi-threaded tests CLAUDE.md requires (used for the token bucket, dispatcher races and the same-key idempotency race).

## 3. Requirements audit as an interviewer
After the first full implementation the assistant was asked to review the code against the task PDF as an interviewer would. That produced a gap list which drove the second round of work:
replay of dead notifications, richer reports and list filters, batch submit, stable page JSON, a 10k-notification load test, token refunds, and later a hardening pass (below).

## 4. Plan mode for the hardening pass
A second audit listed *unverified claims* and *weak spots*. It was turned into a written plan (prioritised P0–P2, one commit per item) and approved before any code was changed. Findings that came from reading the code rather than from the first audit:
- README quick-tour used an unset env var → first `curl` would 401.
- Disabling a channel did not stop already-queued rows; `senderId` was stored but never sent.
- A saturated channel pool stopped the whole tenant's batch (head-of-line blocking across channels).
- No provider timeout: a hung `send()` pinned a pool thread; lease was consumed by queue wait.
- Idempotency key reuse with a different payload silently returned the old notification.

## 5. Verification, not just generation
- `mvn test` after every change (52 tests: unit, Spring integration, multi-threaded, load).
- The app was actually started and exercised end to end with `docs/smoke.sh` (this caught a quoting bug in the script itself).
- New regression tests were checked to fail against the old behaviour (e.g. the saturated-pool test fails on the old dispatcher).
- A load-test failure that appeared during the work was investigated instead of retried away: 3 of the first 5 runs timed out at 120 s on a machine with load average ~30, with no exceptions and no expired leases in the logs. Conclusion: CPU starvation plus four dispatcher threads busy-spinning while the global rate limit throttled them. The test now backs off when a cycle dispatches nothing and allows a longer timeout. The hang could not be reproduced afterwards (10+ clean runs); that conclusion is an inference, not a proof.
- Time-dependent tests were rewritten to use a controllable clock (`MutableClock`) rather than sleeps.

## 6. What the AI did not decide
Scope, the trade-offs listed in README "Assumptions", and what to leave out (broker, Redis, real providers, user management) were choices made with the author; the assistant proposed options and the author approved the plan. Claims that were not verified are labelled as such in the README (e.g. Postgres was reviewed but only exercised on H2).
