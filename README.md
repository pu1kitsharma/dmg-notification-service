# Multi-tenant Notification Service

Spring Boot 3.3 · Java 17 · Maven · JPA + Flyway · H2 (default) / PostgreSQL · Spring Security (HTTP Basic)

Multi-channel (EMAIL, SMS, PUSH, IN_APP) notification platform: tenant-defined versioned templates, immediate and
scheduled sends, per-tenant + global rate limiting, per-tenant fairness, bounded per-channel worker pools,
retries with exponential backoff, exactly-once *effect* on retry, and a persisted audit trail.

## Run

```bash
mvn test                # 26 tests
mvn spring-boot:run     # http://localhost:8080, in-memory H2
```

A platform admin is seeded on startup: `admin` / `admin12345` (override with `APP_BOOTSTRAP_ADMIN_PASSWORD`).
For PostgreSQL set `spring.datasource.url/username/password`; the Flyway migration is Postgres-compatible.

## Quick tour

```bash
H='Content-Type: application/json'
PLATFORM="admin:$APP_BOOTSTRAP_ADMIN_PASSWORD"   # default password is in application.yml (dev only)
TENANT="acme-admin:<the adminPassword you chose>"
# platform admin creates a tenant (+ its admin user)
curl -u "$PLATFORM" -H "$H" -X POST localhost:8080/api/v1/tenants \
  -d '{"name":"acme","ratePerSecond":50,"burst":100,"maxAttempts":5,"adminUsername":"acme-admin","adminPassword":"<choose-a-password>"}'
# tenant admin defines a template, then sends
curl -u "$TENANT" -H "$H" -X POST localhost:8080/api/v1/templates \
  -d '{"name":"welcome","channel":"EMAIL","subject":"Hi {{name}}","body":"Welcome {{name}}"}'
curl -u "$TENANT" -H "$H" -H 'Idempotency-Key: order-42' -X POST localhost:8080/api/v1/notifications \
  -d '{"channel":"EMAIL","templateName":"welcome","recipient":"ada@example.com","variables":{"name":"Ada"}}'
curl -u "$TENANT" localhost:8080/api/v1/notifications/<id>     # status + audit events + attempts
curl -u "$TENANT" localhost:8080/api/v1/reports/delivery
```

Recipients containing `fail-transient` / `fail-permanent` make the simulated provider fail, for demos.

## API

| Role | Endpoint |
|---|---|
| PLATFORM_ADMIN | `POST/GET /api/v1/tenants`, `GET/PATCH /api/v1/tenants/{id}` (activate, rate, burst, maxAttempts), `GET/PUT /api/v1/limits/global` |
| TENANT_ADMIN | `POST/GET /api/v1/templates`, `GET /api/v1/templates/{id}` |
| | `GET /api/v1/channels`, `PUT /api/v1/channels/{EMAIL\|SMS\|PUSH\|IN_APP}` |
| | `POST /api/v1/notifications` (202 new / 200 idempotent replay), `POST /api/v1/notifications/batch` (≤100 items, per-item outcome), `GET /api/v1/notifications?status=&channel=&from=&to=&page=&size=`, `GET /api/v1/notifications/{id}`, `POST /api/v1/notifications/{id}/cancel`, `POST /api/v1/notifications/{id}/replay` (DEAD only) |
| | `GET /api/v1/reports/delivery?from=&to=&channel=` (totals, success rate, by channel/status, by template, top 5 dead-letter reasons) |

Lists are paged as `{content:[...], page:{size,number,totalElements,totalPages}}` (stable DTO shape).

