# CLAUDE.md — Multi-tenant Notification Service

Take-home for DMG (Java Backend Developer). Spring Boot 3.3, Java 17, Maven. Design in `docs/DESIGN.md`.

## Rules
- Scope: REST APIs, persistence, RBAC, validation, tests. No UI, Docker/CI, microservices, OAuth.
- Package by feature under `com.dmg.notify`: `tenant`, `template`, `notification`, `dispatch`, `ratelimit`, `channel`, `audit`, `security`, `common`.
- Schema changes only via Flyway migrations (`src/main/resources/db/migration`); `ddl-auto=validate`.
- Every state transition of a notification goes through `NotificationStateMachine` and writes an audit row in the same transaction.
- No blocking I/O inside the DB transaction. Channel send happens outside it.
- Concurrency-sensitive code (rate limiter, claim/lease, idempotency) MUST have a multi-threaded test.
- Commit small and often; message format `type(scope): summary`.

## Commands
- `mvn test` — all tests
- `mvn spring-boot:run` — run on H2 (in-memory)

## Definition of done per feature
Code + unit test + (if it touches DB/HTTP) integration test + README assumption noted.
