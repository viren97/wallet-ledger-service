package com.aspora.tms.transaction

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import com.aspora.tms.transaction.domain.Transaction
import com.aspora.tms.transaction.domain.TransactionType
import com.aspora.tms.transaction.handler.BalanceAllocations
import com.aspora.tms.transaction.handler.Parties
import com.aspora.tms.transaction.handler.TransactionHandler
import com.aspora.tms.transaction.handler.TransactionRequest
import com.aspora.tms.transaction.validation.RuleSettings
import com.aspora.tms.transaction.validation.TransactionValidator
import com.aspora.tms.transaction.validation.ValidationContext
import com.aspora.tms.transaction.validation.ValidationProperties
import com.aspora.tms.transaction.validation.rules.AmountLimitRule
import com.aspora.tms.transaction.validation.rules.CurrencyMatchRule
import com.aspora.tms.transaction.validation.rules.DistinctWalletsRule
import com.aspora.tms.transaction.validation.rules.SufficientBalanceRule
import com.aspora.tms.transaction.validation.rules.WalletActiveRule
import com.aspora.tms.wallet.BalanceBucket
import com.aspora.tms.wallet.Wallet
import com.aspora.tms.wallet.WalletStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TransactionValidatorTest {

    private val rules = listOf(WalletActiveRule(), DistinctWalletsRule(), CurrencyMatchRule(), AmountLimitRule(), SufficientBalanceRule())

    private val properties = ValidationProperties(
        defaults = mapOf(
            "wallet-active" to RuleSettings(),
            "distinct-wallets" to RuleSettings(),
            "currency-match" to RuleSettings(),
            "amount-limit" to RuleSettings(params = mapOf("min" to "100", "max" to "10000")),
            "sufficient-balance" to RuleSettings(),
        ),
        overrides = mapOf(
            TransactionType.WITHDRAWAL to mapOf("amount-limit" to RuleSettings(params = mapOf("max" to "500"))),
            TransactionType.DEPOSIT to mapOf("sufficient-balance" to RuleSettings(enabled = false)),
        ),
    )

    private val validator = TransactionValidator(rules, properties)

    private val source = Wallet.forUser("u1", "INR").apply { credit(BalanceBucket.WITHDRAWABLE, 1_000) }
    private val destination = Wallet.forUser("u2", "INR")

    private fun context(type: TransactionType, amount: Long, from: Wallet = source, to: Wallet = destination) =
        ValidationContext(Transaction("k", "h", type, from.id, to.id, amount, "INR"), from, to, handler(type))

    private fun handler(type: TransactionType) = object : TransactionHandler {
        override val type = type
        override val debitAllocation = BalanceAllocations.WITHDRAWABLE_ONLY
        override fun resolveParties(request: TransactionRequest) = Parties("", "")
    }

    @Test
    fun `valid transfer passes the whole chain`() {
        assertThatCode { validator.validate(context(TransactionType.TRANSFER, 500)) }.doesNotThrowAnyException()
    }

    @Test
    fun `type override merges params over defaults`() {
        assertThatCode { validator.validate(context(TransactionType.TRANSFER, 800)) }.doesNotThrowAnyException()
        assertFailsWith(ErrorCode.LIMIT_VIOLATION) { validator.validate(context(TransactionType.WITHDRAWAL, 800)) }
    }

    @Test
    fun `type override can disable a rule`() {
        assertFailsWith(ErrorCode.INSUFFICIENT_BALANCE) { validator.validate(context(TransactionType.TRANSFER, 5_000)) }
        assertThatCode { validator.validate(context(TransactionType.DEPOSIT, 5_000)) }.doesNotThrowAnyException()
    }

    @Test
    fun `cheap rules run before balance checks`() {
        val frozen = Wallet.forUser("u3", "INR").apply { status = WalletStatus.FROZEN }
        assertFailsWith(ErrorCode.WALLET_INACTIVE) { validator.validate(context(TransactionType.TRANSFER, 5_000, from = frozen)) }
    }

    @Test
    fun `unknown rule in config fails at startup`() {
        val bad = ValidationProperties(defaults = mapOf("no-such-rule" to RuleSettings()))
        assertThatThrownBy { TransactionValidator(rules, bad) }.hasMessageContaining("no-such-rule")
    }

    @Test
    fun `invalid rule params fail at startup`() {
        val bad = ValidationProperties(defaults = mapOf("amount-limit" to RuleSettings(params = mapOf("min" to "10"))))
        assertThatThrownBy { TransactionValidator(rules, bad) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `chains are resolved per type`() {
        assertThat(validator.describe().getValue(TransactionType.DEPOSIT).map { it["rule"] })
            .containsExactly("wallet-active", "distinct-wallets", "currency-match", "amount-limit")
    }

    private fun assertFailsWith(code: ErrorCode, action: () -> Unit) {
        assertThatThrownBy(action).isInstanceOf(TmsException::class.java).extracting("code").isEqualTo(code)
    }
}