Errors are RFC 7807 problem details: 400 validation, 401 unauthenticated, 403 wrong role/deactivated tenant, 404 not found (also for other tenants' data), 409 conflict, 422 template variables missing.

## Architecture

```
POST /notifications ──► validate ─► render template ─► INSERT notifications(PENDING) + audit ──► 202
                                                              │  (the table is the queue)
   Dispatcher (poll every 500ms)  ◄───────────────────────────┘
     1. reclaim expired leases → treated as transient failure
     2. tenants with due work, rotated each cycle
     3. per tenant: take ≤ batch-per-tenant rows → token bucket (tenant, then global)
     4. atomic claim  PENDING→PROCESSING (CAS UPDATE, attemptCount++, lease)
     5. submit to that channel's bounded pool  (full → release claim, back off)
   Worker (pool thread, outside any DB tx): provider.send(deliveryKey=id)
     └► DeliveryOutcomeService (fenced by attemptCount): SENT | PENDING+backoff | DEAD, + attempt row + audit event
```

Key decisions (details in [docs/DESIGN.md](docs/DESIGN.md)):

- **DB-backed outbox instead of a broker.** Durable, transactional with the submit, restart-safe, zero infra for a take-home.
  Delivery is behind `Channel` and dispatch behind `Dispatcher`; swapping the poll for Kafka/RabbitMQ consumers is a contained change.
- **No duplicate deliveries.** (1) `Idempotency-Key` per tenant dedups submits (unique index, race-safe). (2) Claim is an atomic CAS,
  so two dispatchers never hold the same row. (3) The provider call uses a stable `deliveryKey`; if a send succeeded but the outcome
  was never saved, the lease expires and the retry is de-duplicated provider-side. (4) Outcomes are fenced by attempt number so a slow,
  reclaimed worker cannot clobber a newer attempt. Effectively-once, on top of at-least-once.
- **Rate limiting:** lock-free GCRA token bucket (single `AtomicLong` + CAS) per tenant plus one global bucket.
  Over-limit work stays `PENDING` — throttled, never dropped.
- **Fairness:** each cycle serves every tenant with due work up to `batch-per-tenant`, start tenant rotates. A noisy tenant cannot starve a quiet one.
- **Isolation between channels:** one bounded `ThreadPoolExecutor` per channel with a bounded queue and `AbortPolicy`; saturation applies back-pressure.
- **Retries:** exponential backoff with equal jitter, capped; permanent errors go straight to `DEAD`; attempts exhausted → `DEAD`.
- **Audit:** every transition goes through `NotificationStateMachine`, which validates the transition and writes `notification_events` in the same transaction. Each attempt is in `notification_attempts`.
- **Tenant isolation by construction:** tenant-scoped endpoints take the tenant from the authenticated principal, never from the URL/body; other tenants' ids return 404.

## Assumptions

1. Providers are simulated (`SimulatedChannel`); real ones implement `Channel` and register in `ChannelRegistry`. Providers are assumed to honour an idempotency key.
2. Only two roles, as specified. Tenant admins are created with the tenant; one admin user per tenant at creation (no user-management API).
3. Channels are enabled by default; a config row disables one or sets a sender id. Provider credentials are out of scope.
4. Templates are immutable and versioned; a submit uses the latest version at submit time and stores the rendered subject/body (later edits never change queued messages).
5. Template syntax is `{{name}}`, no logic, no escaping. Missing variable → 422 at submit time.
6. `scheduledAt` in the past = send now; more than 365 days ahead = 400. Scheduled and immediate sends share one mechanism (`next_attempt_at`).
7. Idempotency keys are scoped per tenant; a replay returns the original notification (payload not compared).
8. `maxAttempts` counts total attempts, including the first. Lease-expiry counts as an attempt.
9. A cancelled notification must still be `PENDING`; once claimed it can't be cancelled.
10. Rate limits are per-instance in memory (a multi-instance deployment would need a shared store such as Redis). The claim/idempotency logic is already multi-instance-safe because it lives in the DB.
11. Global limit is applied after the tenant limit, so a throttled tenant doesn't burn global tokens; a rare global rejection wastes one tenant token.
12. Security is HTTP Basic + BCrypt, as advanced auth is out of scope. The seeded admin password is for development only.
13. Payload size caps: body 4000 chars, subject 500.
15. Batch submit: max 100 items; malformed items (bean validation) reject the whole request with 400, business errors (unknown template, disabled channel, bad recipient) are reported per item with the status a single submit would have returned. Items are not one transaction.
16. Report `successRate` = SENT / (SENT + DEAD); pending, in-flight and cancelled are excluded. `from` inclusive, `to` exclusive, on `createdAt`.
14. Replay (`DEAD -> PENDING`) is manual and tenant-scoped. Attempt numbers keep increasing (they are the fencing token and the history); the retry budget restarts from `attempt_base`, so a replay gets `maxAttempts` fresh tries.

## Testing (26 tests: unit + Spring integration + concurrency)

| Area | Test |
|---|---|
| Token bucket | burst then reject, steady refill, **16 threads never exceed burst** |
| Template | substitution, regex-special values, all missing variables reported |
| Retry policy | exponential growth within jitter bounds, cap, no overflow |
| Dispatch | happy path + full audit trail; transient retries succeed with exactly 1 delivery; DEAD after max attempts; permanent failure not retried; scheduled sends wait; **rate limit defers but never drops**; **noisy tenant can't starve another**; **4 concurrent dispatchers × 300 notifications → each delivered exactly once**; expired lease recovery; **stale worker can't overwrite newer attempt**; cancel |
| API / RBAC | 401/403, cross-tenant isolation (404), idempotency-key replay, validation (400/404/422/409), disabled channel / deactivated tenant, end-to-end + report, template versioning |

## Not done / next steps

Webhook/callback delivery receipts, per-tenant priority queues, Redis-backed distributed rate limiting,
Kafka-based ingestion, pagination on reports, user-management API.

## AI workflow

See [CLAUDE.md](CLAUDE.md) (rules used while building) and `.claude/skills/`.
