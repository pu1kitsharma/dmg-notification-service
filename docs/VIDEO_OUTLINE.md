# Video outline (≤ 10 min)

**0:00–1:00 · What it is.** Multi-tenant notification service: 4 channels, tenant templates, immediate + scheduled sends, per-tenant rate limits and fairness, retries with backoff, no duplicate deliveries, audit trail. Two roles: platform admin, tenant admin.

**1:00–3:00 · Approach & stack.** Spring Boot 3.3 / Java 17 / JPA + Flyway / H2 (Postgres-compatible). Why a **DB-backed outbox** instead of a broker: durable, transactional with the submit, zero infra, swappable behind `Dispatcher`/`Channel`. Package by feature; RBAC via Spring Security Basic (advanced auth out of scope).

**3:00–6:30 · Walk through the hard part** (open the code):
1. `NotificationService.submit` → row is the queue (202 fast), idempotency key + fingerprint.
2. `Dispatcher.runOnce`: per-tenant batch (fairness) → token bucket (`TokenBucket` GCRA, tenant then global, refund when unused) → atomic CAS claim → per-channel bounded pool (saturation only blocks that channel).
3. `DeliveryWorker`: lease renewal, watchdog timeout, provider call outside any transaction with a stable `deliveryKey`.
4. `DeliveryOutcomeService`: fenced by attempt number (a slow reclaimed worker cannot overwrite a newer attempt) → `NotificationStateMachine` writes the audit event in the same transaction.
5. "How do you avoid duplicates?" → four layers (idempotency key, CAS claim, provider deliveryKey, fencing) = effectively-once on at-least-once.

**6:30–8:30 · Testing.** 63 tests, plus 167 live end-to-end checks (`docs/e2e.sh`), crash-recovery scenarios (`docs/e2e-resilience.sh`), all also run on Postgres 16. Show: 16-thread token bucket, 4 dispatchers × 300 rows exactly once, 10k-notification load test (~1,200 msg/s on H2), noisy-tenant fairness, saturated-pool isolation, hung-provider timeout, idempotency race. Mention `docs/smoke.sh` against the running app and the controllable test clock.

**8:30–9:30 · AI workflow.** CLAUDE.md rules + DESIGN.md first, two skills, interviewer-style audit → plan mode → small commits, verifying by running the app and checking that new tests fail on old code. See `docs/AI_WORKFLOW.md`.

**9:30–10:00 · Honest limits.** In-memory rate limits (Redis for multi-instance), single tenant-admin user, providers simulated, Postgres ~2.5x slower than in-memory H2 on the load test, providers that ignore interrupts can pin a thread.

## Live demo commands (README Quick tour)
`mvn spring-boot:run` → create tenant → template → send with `Idempotency-Key` → `GET /notifications/{id}` (status + events + attempts) → send to `fail-permanent@x.com` → DEAD → `POST /{id}/replay` → `GET /reports/delivery`.
