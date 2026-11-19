package com.aspora.tms.ledger

import com.aspora.tms.common.persistence.BaseEntity
import com.aspora.tms.wallet.BalanceBucket
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

enum class EntryDirection { DEBIT, CREDIT }

/** Immutable, append-only double-entry record. Every transaction produces debits == credits. */
@Entity
@Table(
    name = "ledger_entries",
    indexes = [Index(columnList = "wallet_id"), Index(columnList = "transaction_id")],
)
class LedgerEntry(
    @Column(name = "transaction_id", nullable = false, updatable = false) val transactionId: String,
    @Column(name = "wallet_id", nullable = false, updatable = false) val walletId: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val direction: EntryDirection,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val bucket: BalanceBucket,
    @Column(nullable = false, updatable = false) val amount: Long,
    @Column(nullable = false, updatable = false) val balanceAfter: Long,
) : BaseEntity() {
    val signedAmount: Long get() = if (direction == EntryDirection.CREDIT) amount else -amount
}
