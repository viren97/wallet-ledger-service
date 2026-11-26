# ADR-002: Accept with 202, process asynchronously, hand off through a transactional outbox

**Status:** Accepted

## Context

A transfer involves validation, locking two wallets, and writing balances, the ledger and events. Doing that
inside the HTTP request ties client latency to lock contention and makes retries dangerous. Handing the work to a
queue introduces the *dual-write* problem: DB commit and broker publish can't be made atomic directly.

## Options

| Option | Problem |
|---|---|
| Process inside the request | Latency and throughput bound by the slowest step; a client timeout leaves the outcome unknown |
| Commit, then publish to Kafka | A crash in between loses the work; a publish before a rollback creates phantom work |
| Change data capture (Debezium) | Correct, but adds connector infrastructure |
| **Transactional outbox + relay** | One extra table and a poller; at-least-once delivery |

## Decision

`POST /transactions` writes the transaction (`PENDING`) and a `PROCESS_TRANSACTION` outbox row in one DB
transaction (`TransactionWriter.create`) and returns `202`. `OutboxRelay` publishes `NEW` rows, keyed by
transaction id, and marks them `PUBLISHED` only after the broker acks. `TransactionProcessor` consumes:

1. `claim()`: `PENDING → PROCESSING`. A duplicate delivery stops here.
2. Under wallet locks, `execute()`: validation, money movement, ledger, `COMPLETED` and the outbox event, all-or-nothing.
3. Business-rule failures → `FAILED` with a code. Transient errors → requeue, up to `max-attempts`.

`StuckTransactionSweeper` covers lost messages and crashed workers.

## Consequences

- ✅ No lost or phantom transactions. If Kafka is down, writes are still accepted.
- ✅ Throughput is isolated from slow steps; the same code runs on the in-memory queue or Kafka.
- ⚠️ Clients poll `GET /transactions/{id}` (or subscribe to events) to get the outcome.
- ⚠️ At-least-once delivery means every consumer must be idempotent. They are: claim checks plus `@Version`.
