package com.aspora.tms.transaction.validation

import com.aspora.tms.transaction.domain.Transaction
import com.aspora.tms.transaction.handler.TransactionHandler
import com.aspora.tms.wallet.Wallet

data class ValidationContext(
    val transaction: Transaction,
    val source: Wallet,
    val destination: Wallet,
    val handler: TransactionHandler,
)

/** Typed access to the `params` block of a rule in application.yml. */
class RuleParams(private val values: Map<String, String>) {
    fun long(name: String): Long =
        values[name]?.toLongOrNull() ?: throw IllegalStateException("Validation param '$name' is missing or not a number")

    override fun toString() = values.toString()
}

/**
 * One validation rule = one bean. Rules are switched on/off and parameterised per transaction
 * type from configuration (see TransactionValidator), so adding a rule never touches existing code.
 */
interface ValidationRule {
    /** Key used in `tms.validation.*` configuration. */
    val key: String

    /** Lower runs first: cheap structural checks before balance checks. */
    val order: Int

    /** Throw a TmsException to reject the transaction. */
    fun validate(context: ValidationContext, params: RuleParams)

    /** Called once at startup so misconfiguration fails the boot, not a live transaction. */
    fun checkConfig(params: RuleParams) {}
}
