package com.aspora.tms.transaction.domain

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.persistence.BaseEntity
import com.aspora.tms.transaction.state.TransactionState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class TransactionType { TRANSFER, DEPOSIT, WITHDRAWAL, VENDOR_PAYMENT }

enum class TransactionStatus {
    PENDING, PROCESSING, COMPLETED, FAILED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
}

@Entity
@Table(name = "transactions", indexes = [Index(columnList = "status")])
class Transaction(
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false) val idempotencyKey: String,
    @Column(nullable = false, updatable = false) val requestHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val type: TransactionType,
    @Column(nullable = false, updatable = false) val sourceWalletId: String,
    @Column(nullable = false, updatable = false) val destinationWalletId: String,
    @Column(nullable = false, updatable = false) val amount: Long,
    @Column(nullable = false, length = 3, updatable = false) val currency: String,
    /** Reference from an external system (e.g. bank UTR). Unique so a bank credit is applied once. */
    @Column(name = "external_reference", unique = true, updatable = false) val externalReference: String? = null,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TransactionStatus = TransactionStatus.PENDING
        internal set

    var failureCode: String? = null
        internal set

    var failureReason: String? = null
        internal set

    @Column(nullable = false)
    var updatedAt: Instant = createdAt
        internal set

    val walletIds: List<String> get() = listOf(sourceWalletId, destinationWalletId)

    // Lifecycle transitions are delegated to the State pattern; the entity never sets status directly.
    fun startProcessing() = TransactionState.of(status).startProcessing(this)
    fun complete() = TransactionState.of(status).complete(this)
    fun fail(code: ErrorCode, reason: String) = TransactionState.of(status).fail(this, code, reason)
}
