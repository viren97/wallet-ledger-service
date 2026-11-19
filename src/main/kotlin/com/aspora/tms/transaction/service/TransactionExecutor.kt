package com.aspora.tms.transaction.service

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.event.TransactionEvent
import com.aspora.tms.ledger.EntryDirection
import com.aspora.tms.ledger.LedgerService
import com.aspora.tms.outbox.OutboxEventType
import com.aspora.tms.outbox.OutboxService
import com.aspora.tms.transaction.domain.Transaction
import com.aspora.tms.transaction.domain.TransactionRepository
import com.aspora.tms.transaction.domain.TransactionStatus
import com.aspora.tms.transaction.handler.TransactionHandlerRegistry
import com.aspora.tms.transaction.validation.TransactionValidator
import com.aspora.tms.transaction.validation.ValidationContext
import com.aspora.tms.wallet.WalletRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Each public method is one short DB transaction. Kept in its own bean so that TransactionProcessor
 * calls go through the Spring proxy (self-invocation would silently skip @Transactional).
 */
@Component
class TransactionExecutor(
    private val transactions: TransactionRepository,
    private val wallets: WalletRepository,
    private val handlers: TransactionHandlerRegistry,
    private val validator: TransactionValidator,
    private val ledger: LedgerService,
    private val outbox: OutboxService,
) {
    /** PENDING -> PROCESSING. Returns null if already picked up (duplicate delivery) so the caller skips it. */
    @Transactional
    fun claim(transactionId: String): Transaction? {
        val txn = transactions.findByIdOrNull(transactionId) ?: return null
        if (txn.status != TransactionStatus.PENDING) return null
        txn.startProcessing()
        return txn
    }

    /**
     * Validation + balance updates + ledger entries + COMPLETED + outbox event, all-or-nothing.
     * Any exception rolls everything back, so no money moves for a failed transaction.
     */
    @Transactional
    fun execute(transactionId: String) {
        val txn = load(transactionId)
        if (txn.status != TransactionStatus.PROCESSING) return
        val source = wallets.findByIdOrNull(txn.sourceWalletId) ?: throw TmsException.notFound("Wallet", txn.sourceWalletId)
        val destination = wallets.findByIdOrNull(txn.destinationWalletId) ?: throw TmsException.notFound("Wallet", txn.destinationWalletId)
        val handler = handlers.get(txn.type)

        validator.validate(ValidationContext(txn, source, destination, handler))

        handler.debitAllocation.allocate(source, txn.amount).forEach { (bucket, amount) ->
            source.debit(bucket, amount)
            ledger.record(txn.id, source, EntryDirection.DEBIT, bucket, amount)
        }
        destination.credit(handler.creditBucket, txn.amount)
        ledger.record(txn.id, destination, EntryDirection.CREDIT, handler.creditBucket, txn.amount)

        txn.complete()
        outbox.record(OutboxEventType.TRANSACTION_COMPLETED, txn.id, TransactionEvent.from(txn))
    }

    @Transactional
    fun fail(transactionId: String, code: ErrorCode, reason: String) {
        val txn = load(transactionId)
        if (txn.status.isTerminal) return
        txn.fail(code, reason)
        outbox.record(OutboxEventType.TRANSACTION_FAILED, txn.id, TransactionEvent.from(txn))
    }

    private fun load(id: String) = transactions.findByIdOrNull(id) ?: throw TmsException.notFound("Transaction", id)
}
