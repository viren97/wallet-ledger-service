package dev.viren.tms.transaction.handler

import dev.viren.tms.common.error.TmsException
import dev.viren.tms.transaction.domain.TransactionType
import dev.viren.tms.wallet.BalanceBucket
import java.security.MessageDigest

/** Incoming request to move money, independent of the transport (REST, webhook, poller). */
data class TransactionRequest(
    val type: TransactionType,
    val sourceWalletId: String?,
    val destinationWalletId: String?,
    val amount: Long,
    val currency: String,
    val idempotencyKey: String,
    val externalReference: String? = null,
) {
    /** Hash of the business payload, used to detect an idempotency key being reused for a different request. */
    fun fingerprint(): String {
        val raw = listOf(type, sourceWalletId, destinationWalletId, amount, currency, externalReference).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class Parties(val sourceWalletId: String, val destinationWalletId: String)

/**
 * Strategy per transaction type. A handler only *declares* who pays, who receives and from which
 * bucket; the generic money movement (debit, credit, ledger, state) lives in TransactionExecutor.
 * Adding a new type = one new @Component implementing this interface.
 */
interface TransactionHandler {
    val type: TransactionType

    /** Bucket(s) to debit from the source wallet. */
    val debitAllocation: BalanceAllocationStrategy

    /** Bucket to credit on the destination wallet. */
    val creditBucket: BalanceBucket get() = BalanceBucket.WITHDRAWABLE

    fun resolveParties(request: TransactionRequest): Parties
}

internal fun required(value: String?, field: String, type: TransactionType): String =
    value?.takeIf { it.isNotBlank() } ?: throw TmsException.invalid("'$field' is required for $type")
