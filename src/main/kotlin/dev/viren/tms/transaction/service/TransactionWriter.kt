package dev.viren.tms.transaction.service

import dev.viren.tms.common.error.TmsException
import dev.viren.tms.outbox.OutboxEventType
import dev.viren.tms.outbox.OutboxService
import dev.viren.tms.transaction.domain.Transaction
import dev.viren.tms.transaction.domain.TransactionRepository
import dev.viren.tms.transaction.handler.TransactionHandlerRegistry
import dev.viren.tms.transaction.handler.TransactionRequest
import dev.viren.tms.wallet.WalletRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class ProcessTransactionCommand(val transactionId: String)

@Component
class TransactionWriter(
    private val handlers: TransactionHandlerRegistry,
    private val transactions: TransactionRepository,
    private val wallets: WalletRepository,
    private val outbox: OutboxService,
) {
    /** Transaction row (PENDING) + outbox task are written atomically: the outbox pattern. */
    @Transactional
    fun create(request: TransactionRequest, fingerprint: String): Transaction {
        val parties = handlers.get(request.type).resolveParties(request)
        listOf(parties.sourceWalletId, parties.destinationWalletId).forEach {
            if (!wallets.existsById(it)) throw TmsException.notFound("Wallet", it)
        }
        val txn = transactions.save(
            Transaction(
                idempotencyKey = request.idempotencyKey,
                requestHash = fingerprint,
                type = request.type,
                sourceWalletId = parties.sourceWalletId,
                destinationWalletId = parties.destinationWalletId,
                amount = request.amount,
                currency = request.currency,
                externalReference = request.externalReference,
            ),
        )
        outbox.record(OutboxEventType.PROCESS_TRANSACTION, txn.id, ProcessTransactionCommand(txn.id))
        return txn
    }
}
