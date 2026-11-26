# ADR-005: Cache only terminal transaction statuses

**Status:** Accepted

## Context

With asynchronous processing ([ADR-002](002-async-processing-with-outbox.md)), clients poll
`GET /transactions/{id}`, so reads far outnumber writes. Caching helps, but cache invalidation on a money
status is risky: a stale `PENDING` shown after `COMPLETED` causes duplicate retries and support tickets.

## Options

| Option | Trade-off |
|---|---|
| No cache | Every poll hits Postgres |
| Cache everything, invalidate on change | Invalidation bugs mean stale money statuses |
| Short TTL for everything | Still stale within the TTL |
| **Cache only `COMPLETED` / `FAILED`** | In-flight polls still hit the DB |

## Decision

`RedisTransactionStatusCache.putIfTerminal` stores a response only when its status is terminal. Terminal states
never change again (the state machine rejects every transition out of them), so there is nothing to invalidate.
In-flight transactions always read from the database, which also gives read-your-writes right after submit. The
cache **fails open**: if Redis errors, the read falls through to Postgres.

## Consequences

- ✅ No invalidation logic, and no stale status is possible by construction.
- ✅ The bulk of polling traffic (repeat reads of finished transactions) moves off the database.
- ⚠️ Polls for in-flight transactions still reach Postgres; they are short-lived and indexed by primary key.
