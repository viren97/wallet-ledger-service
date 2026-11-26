package dev.viren.tms.transaction.service

import dev.viren.tms.common.error.ErrorCode
import dev.viren.tms.common.error.TmsException
import dev.viren.tms.event.TransactionEvent
import dev.viren.tms.ledger.EntryDirection
import dev.viren.tms.ledger.LedgerService
import dev.viren.tms.outbox.OutboxEventType
import dev.viren.tms.outbox.OutboxService
import dev.viren.tms.transaction.domain.Transaction
import dev.viren.tms.transaction.domain.TransactionRepository
import dev.viren.tms.transaction.domain.TransactionStatus
import dev.viren.tms.transaction.handler.TransactionHandlerRegistry
import dev.viren.tms.transaction.validation.TransactionValidator
import dev.viren.tms.transaction.validation.ValidationContext
import dev.viren.tms.wallet.WalletRepository
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${tms.processing.max-attempts:5}") private val maxAttempts: Int,
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

    /**
     * Gives a transaction another go after a transient failure (DB hiccup, lock conflict, crash after claim):
     * PROCESSING -> PENDING plus a fresh PROCESS_TRANSACTION outbox row, in one DB transaction.
     * A PENDING transaction whose queue message was lost (e.g. in-memory queue after a restart) is simply
     * re-enqueued. After [maxAttempts] claims it is failed instead, so a poison transaction can't loop forever.
     * Duplicate commands are harmless: [claim] only lets one worker through.
     *
     * @return true if another attempt was scheduled
     */
    @Transactional
    fun requeue(transactionId: String, reason: String): Boolean {
        val txn = load(transactionId)
        when (txn.status) {
            TransactionStatus.PENDING -> txn.touch()
            TransactionStatus.PROCESSING -> {
                if (txn.processingAttempts >= maxAttempts) {
                    txn.fail(ErrorCode.INTERNAL_ERROR, "Gave up after ${txn.processingAttempts} attempts: $reason")
                    outbox.record(OutboxEventType.TRANSACTION_FAILED, txn.id, TransactionEvent.from(txn))
                    return false
                }
                txn.requeue()
            }
            else -> return false
        }
        outbox.record(OutboxEventType.PROCESS_TRANSACTION, txn.id, ProcessTransactionCommand(txn.id))
        return true
    }

    private fun load(id: String) = transactions.findByIdOrNull(id) ?: throw TmsException.notFound("Transaction", id)
}
