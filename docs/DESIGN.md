# Design — Multi-tenant Notification Service

## Core decisions (the "why" for the video)
| Concern | Decision | Reason |
|---|---|---|
| Queue | DB-backed outbox (`notifications` table is the queue); poller claims rows with an atomic compare-and-set `UPDATE ... WHERE status='PENDING'` + lease (portable across H2/Postgres, no `SKIP LOCKED` needed) | Durable, no broker needed, survives restart; a `QueuePort` interface lets Kafka/RabbitMQ slot in later (JD-relevant talking point) |
| Workers | One bounded `ThreadPoolExecutor` per channel (fixed queue, `CallerRunsPolicy` avoided → reject + re-lease) | Channel isolation: slow SMS provider can't starve email |
| Fairness | Per-tenant round-robin claim: claim at most N rows per tenant per poll cycle | One noisy tenant can't monopolise workers |
| Rate limit | Per-tenant token bucket (lock-free CAS on `AtomicLong` nanos/tokens) + global limit; over-limit → row stays `PENDING` with `next_attempt_at` pushed out (not dropped) | Classic DSA, testable under concurrency |
| Idempotency | (a) client `Idempotency-Key` unique per tenant on submit; (b) provider send uses `deliveryKey = notificationId` (same across attempts) so a retry after a lost outcome is de-duplicated by the provider; (c) outcomes are fenced by `attemptCount` so a stale worker cannot overwrite a newer attempt | "No duplicate deliveries on retry" |
| Retries | Exponential backoff + full jitter, max attempts per tenant config, classify `TransientFailure` vs `PermanentFailure` → `DEAD` | Matches "transient failures" |
| Lease recovery | Rows in `PROCESSING` with expired `lease_until` are reclaimed by the poller | Worker crash safety |
| Audit | `notification_events` append-only (from→to, attempt, reason, ts) written in same tx as state change | Required audit trail |
| Templates | `{{var}}` substitution, versioned per tenant+channel, missing-variable → 422 at submit time | Fail fast |
| Scheduling | `scheduled_at` — same poller picks up when due | One mechanism for immediate + scheduled |
| Channels | `Channel` strategy interface; Email/SMS/Push/InApp are simulated adapters with configurable failure rate (for tests/demo) | No real providers needed |
| RBAC | Spring Security HTTP Basic, roles `PLATFORM_ADMIN`, `TENANT_ADMIN`; tenant admin bound to one `tenantId`, enforced in service layer (not just URL) | Tenant isolation is the real risk |

## State machine
`PENDING → PROCESSING → SENT` (terminal)
`PROCESSING → PENDING` (transient fail / lease expiry, attempt < max, backoff; also released when pool saturated)
`PROCESSING → DEAD` (permanent fail / attempts exhausted)
`PENDING → CANCELLED` (tenant cancels before pickup)

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
