package com.aspora.tms.transaction.service

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.transaction.domain.Transaction
import com.aspora.tms.transaction.domain.TransactionRepository
import com.aspora.tms.transaction.handler.TransactionRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class SubmissionResult(val transaction: Transaction, val replayed: Boolean)

/**
 * Entry point for creating transactions, idempotent on `idempotencyKey`:
 *  - same key + same payload  -> returns the original transaction (replayed = true)
 *  - same key + other payload -> 422 IDEMPOTENCY_KEY_REUSED
 *
 * Deliberately NOT @Transactional: the unique-key violation from a concurrent duplicate surfaces
 * at commit of [TransactionWriter.create], and we need to catch it *outside* that transaction.
 */
@Service
class TransactionService(
    private val writer: TransactionWriter,
    private val transactions: TransactionRepository,
) {
    fun submit(request: TransactionRequest): SubmissionResult {
        val fingerprint = request.fingerprint()
        transactions.findByIdempotencyKey(request.idempotencyKey)?.let { return replay(it, fingerprint) }
        return try {
            SubmissionResult(writer.create(request, fingerprint), replayed = false)
        } catch (e: DataIntegrityViolationException) {
            val existing = transactions.findByIdempotencyKey(request.idempotencyKey) ?: throw e
            replay(existing, fingerprint)
        }
    }

    @Transactional(readOnly = true)
    fun get(id: String): Transaction = transactions.findByIdOrNull(id) ?: throw TmsException.notFound("Transaction", id)

    private fun replay(existing: Transaction, fingerprint: String): SubmissionResult {
        if (existing.requestHash != fingerprint) {
            throw TmsException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used with a different request")
        }
        return SubmissionResult(existing, replayed = true)
    }
}
