# Design — Multi-tenant Notification Service

## Core decisions (the "why" for the video)
| Concern | Decision | Reason |
|---|---|---|
| Queue | DB-backed outbox (`notifications` table is the queue); poller claims rows with an atomic compare-and-set `UPDATE ... WHERE status='PENDING'` + lease (portable across H2/Postgres, no `SKIP LOCKED` needed) | Durable, no broker needed, survives restart; there is no separate queue abstraction: the poller (`Dispatcher`) and worker (`DeliveryWorker`) are the only pieces that would change to consume from a broker like Kafka/RabbitMQ) |
| Workers | One bounded `ThreadPoolExecutor` per channel (fixed queue, `CallerRunsPolicy` avoided → reject + re-lease) | Channel isolation: slow SMS provider can't starve email |
| Fairness | Per-tenant round-robin claim: claim at most N rows per tenant per poll cycle; disabled and saturated channels are excluded in the query so they cannot occupy a tenant's batch | One noisy tenant can't monopolise workers |
| Rate limit | Per-tenant token bucket (lock-free CAS on `AtomicLong` nanos/tokens) + global limit; over-limit → the row simply stays `PENDING` and is picked up by a later poll cycle once tokens refill (`next_attempt_at` is not changed, nothing is dropped); unused permits are refunded | Classic DSA, testable under concurrency |
| Idempotency | (a) client `Idempotency-Key` unique per tenant on submit, with a SHA-256 request fingerprint (`request_hash`) so reuse with a different payload is a 409; (b) provider send uses `deliveryKey = notificationId` (same across attempts) so a retry after a lost outcome is de-duplicated by the provider; (c) outcomes are fenced by `attemptCount` so a stale worker cannot overwrite a newer attempt | "No duplicate deliveries on retry" |
| Retries | Exponential backoff + equal jitter (half deterministic, half random), max attempts per tenant config, classify `TransientFailure` vs `PermanentFailure` → `DEAD` | Matches "transient failures" |
| Lease recovery | Rows in `PROCESSING` with expired `lease_until` are reclaimed by the poller. The lease is renewed (fenced by attempt) when a worker thread starts, and a watchdog interrupts a provider call after `send-timeout-ms` (< lease) | Worker crash safety; a hung provider cannot pin a thread or cause a re-send while healthy |
| Audit | `notification_events` append-only (from→to, attempt, reason, ts) written in same tx as state change | Required audit trail |
| Templates | `{{var}}` substitution, versioned per tenant+channel, missing-variable → 422 at submit time | Fail fast |
| Scheduling | `scheduled_at` — same poller picks up when due | One mechanism for immediate + scheduled |
| Channels | `Channel` strategy interface; Email/SMS/Push/InApp are simulated adapters; failures are triggered by the recipient text (`fail-transient`, `fail-permanent`) and latency is configurable via `app.channels.latency-ms` (for tests/demo) | No real providers needed |
| RBAC | Spring Security HTTP Basic, roles `PLATFORM_ADMIN`, `TENANT_ADMIN`; tenant admin bound to one `tenantId`, enforced in service layer (not just URL) | Tenant isolation is the real risk |

## State machine
`PENDING → PROCESSING → SENT` (terminal)
`PROCESSING → PENDING` (transient fail / lease expiry, attempt < max, backoff; also released when pool saturated)
`PROCESSING → DEAD` (permanent fail / attempts exhausted)
`PENDING → CANCELLED` (tenant cancels before pickup)
`DEAD → PENDING` (tenant replays; `attempt_base` = attempts so far, budget restarts, audit event written)

## Data model
tenants, tenant_channel_config, templates(version), notifications, notification_attempts, notification_events, global_limits, users.

## API sketch
- Platform admin: `POST/GET/PATCH /api/v1/tenants`, `PUT /api/v1/limits/global`
- Tenant admin: `POST/GET /api/v1/templates`, `PUT /api/v1/channels/{type}`, `POST /api/v1/notifications` (immediate/scheduled, `Idempotency-Key`), `GET /api/v1/notifications/{id}` (+ event trail), `POST .../cancel`, `GET /api/v1/reports/delivery?from&to&channel`

## Build order (48h plan)
1. Flyway schema + entities + RBAC skeleton (tenant, users)
2. Templates + submit API + idempotency key
3. State machine + audit
4. Token bucket + fairness claim + worker pools
5. Retry/backoff + lease recovery
6. Reports + validation/error handling
7. Tests: concurrency (no oversend, no dup), rate limit, retry, RBAC/tenant isolation
8. README (assumptions), record Loom (approach, stack, AI workflow, testing)
