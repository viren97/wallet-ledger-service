# Architecture decision records

Short records of the decisions that shape this service: the context, the options considered, what was chosen,
and what it costs.

| ADR | Decision | Status |
|---|---|---|
| [001](001-double-entry-ledger.md) | Stored balances plus an append-only double-entry ledger, written in one transaction | Accepted |
| [002](002-async-processing-with-outbox.md) | Accept with `202`, process asynchronously, hand off through a transactional outbox | Accepted |
| [003](003-striped-locks-and-optimistic-locking.md) | Striped in-JVM wallet locks plus `@Version` optimistic locking | Accepted |
| [004](004-config-driven-validation-chain.md) | A validation chain configured in YAML, validated at startup | Accepted |
| [005](005-cache-only-terminal-statuses.md) | Cache only terminal transaction statuses | Accepted |
