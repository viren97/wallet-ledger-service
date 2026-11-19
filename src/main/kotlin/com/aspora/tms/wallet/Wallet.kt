package com.aspora.tms.wallet

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

enum class WalletType { USER, SYSTEM }

enum class WalletStatus { ACTIVE, FROZEN }

/** Withdrawable = real money (can leave the system). Non-withdrawable = promo/cashback (spend-only). */
enum class BalanceBucket { WITHDRAWABLE, NON_WITHDRAWABLE }

/** Internal house wallets used as the counter-party for money entering/leaving the platform. */
enum class SystemWalletCode { BANK_SETTLEMENT, VENDOR_PAYOUT }

/**
 * A wallet is the "account" of the TMS. Balances are stored in minor units (paise/cents) as Long
 * to avoid floating point issues. All mutations go through [debit]/[credit] so invariants live here.
 */
@Entity
@Table(
    name = "wallets",
    uniqueConstraints = [UniqueConstraint(columnNames = ["system_code", "currency"])],
    indexes = [Index(columnList = "user_id")],
)
class Wallet(
    @Column(name = "user_id") val userId: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val type: WalletType,
    @Column(nullable = false, length = 3) val currency: String,
    @Enumerated(EnumType.STRING) @Column(name = "system_code") val systemCode: SystemWalletCode? = null,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: WalletStatus = WalletStatus.ACTIVE

    @Column(nullable = false)
    var withdrawableBalance: Long = 0
        protected set

    @Column(nullable = false)
    var nonWithdrawableBalance: Long = 0
        protected set

    val isSystem: Boolean get() = type == WalletType.SYSTEM
    val isActive: Boolean get() = status == WalletStatus.ACTIVE
    val totalBalance: Long get() = withdrawableBalance + nonWithdrawableBalance

    fun balanceOf(bucket: BalanceBucket): Long = when (bucket) {
        BalanceBucket.WITHDRAWABLE -> withdrawableBalance
        BalanceBucket.NON_WITHDRAWABLE -> nonWithdrawableBalance
    }

    /** User wallets can never go negative. System wallets can (they mirror money held outside, e.g. at the bank). */
    fun debit(bucket: BalanceBucket, amount: Long) {
        require(amount > 0) { "Debit amount must be positive" }
        val current = balanceOf(bucket)
        if (!isSystem && current < amount) {
            throw TmsException(ErrorCode.INSUFFICIENT_BALANCE, "Wallet $id has insufficient $bucket balance")
        }
        setBalance(bucket, current - amount)
    }

    fun credit(bucket: BalanceBucket, amount: Long) {
        require(amount > 0) { "Credit amount must be positive" }
        setBalance(bucket, Math.addExact(balanceOf(bucket), amount))
    }

    private fun setBalance(bucket: BalanceBucket, value: Long) = when (bucket) {
        BalanceBucket.WITHDRAWABLE -> withdrawableBalance = value
        BalanceBucket.NON_WITHDRAWABLE -> nonWithdrawableBalance = value
    }

    companion object {
        fun forUser(userId: String, currency: String) = Wallet(userId, WalletType.USER, currency)
        fun system(code: SystemWalletCode, currency: String) = Wallet(null, WalletType.SYSTEM, currency, code)
    }
}
