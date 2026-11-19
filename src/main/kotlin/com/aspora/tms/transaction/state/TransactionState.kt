package com.aspora.tms.transaction.state

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.transaction.domain.Transaction
import com.aspora.tms.transaction.domain.TransactionStatus
import java.time.Instant

/**
 * State pattern for the transaction lifecycle:
 *
 *   PENDING ──startProcessing──▶ PROCESSING ──complete──▶ COMPLETED
 *      │                             │
 *      └──────────fail───────────────┴────────fail──────▶ FAILED
 *
 * Every state only overrides the transitions it allows; anything else is rejected.
 * Adding a state (e.g. AWAITING_BANK, REVERSED) = one new object + a line in [of].
 */
sealed class TransactionState(val status: TransactionStatus) {

    open fun startProcessing(txn: Transaction): Unit = reject(txn, TransactionStatus.PROCESSING)
    open fun complete(txn: Transaction): Unit = reject(txn, TransactionStatus.COMPLETED)
    open fun fail(txn: Transaction, code: ErrorCode, reason: String): Unit = reject(txn, TransactionStatus.FAILED)

    protected fun moveTo(txn: Transaction, target: TransactionStatus) {
        txn.status = target
        txn.updatedAt = Instant.now()
    }

    protected fun moveToFailed(txn: Transaction, code: ErrorCode, reason: String) {
        txn.failureCode = code.name
        txn.failureReason = reason
        moveTo(txn, TransactionStatus.FAILED)
    }

    private fun reject(txn: Transaction, target: TransactionStatus): Nothing =
        throw TmsException(ErrorCode.ILLEGAL_STATE_TRANSITION, "Transaction ${txn.id} cannot move from $status to $target")

    data object Pending : TransactionState(TransactionStatus.PENDING) {
        override fun startProcessing(txn: Transaction) = moveTo(txn, TransactionStatus.PROCESSING)
        override fun fail(txn: Transaction, code: ErrorCode, reason: String) = moveToFailed(txn, code, reason)
    }

    data object Processing : TransactionState(TransactionStatus.PROCESSING) {
        override fun complete(txn: Transaction) = moveTo(txn, TransactionStatus.COMPLETED)
        override fun fail(txn: Transaction, code: ErrorCode, reason: String) = moveToFailed(txn, code, reason)
    }

    data object Completed : TransactionState(TransactionStatus.COMPLETED)

    data object Failed : TransactionState(TransactionStatus.FAILED)

    companion object {
        fun of(status: TransactionStatus): TransactionState = when (status) {
            TransactionStatus.PENDING -> Pending
            TransactionStatus.PROCESSING -> Processing
            TransactionStatus.COMPLETED -> Completed
            TransactionStatus.FAILED -> Failed
        }
    }
}
