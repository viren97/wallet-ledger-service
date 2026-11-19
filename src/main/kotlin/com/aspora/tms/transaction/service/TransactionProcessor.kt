package com.aspora.tms.transaction.service

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.queue.MessageConsumer
import com.aspora.tms.queue.QueueMessage
import com.aspora.tms.queue.Topics
import com.aspora.tms.wallet.WalletLockManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component

/**
 * Orchestrates one transaction: claim -> (lock wallets -> execute with optimistic retry) -> complete/fail.
 *
 * The retry loop wraps the @Transactional call from the outside, so every attempt gets a fresh
 * DB transaction with freshly read wallet versions.
 */
@Component
class TransactionProcessor(
    private val executor: TransactionExecutor,
    private val locks: WalletLockManager,
    @Value("\${tms.processing.max-lock-retries:3}") private val maxRetries: Int,
    @Value("\${tms.processing.lock-retry-backoff-ms:25}") private val backoffMs: Long,
) : MessageConsumer {
    private val log = LoggerFactory.getLogger(javaClass)

    override val topic = Topics.TRANSACTION_PROCESSING

    override fun consume(message: QueueMessage) = process(message.key)

    fun process(transactionId: String) {
        val txn = try {
            executor.claim(transactionId)
        } catch (e: OptimisticLockingFailureException) {
            null // another worker claimed it concurrently
        } ?: return

        try {
            locks.withLocks(txn.walletIds) { withOptimisticRetry { executor.execute(transactionId) } }
        } catch (e: TmsException) {
            markFailed(transactionId, e.code, e.message)
        } catch (e: OptimisticLockingFailureException) {
            markFailed(transactionId, ErrorCode.CONCURRENCY_CONFLICT, "Could not apply transaction due to concurrent updates")
        } catch (e: Exception) {
            // The money movement is a single DB transaction that rolled back, so failing is safe.
            log.error("Unexpected error processing transaction {}", transactionId, e)
            markFailed(transactionId, ErrorCode.INTERNAL_ERROR, "Internal processing error")
        }
    }

    private fun markFailed(transactionId: String, code: ErrorCode, reason: String) {
        log.info("Transaction {} failed: {} - {}", transactionId, code, reason)
        executor.fail(transactionId, code, reason)
    }

    private fun <T> withOptimisticRetry(action: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return action()
            } catch (e: OptimisticLockingFailureException) {
                if (++attempt >= maxRetries) throw e
                Thread.sleep(backoffMs * attempt)
            }
        }
    }
}
