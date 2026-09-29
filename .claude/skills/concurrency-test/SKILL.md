---
name: concurrency-test
description: Write a multi-threaded test for concurrency-sensitive code (rate limiter, claim/lease, idempotency). Required by CLAUDE.md for such code.
---

# Writing a concurrency test

1. Pick the invariant, stated as a number: "N threads race, exactly K succeed" / "never more than burst" / "each row delivered once".
2. Release all threads at once with a `CountDownLatch` start gate; use an `ExecutorService` sized to the thread count.
3. Assert on persisted or counted outcomes (DB rows, `ScriptedChannel.successes`), not on timing.
4. Use small fixed sizes (16 threads, a few hundred rows) so the test is deterministic and finishes in seconds; use `awaitTermination` with a timeout so a deadlock fails instead of hanging.
5. Drive the dispatcher with `runOnce()` (scheduler disabled in `AbstractIntegrationTest`) rather than sleeping.
6. Existing examples: `TokenBucketTest` (16 threads vs burst), `DispatchIntegrationTest` (4 dispatchers x 300 notifications).
