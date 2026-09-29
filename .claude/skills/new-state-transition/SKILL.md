---
name: new-state-transition
description: Add or change a notification status transition (e.g. DEAD -> PENDING replay) without breaking the audit-trail invariant. Use when touching NotificationStatus or NotificationStateMachine.
---

# Adding a notification state transition

Invariant from CLAUDE.md: every state transition goes through `NotificationStateMachine` and writes a
`notification_events` row in the same transaction.

1. Add the edge to `NotificationStatus.allowed()`. Terminal states have no outgoing edges unless the feature says so.
2. Add a `@Transactional` method on `NotificationStateMachine` that loads the row (tenant-scoped, 404 otherwise),
   checks preconditions (409 on wrong state) and calls `transition(n, to, reason)`. Never `setStatus` elsewhere.
3. If the row becomes dispatchable again, reset `attemptCount`, `nextAttemptAt`, `lastError` in the same method.
4. Expose it via `NotificationService` + controller using `TenantContext.requireTenantId()`; never take the tenant from the URL/body.
5. Tests: an audit-trail assertion (`events.findByNotificationIdOrderByIdAsc`), an illegal-state 409, a cross-tenant 404.
6. Update the state diagram in `docs/DESIGN.md` and the API table in `README.md`.
