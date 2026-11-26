package dev.viren.tms.transaction.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface TransactionRepository : JpaRepository<Transaction, String> {
    fun findByIdempotencyKey(idempotencyKey: String): Transaction?

    /** Non-terminal transactions untouched since [before]: candidates for the stuck-transaction sweeper. */
    fun findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status: TransactionStatus, before: Instant): List<Transaction>
}
