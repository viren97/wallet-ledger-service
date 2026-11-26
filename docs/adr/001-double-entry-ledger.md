# ADR-001: Stored balances plus an append-only double-entry ledger

**Status:** Accepted

## Context

Wallet balances must be read fast (every transfer checks them) and must be provably correct: auditors,
support and reconciliation with partners all need to know *why* a balance is what it is. A balance that drifts
from its history even once is a financial incident.

## Options

| Option | Trade-off |
|---|---|
| Balance column only | Fast, but no audit trail; drift is undetectable |
| Ledger only, balance computed on read | Always consistent, but every read is O(entries) and hot wallets get slow |
| Stored balance + ledger, written separately | Fast reads, but the two can diverge on a crash between writes |
| **Stored balance + ledger, written in the same DB transaction** | Slightly more write volume |

## Decision

Every money movement writes, in **one** DB transaction (`TransactionExecutor.execute`):

- the debit and credit on the wallet balance buckets (`Wallet.debit` / `credit`, which enforce invariants);
- one `DEBIT` and one `CREDIT` `LedgerEntry` per bucket, each with `balanceAfter`;
- the transaction's `COMPLETED` status and its outbox event.

`LedgerService.record` uses `Propagation.MANDATORY`, so a ledger write outside a balance-changing transaction
fails immediately. Money is a `Long` in minor units (paise/cents), never floating point. Arithmetic uses
`Math.addExact` to catch overflow.

`GET /wallets/{id}/reconciliation` re-derives every bucket from the ledger and compares it to the stored balance.

## Consequences

- ✅ O(1) balance reads; a full, append-only audit trail.
- ✅ Double entry is checkable: across all wallets (system wallets included) the ledger nets to zero, and the
  200-thread concurrency test asserts exactly that.
- ⚠️ Every movement writes at least four rows. That is acceptable at this scale; partitioning the ledger by time
  is the path for later.
