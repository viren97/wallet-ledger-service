# API Reference

Base URL: `http://localhost:8080/api/v1` · JSON in/out · amounts are **minor units** (`50000` = ₹500.00).

## Conventions

**Idempotency.** `POST /transactions` requires an `Idempotency-Key` header (1–100 chars).

| Situation | Response |
|---|---|
| New key | `202 Accepted`, transaction in `PENDING` |
| Same key, same body | `200 OK`, the original transaction (no new processing) |
| Same key, different body | `422 IDEMPOTENCY_KEY_REUSED` |

The bank deposit webhook is idempotent on `bankReference`. GETs are read-only.

**Async processing.** Transactions are accepted as `PENDING` and processed in the background. Poll
`GET /transactions/{id}` until `status` is `COMPLETED` or `FAILED`.

**Errors.** Every error has this shape:
```json
{ "code": "INSUFFICIENT_BALANCE", "message": "…", "details": [], "timestamp": "2026-11-19T13:20:42Z" }
```

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_REQUEST` | 400 | Bean validation, malformed JSON, missing header, unsupported currency |
| `RESOURCE_NOT_FOUND` | 404 | User / wallet / transaction not found |
| `DUPLICATE_RESOURCE` | 409 | e.g. email already registered |
| `CONCURRENCY_CONFLICT` | 409 | Concurrent update; retry |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Key reused with a different payload |
| `INTERNAL_ERROR` | 500 | Unexpected error (details logged, not exposed) |

Business rule failures (`INSUFFICIENT_BALANCE`, `LIMIT_VIOLATION`, `WALLET_INACTIVE`, `SAME_WALLET`,
`CURRENCY_MISMATCH`) happen during async processing. They appear as `failureCode` / `failureReason`
on a `FAILED` transaction, not as HTTP errors.

---

## Users

### `POST /users`
```bash
curl -X POST localhost:8080/api/v1/users -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@example.com"}'
```
`201` → `{ "id", "name", "email", "createdAt" }` · `409` if email exists.

### `GET /users/{id}`

## Wallets

### `POST /users/{userId}/wallets`
```bash
curl -X POST localhost:8080/api/v1/users/$USER_ID/wallets -H 'Content-Type: application/json' -d '{"currency":"INR"}'
```
`201` →
```json
{ "id": "…", "userId": "…", "type": "USER", "systemCode": null, "currency": "INR", "status": "ACTIVE",
  "withdrawableBalance": 0, "nonWithdrawableBalance": 0, "totalBalance": 0 }
```

| Endpoint | Description |
|---|---|
| `GET /users/{userId}/wallets` | A user's wallets |
| `GET /wallets/{id}` | Wallet with bucket balances |
| `GET /wallets/system` | System wallets (`BANK_SETTLEMENT`, `VENDOR_PAYOUT` per currency) |
| `GET /wallets/{id}/ledger` | Ledger entries: `transactionId, direction (DEBIT/CREDIT), bucket, amount, balanceAfter` |
| `GET /wallets/{id}/reconciliation` | Stored balance vs balance re-derived from the ledger, per bucket |

## Transactions

### `POST /transactions`
Headers: `Idempotency-Key` (required).

| Field | Required | Notes |
|---|---|---|
| `type` | yes | `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`, `VENDOR_PAYMENT` |
| `sourceWalletId` | TRANSFER, WITHDRAWAL, VENDOR_PAYMENT | Ignored for DEPOSIT (source is `BANK_SETTLEMENT`) |
| `destinationWalletId` | TRANSFER, DEPOSIT | WITHDRAWAL → `BANK_SETTLEMENT`, VENDOR_PAYMENT → `VENDOR_PAYOUT` |
| `amount` | yes | > 0, minor units; limits come from config |
| `currency` | yes | ISO-4217, must match both wallets |

How each type moves money:

| Type | Debit | Credit |
|---|---|---|
| `TRANSFER` | source, withdrawable only | destination, withdrawable |
| `DEPOSIT` | `BANK_SETTLEMENT` | destination, withdrawable |
| `WITHDRAWAL` | source, withdrawable only | `BANK_SETTLEMENT` |
| `VENDOR_PAYMENT` | source, non-withdrawable first, then withdrawable | `VENDOR_PAYOUT` |

```bash
curl -X POST localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 7f1c-transfer-001' \
  -d '{"type":"TRANSFER","sourceWalletId":"'$W1'","destinationWalletId":"'$W2'","amount":20000,"currency":"INR"}'
```
`202` →
```json
{ "id": "81ec…", "type": "TRANSFER", "status": "PENDING", "sourceWalletId": "…", "destinationWalletId": "…",
  "amount": 20000, "currency": "INR", "externalReference": null, "failureCode": null, "failureReason": null,
  "createdAt": "…", "updatedAt": "…" }
```

### `GET /transactions/{id}`
A failed transaction looks like this:
```json
{ "id": "218c…", "status": "FAILED", "failureCode": "INSUFFICIENT_BALANCE",
  "failureReason": "Insufficient balance: available 20000, required 90000", "…": "…" }
```

### `GET /admin/validation-rules`
Effective validation chain per transaction type, as resolved from configuration.

## Bank webhooks

### `POST /webhooks/bank/deposits`
Called by the bank when a cash/bank deposit is credited. Creates a `DEPOSIT` from `BANK_SETTLEMENT`.
```bash
curl -X POST localhost:8080/api/v1/webhooks/bank/deposits -H 'Content-Type: application/json' \
  -d '{"bankReference":"UTR123456","walletId":"'$W1'","amount":50000,"currency":"INR"}'
```
`202` on first delivery, `200` with the same transaction on a retry.

## End-to-end example
```bash
B=localhost:8080/api/v1; J='Content-Type: application/json'
U1=$(curl -s -XPOST $B/users -H "$J" -d '{"name":"Asha","email":"asha@x.com"}' | jq -r .id)
U2=$(curl -s -XPOST $B/users -H "$J" -d '{"name":"Ravi","email":"ravi@x.com"}' | jq -r .id)
W1=$(curl -s -XPOST $B/users/$U1/wallets -H "$J" -d '{"currency":"INR"}' | jq -r .id)
W2=$(curl -s -XPOST $B/users/$U2/wallets -H "$J" -d '{"currency":"INR"}' | jq -r .id)
curl -s -XPOST $B/webhooks/bank/deposits -H "$J" -d "{\"bankReference\":\"UTR1\",\"walletId\":\"$W1\",\"amount\":50000,\"currency\":\"INR\"}"
T=$(curl -s -XPOST $B/transactions -H "$J" -H 'Idempotency-Key: t-1' \
  -d "{\"type\":\"TRANSFER\",\"sourceWalletId\":\"$W1\",\"destinationWalletId\":\"$W2\",\"amount\":20000,\"currency\":\"INR\"}" | jq -r .id)
sleep 1; curl -s $B/transactions/$T | jq .status          # "COMPLETED"
curl -s $B/wallets/$W1/reconciliation | jq .consistent   # true
```
