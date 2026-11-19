# Aspora TMS — Transaction Management System

A wallet-based transaction management service: transfers, cash deposits/withdrawals and vendor
payments with configurable validation, a state-machine lifecycle, a double-entry ledger, the
transactional outbox and async processing/fan-out over an in-memory queue.

**Stack:** Kotlin 2.0 · Spring Boot 3.5 · Spring Data JPA / Hibernate 6 · SQLite · Gradle 8.5 · Java 17

## Run

```bash
./gradlew bootRun          # starts on :8080, creates ./tms.db
./gradlew test             # unit + concurrent end-to-end tests
```

Requires JDK 17+. The DB file `tms.db` is created on first start; delete it to reset.
System wallets (`BANK_SETTLEMENT`, `VENDOR_PAYOUT` for INR and USD) are seeded on startup.

API reference with curl examples: [docs/API.md](docs/API.md). What's deferred: [docs/ROADMAP.md](docs/ROADMAP.md).

## Request flow

```
POST /transactions ─▶ TransactionService (idempotency check)
                      └▶ TransactionWriter  @Transactional ── INSERT transaction(PENDING)
                                                           └─ INSERT outbox(PROCESS_TRANSACTION)
                    ◀── 202 Accepted {status: PENDING}

OutboxRelay (@Scheduled) ─▶ TaskQueue[transaction.processing] ─▶ TransactionProcessor
   1. claim         @Transactional  PENDING → PROCESSING   (duplicate deliveries stop here)
   2. lock wallets  (striped locks, sorted order → no deadlock)
   3. execute       @Transactional  validate → debit/credit wallets → ledger rows
                                    → COMPLETED → outbox(TRANSACTION_COMPLETED)
      (retried on optimistic-lock conflict, each attempt a fresh DB transaction)
   4. on error      @Transactional  PROCESSING → FAILED(code, reason) → outbox(TRANSACTION_FAILED)

OutboxRelay ─▶ TaskQueue[transaction.events] ─▶ TransactionEventFanOut ─▶ every EventSubscriber
                                                                          (notifications, …)
```

Clients poll `GET /transactions/{id}` for the final status; upstream services get it pushed via subscribers.

## Design

| Concern | Approach | Where |
|---|---|---|
| Transaction types | **Strategy + registry**: a handler declares parties and which balance bucket to debit/credit; generic money movement lives in one executor. New type = one `@Component`. | `transaction/handler` |
| Validation | **Chain of responsibility, config-driven**: each rule is a bean with a key; `application.yml` enables/disables rules and sets params per transaction type (defaults + overrides). Unknown keys / bad params fail at boot. | `transaction/validation`, `tms.validation` |
| Lifecycle | **State pattern**: `PENDING → PROCESSING → COMPLETED / FAILED`; each state allows only its transitions. | `transaction/state` |
| Balances | Wallet has **withdrawable** (real money) and **non-withdrawable** (promo) buckets. `BalanceAllocationStrategy` decides which bucket pays: transfers/withdrawals use withdrawable only, vendor payments spend promo first. | `wallet`, `BalanceAllocationStrategy.kt` |
| System wallets | `BANK_SETTLEMENT` (counter-party for deposits/withdrawals) and `VENDOR_PAYOUT` (vendor payments). System wallets may go negative; user wallets never. | `SystemWalletRegistry` |
| Ledger (dual write) | Every movement writes DEBIT and CREDIT entries (with `balanceAfter`) in the **same DB transaction** as the balance update. Ledger nets to zero across all wallets; `/reconciliation` re-derives balances. | `ledger` |
| Concurrency | In-process striped locks acquired in sorted order + `@Version` optimistic locking with retry outside the transaction. | `WalletLockManager`, `TransactionProcessor` |
| Outbox | Business change + event committed atomically; relay publishes after commit (no I/O inside DB transactions). At-least-once → consumers are idempotent. | `outbox` |
| Async | `TaskQueue` port with bounded in-memory implementation, worker pool, retry with backoff, dead letters. | `queue` |
| Fan-out | Terminal events go to every `EventSubscriber`, each isolated. | `event`, `notification` |
| Idempotency | `Idempotency-Key` header (unique column + request hash): same request → replay (200), different payload → 422. Bank webhooks use the bank reference. | `TransactionService` |
| Errors | One `TmsException` + `ErrorCode` enum (carries HTTP status); one `@RestControllerAdvice`. Business failures during async processing become `FAILED` with `failureCode`. | `common/error` |
| Bank | `BankGateway` port (payout push, status/deposit pull) defined, not implemented. Inbound deposit webhook implemented. | `integration/bank` |

### `@Transactional` usage
- Only on service/component methods, never controllers.
- Separate beans where one method calls another transactional one (`TransactionService` → `TransactionWriter`,
  `TransactionProcessor` → `TransactionExecutor`, `SystemWalletBootstrap` → `SystemWalletRegistry`), so calls go through the proxy.
- `Propagation.MANDATORY` on `LedgerService.record` and `OutboxService.record`: they must join the caller's transaction.
- `readOnly = true` for queries.
- `TransactionService.submit` is deliberately non-transactional so it can catch the unique-key violation thrown at commit.
- Retries and queue publishing happen outside transactions.

### Money & data
- Amounts are `Long` **minor units** (paise/cents), never floating point.
- SQLite allows one writer, so the pool has a single connection (WAL + `busy_timeout`). For scale: Postgres + a larger pool + `SELECT … FOR UPDATE`.

## Configuration (`application.yml`)

```yaml
tms:
  validation:
    defaults:                       # applies to all types
      amount-limit: { params: { min: 100, max: 10000000 } }
      sufficient-balance: { enabled: true }
    overrides:
      WITHDRAWAL:
        amount-limit: { params: { min: 1000, max: 5000000 } }   # params merged over defaults
      DEPOSIT:
        sufficient-balance: { enabled: false }                   # rule switched off for a type
```
Effective chains are logged at startup and exposed at `GET /api/v1/admin/validation-rules`.

## Extending
- **New transaction type:** add an enum value + a `TransactionHandler` bean.
- **New validation rule:** add a `ValidationRule` bean and reference its key in `tms.validation`.
- **New notification channel / upstream consumer:** add a `NotificationChannel` / `EventSubscriber` bean.
- **Real broker:** implement `TaskQueue` (Kafka/SQS), no producer/consumer changes.

## Project layout
```
src/main/kotlin/com/aspora/tms
├── common/        error codes, exception advice, base entity
├── user/          users
├── wallet/        wallets, balance buckets, system wallets, wallet locks
├── ledger/        double-entry ledger, reconciliation
├── transaction/   domain, state, handler (strategies), validation, service, api
├── outbox/        outbox entity, service, relay
├── queue/         TaskQueue port + in-memory implementation
├── event/         transaction events + fan-out
├── notification/  channels + subscriber
└── integration/   BankGateway port
```

## Tests
- `TransactionStateTest`: allowed and illegal transitions.
- `WalletTest`: bucket invariants, system wallet overdraft.
- `BalanceAllocationTest`: promo-first vs withdrawable-only.
- `TransactionValidatorTest`: config merge, disabling per type, ordering, fail-fast on bad config.
- `TransactionFlowIntegrationTest` (real SQLite, full async path): 200 concurrent A↔B transfers keep balances
  correct and the ledger at zero, insufficient balance moves no money, idempotent replay/reuse, fan-out to subscribers.
