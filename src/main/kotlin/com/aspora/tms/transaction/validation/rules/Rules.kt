package com.aspora.tms.transaction.validation.rules

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.transaction.validation.RuleParams
import com.aspora.tms.transaction.validation.ValidationContext
import com.aspora.tms.transaction.validation.ValidationRule
import org.springframework.stereotype.Component

@Component
class WalletActiveRule : ValidationRule {
    override val key = "wallet-active"
    override val order = 10

    override fun validate(context: ValidationContext, params: RuleParams) {
        listOf(context.source, context.destination).firstOrNull { !it.isActive }?.let {
            throw TmsException(ErrorCode.WALLET_INACTIVE, "Wallet ${it.id} is ${it.status}")
        }
    }
}

@Component
class DistinctWalletsRule : ValidationRule {
    override val key = "distinct-wallets"
    override val order = 20

    override fun validate(context: ValidationContext, params: RuleParams) {
        if (context.source.id == context.destination.id) {
            throw TmsException(ErrorCode.SAME_WALLET, "Source and destination wallet must be different")
        }
    }
}

@Component
class CurrencyMatchRule : ValidationRule {
    override val key = "currency-match"
    override val order = 30

    override fun validate(context: ValidationContext, params: RuleParams) {
        val currency = context.transaction.currency
        if (context.source.currency != currency || context.destination.currency != currency) {
            throw TmsException(ErrorCode.CURRENCY_MISMATCH, "Both wallets must hold $currency")
        }
    }
}

/** Params: `min`, `max` (minor units, inclusive). */
@Component
class AmountLimitRule : ValidationRule {
    override val key = "amount-limit"
    override val order = 40

    override fun checkConfig(params: RuleParams) {
        check(params.long("min") in 1..params.long("max")) { "amount-limit requires 0 < min <= max, got $params" }
    }

    override fun validate(context: ValidationContext, params: RuleParams) {
        val amount = context.transaction.amount
        val min = params.long("min")
        val max = params.long("max")
        if (amount !in min..max) {
            throw TmsException(
                ErrorCode.LIMIT_VIOLATION,
                "Amount $amount is outside the allowed range [$min, $max] for ${context.transaction.type}",
            )
        }
    }
}

/** Uses the handler's allocation strategy, so it knows e.g. that a TRANSFER cannot spend promo balance. */
@Component
class SufficientBalanceRule : ValidationRule {
    override val key = "sufficient-balance"
    override val order = 100

    override fun validate(context: ValidationContext, params: RuleParams) {
        if (context.source.isSystem) return
        val available = context.handler.debitAllocation.available(context.source)
        if (available < context.transaction.amount) {
            throw TmsException(
                ErrorCode.INSUFFICIENT_BALANCE,
                "Insufficient balance: available $available, required ${context.transaction.amount}",
            )
        }
    }
}
