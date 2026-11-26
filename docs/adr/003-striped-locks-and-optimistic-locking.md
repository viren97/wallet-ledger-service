# ADR-003: Striped in-JVM wallet locks plus @Version optimistic locking

**Status:** Accepted

## Context

Transfers A → B and B → A can run at the same time on different workers and instances. Lost updates would
create or destroy money. Naive pessimistic locking risks deadlocks when two transfers lock the same pair of
wallets in opposite order.

## Options

| Option | Trade-off |
|---|---|
| `SELECT … FOR UPDATE` on both wallets | Correct if lock order is enforced everywhere; holds DB connections while waiting; deadlock-prone if the order slips |
| Optimistic locking only | Correct, but under contention most attempts fail and retry, which wastes work |
| Conditional `UPDATE … WHERE balance >= amount` | Cheapest on hot rows, but moves the bucket rules out of the domain model |
| Distributed lock (Redis) | Extra dependency and failure mode |
| **Striped in-JVM locks (sorted) + `@Version`** | Serialises a hot wallet |

## Decision

- `WalletLockManager` maps wallet ids onto 1024 `ReentrantLock` stripes and always acquires them in ascending
  stripe order, so opposite transfers can't deadlock. This removes contention inside one instance.
- `@Version` on `Wallet` (and every entity) is the source of truth across instances. `TransactionProcessor` retries
  the `@Transactional` `execute()` from outside, so each attempt reads fresh versions.
- The balance check is a domain method on the locked, loaded entity (`Wallet.debit`), which keeps bucket rules
  and the system-wallet overdraft rule in one place.

## Consequences

- ✅ No deadlocks, no lost updates. 200 concurrent opposite transfers end with exact balances and a ledger that nets to zero.
- ✅ Fast on one instance, correct on many.
- ⚠️ A hot wallet (such as `BANK_SETTLEMENT`, touched by every deposit) serialises. The planned fix is sub-wallet
  sharding or asynchronous posting ([roadmap](../ROADMAP.md)).
