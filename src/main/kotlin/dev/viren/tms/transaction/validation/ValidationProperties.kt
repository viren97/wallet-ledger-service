package dev.viren.tms.transaction.validation

import dev.viren.tms.transaction.domain.TransactionType
import org.springframework.boot.context.properties.ConfigurationProperties

data class RuleSettings(
    val enabled: Boolean? = null,
    val params: Map<String, String> = emptyMap(),
)

@ConfigurationProperties("tms.validation")
data class ValidationProperties(
    /** Rules applied to every transaction type. */
    val defaults: Map<String, RuleSettings> = emptyMap(),
    /** Per-type overrides: disable a rule, change params, or add a type-specific rule. */
    val overrides: Map<TransactionType, Map<String, RuleSettings>> = emptyMap(),
)
