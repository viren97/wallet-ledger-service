package dev.viren.tms.integration.bank

import java.time.Instant

enum class BankStatus { SUCCESS, PENDING, FAILED }

data class PayoutRequest(val transactionId: String, val walletId: String, val amount: Long, val currency: String)

data class BankResponse(val status: BankStatus, val bankReference: String?, val message: String? = null)

data class BankDeposit(val bankReference: String, val walletId: String, val amount: Long, val currency: String, val creditedAt: Instant)

/**
 * Outbound port to the bank (not implemented in this iteration, see docs/ROADMAP.md).
 *
 * - push: [initiatePayout] for withdrawals
 * - pull: [fetchPayoutStatus] / [fetchDeposits] for reconciliation when a webhook is missed
 *
 * Implementations must map transport errors: timeouts -> PENDING (never assume failure for money),
 * 5xx -> retryable, 4xx -> FAILED.
 */
interface BankGateway {
    fun initiatePayout(request: PayoutRequest): BankResponse
    fun fetchPayoutStatus(bankReference: String): BankResponse
    fun fetchDeposits(since: Instant): List<BankDeposit>
}
