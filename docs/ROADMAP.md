# Roadmap: deferred scope

The first iteration covers the core ask: transfers, deposits, withdrawals and vendor payments;
config-driven validation; a state lifecycle; a double-entry ledger; concurrency control; the
outbox with async processing and fan-out; idempotent transaction APIs; and an inbound bank
deposit webhook. The items below were deliberately deferred. Where an interface already exists,
it is named.

## 1. Validation
- [ ] Store rule config in a DB table (`validation_rule_config`) seeded from YAML, with an admin API (`PUT /admin/validation-rules/{id}`) and cache eviction for runtime changes.
- [ ] Velocity rules: daily/monthly cumulative limits per wallet and type.
- [ ] KYC tier limits per user (needs a `kycTier` on `User`).
- [ ] Rule scoping by wallet type and user segment, not only by transaction type.
- [ ] Synchronous pre-check at submit time for instant 422s (limits, wallet existence), keeping the async check authoritative.

## 2. Bank integration (port: `integration/bank/BankGateway`)
| Direction | Push | Pull |
|---|---|---|
| Bank → us | ✅ deposit webhook · [ ] payout status callback | [ ] `GET /bank/v1/transactions/{externalRef}` so the bank can query status |
| Us → bank | [ ] `initiatePayout` for withdrawals | [ ] `BankReconciliationPoller` (`fetchDeposits`, `fetchPayoutStatus`) |

- [ ] HMAC signature verification + timestamp/replay window on inbound webhooks.
- [ ] `MockBankAdapter` with configurable failure modes for tests.
- [ ] Downstream error mapping: timeout → stay `AWAITING_BANK` (never assume failure), 5xx → retry with backoff, 4xx → `FAILED`.
- [ ] Circuit breaker and bulkhead around the bank client (resilience4j).

## 3. Withdrawal lifecycle with holds
- [ ] New states `AWAITING_BANK` and `REVERSED` (one `TransactionState` object each).
- [ ] `heldBalance` bucket: debit withdrawable into hold → bank payout → settle on success, or release on failure.
- [ ] Sweeper for transactions stuck in `PROCESSING` / `AWAITING_BANK` (crash between claim and execute).

## 4. Upstream fan-out (port: `event/EventSubscriber`)
- [ ] `POST /subscriptions {url, eventTypes, secret}` registry for upstream services.
- [ ] `HttpWebhookSubscriber` with one `WebhookDelivery` row per subscriber per event, HMAC-signed payloads, exponential backoff and a `DEAD` state with replay.
- [ ] Per-subscriber retry. Today a failing subscriber is logged and isolated, not retried.

## 5. Idempotency everywhere
- [ ] Generic `Idempotency-Key` interceptor for all POST/PUT endpoints: an `idempotency_records` table storing (key, scope, request hash, status, response), replay on match, `409` while the first request is in flight. Today it covers transactions and the bank webhook; user creation is protected by unique email.

## 6. More transaction types and wallet features
- [ ] `PROMO_CREDIT` (promo system wallet → user non-withdrawable), `REFUND`, `REVERSAL`, `FEE` (split postings to a `FEES` system wallet).
- [ ] Freeze/unfreeze wallet API (the `wallet-active` rule already enforces it).
- [ ] Multi-currency FX transfers.

## 7. Scale and operations
- [ ] Postgres: larger pool, `SELECT … FOR UPDATE` or optimistic-only, and `FOR UPDATE SKIP LOCKED` for multi-instance outbox relays.
- [ ] Kafka/SQS `TaskQueue` adapter, partitioned by source wallet id for per-wallet ordering.
- [ ] Hot system wallets (every deposit touches `BANK_SETTLEMENT`): shard into sub-wallets or post asynchronously to avoid lock contention.
- [ ] Outbox and ledger housekeeping: archive published events, balance snapshots so reconciliation doesn't scan the full ledger.
- [ ] Flyway migrations instead of `ddl-auto`.
- [ ] Auth (JWT) and ownership checks on wallet operations.
- [ ] Metrics (Micrometer): queue depth, outbox lag, processing latency, failures by code. Correlation ids in logs.
- [ ] OpenAPI/Swagger UI (springdoc).
- [ ] Pagination on ledger and transaction listing.

## 8. Tests
- [ ] Controller slice tests for error mapping (`@WebMvcTest`).
- [ ] Webhook replay and concurrent duplicate submission (same key from two threads).
- [ ] Bank adapter contract tests once the gateway is implemented.
