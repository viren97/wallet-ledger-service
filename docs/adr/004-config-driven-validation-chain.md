# ADR-004: A validation chain configured in YAML, validated at startup

**Status:** Accepted

## Context

Business rules change more often than code: amount limits per transaction type, which checks apply to deposits,
new rules for compliance. Hard-coding them in each handler spreads the rules around and makes every limit
change a deploy of new logic.

## Options

| Option | Trade-off |
|---|---|
| `if` checks inside each handler | Simple, but duplicated and hard to audit |
| Rules engine (Drools, etc.) | Powerful, but heavy for a handful of rules and opaque to reviewers |
| **Rule beans + a YAML chain per type** | Rules still need code, and config must be validated |

## Decision

Each rule is a `ValidationRule` bean with a key (`wallet-active`, `amount-limit`, `sufficient-balance`, …).
`application.yml` declares `defaults` for every type and `overrides.<TYPE>` that disable rules or merge params,
for example "deposits skip `sufficient-balance`, withdrawals have their own limits". `TransactionValidator` builds one
chain per type at startup:

- cheap rules run before balance checks;
- an unknown rule key or invalid params **fail application startup**, not the first transaction;
- the effective chains are logged and exposed at `GET /api/v1/admin/validation-rules`.

## Consequences

- ✅ A limit change is a config change, reviewed like code; a typo can't reach production silently.
- ✅ Each rule is unit-tested once, and `TransactionValidatorTest` covers merging, disabling, ordering and fail-fast.
- ⚠️ Runtime changes need a restart. A DB-backed rule store with an admin API is on the roadmap.
