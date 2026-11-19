package com.aspora.tms.event

import com.aspora.tms.transaction.domain.Transaction
import com.aspora.tms.transaction.domain.TransactionStatus
import com.aspora.tms.transaction.domain.TransactionType
import java.time.Instant

/** Public event contract published to upstream services once a transaction reaches a terminal state. */
data class TransactionEvent(
    val transactionId: String,
    val type: TransactionType,
    val status: TransactionStatus,
    val amount: Long,
    val currency: String,
    val sourceWalletId: String,
    val destinationWalletId: String,
    val failureCode: String?,
    val failureReason: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(txn: Transaction) = TransactionEvent(
            txn.id, txn.type, txn.status, txn.amount, txn.currency,
            txn.sourceWalletId, txn.destinationWalletId, txn.failureCode, txn.failureReason, txn.updatedAt,
        )
    }
}

/**
 * Anything that wants to react to completed/failed transactions (notifications, rewards, analytics,
 * upstream webhooks). Register a bean; the fan-out picks it up automatically.
 */
interface EventSubscriber {
    val name: String
    fun onEvent(event: TransactionEvent)
}
