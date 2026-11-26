package dev.viren.tms.transaction.recovery

import dev.viren.tms.transaction.domain.TransactionRepository
import dev.viren.tms.transaction.domain.TransactionStatus
import dev.viren.tms.transaction.service.TransactionExecutor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Safety net for at-least-once processing. A transaction can get stranded when:
 *  - its queue message was lost (in-memory queue on restart, dead-lettered message) -> stuck in PENDING
 *  - the worker crashed after claim() committed but before execute() did           -> stuck in PROCESSING
 *
 * Anything non-terminal and untouched for `tms.sweeper.stale-after-ms` is requeued through the outbox.
 * Safe to run on several instances and against a slow-but-alive worker: the money movement commits only if
 * the transaction row is still at the version it read (@Version), so a requeued transaction is applied once.
 */
@Component
class StuckTransactionSweeper(
    private val transactions: TransactionRepository,
    private val executor: TransactionExecutor,
    @Value("\${tms.sweeper.stale-after-ms:60000}") private val staleAfterMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${tms.sweeper.interval-ms:30000}",
        initialDelayString = "\${tms.sweeper.interval-ms:30000}",
    )
    fun sweep(): Int {
        val cutoff = Instant.now().minusMillis(staleAfterMs)
        var requeued = 0
        for (status in listOf(TransactionStatus.PENDING, TransactionStatus.PROCESSING)) {
            transactions.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status, cutoff).forEach { txn ->
                runCatching { executor.requeue(txn.id, "stale in $status since ${txn.updatedAt}") }
                    .onSuccess { if (it) requeued++ }
                    .onFailure { log.warn("Sweeper could not requeue {}: {}", txn.id, it.toString()) }
            }
        }
        if (requeued > 0) log.warn("Sweeper requeued {} stale transaction(s)", requeued)
        return requeued
    }
}
