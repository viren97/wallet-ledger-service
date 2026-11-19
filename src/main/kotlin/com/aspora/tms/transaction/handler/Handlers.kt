package com.aspora.tms.transaction.handler

import com.aspora.tms.transaction.domain.TransactionType
import com.aspora.tms.wallet.SystemWalletCode
import com.aspora.tms.wallet.SystemWalletRegistry
import org.springframework.stereotype.Component

/** Wallet to wallet. Only withdrawable money can be sent to another user (promo money is spend-only). */
@Component
class TransferHandler : TransactionHandler {
    override val type = TransactionType.TRANSFER
    override val debitAllocation = BalanceAllocations.WITHDRAWABLE_ONLY

    override fun resolveParties(request: TransactionRequest) = Parties(
        required(request.sourceWalletId, "sourceWalletId", type),
        required(request.destinationWalletId, "destinationWalletId", type),
    )
}

/** Cash/bank deposit: money enters the platform from the bank settlement system wallet. */
@Component
class DepositHandler(private val systemWallets: SystemWalletRegistry) : TransactionHandler {
    override val type = TransactionType.DEPOSIT
    override val debitAllocation = BalanceAllocations.WITHDRAWABLE_ONLY

    override fun resolveParties(request: TransactionRequest) = Parties(
        systemWallets.walletId(SystemWalletCode.BANK_SETTLEMENT, request.currency),
        required(request.destinationWalletId, "destinationWalletId", type),
    )
}

/** Cash withdrawal: only withdrawable money can leave to the bank settlement system wallet. */
@Component
class WithdrawalHandler(private val systemWallets: SystemWalletRegistry) : TransactionHandler {
    override val type = TransactionType.WITHDRAWAL
    override val debitAllocation = BalanceAllocations.WITHDRAWABLE_ONLY

    override fun resolveParties(request: TransactionRequest) = Parties(
        required(request.sourceWalletId, "sourceWalletId", type),
        systemWallets.walletId(SystemWalletCode.BANK_SETTLEMENT, request.currency),
    )
}

/** Paying a vendor: promo (non-withdrawable) balance is spent first, then withdrawable. */
@Component
class VendorPaymentHandler(private val systemWallets: SystemWalletRegistry) : TransactionHandler {
    override val type = TransactionType.VENDOR_PAYMENT
    override val debitAllocation = BalanceAllocations.PROMO_FIRST

    override fun resolveParties(request: TransactionRequest) = Parties(
        required(request.sourceWalletId, "sourceWalletId", type),
        systemWallets.walletId(SystemWalletCode.VENDOR_PAYOUT, request.currency),
    )
}
