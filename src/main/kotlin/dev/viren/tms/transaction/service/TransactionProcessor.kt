package dev.viren.tms.transaction.service

import dev.viren.tms.common.error.ErrorCode
import dev.viren.tms.common.error.TmsException
import dev.viren.tms.queue.MessageConsumer
import dev.viren.tms.queue.QueueMessage
import dev.viren.tms.queue.Topics
import dev.viren.tms.wallet.WalletLockManager
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
            // Business rule (insufficient balance, limits, inactive wallet...): retrying won't change the answer.
            markFailed(transactionId, e.code, e.message)
        } catch (e: Exception) {
            // Transient (lock conflicts that outlasted the retries, DB hiccups) or a bug. The money movement is one
            // DB transaction that rolled back, so nothing moved: put it back to PENDING and try again later rather
            // than failing a legitimate transfer. requeue() fails it for good after tms.processing.max-attempts.
            log.warn("Transient error processing transaction {}, requeueing: {}", transactionId, e.toString())
            requeue(transactionId, e.toString())
        }
    }

    private fun requeue(transactionId: String, reason: String) {
        try {
            executor.requeue(transactionId, reason)
        } catch (e: Exception) {
            // Left in PROCESSING; StuckTransactionSweeper picks it up once it goes stale.
            log.error("Could not requeue transaction {}; the sweeper will retry it", transactionId, e)
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
