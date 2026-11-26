package dev.viren.tms.transaction.validation

import dev.viren.tms.transaction.domain.TransactionType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Chain of Responsibility built from configuration. For each transaction type the effective chain is
 * `defaults` merged with `overrides.<TYPE>` (override wins; params are merged), ordered by rule order.
 * Chains are resolved once at startup; an unknown rule key or bad params fails the boot.
 */
@Component
class TransactionValidator(rules: List<ValidationRule>, properties: ValidationProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class ConfiguredRule(val rule: ValidationRule, val params: RuleParams)

    private val rulesByKey = rules.associateBy { it.key }
    private val chains: Map<TransactionType, List<ConfiguredRule>> =
        TransactionType.entries.associateWith { buildChain(it, properties) }

    init {
        chains.forEach { (type, chain) -> log.info("Validation chain for {}: {}", type, chain.map { it.rule.key }) }
    }

    fun validate(context: ValidationContext) {
        chains.getValue(context.transaction.type).forEach { it.rule.validate(context, it.params) }
    }

    /** Effective configuration, exposed for observability. */
    fun describe(): Map<TransactionType, List<Map<String, String>>> = chains.mapValues { (_, chain) ->
        chain.map { mapOf("rule" to it.rule.key, "params" to it.params.toString()) }
    }

    private fun buildChain(type: TransactionType, properties: ValidationProperties): List<ConfiguredRule> {
        val overrides = properties.overrides[type].orEmpty()
        return (properties.defaults.keys + overrides.keys).mapNotNull { key ->
            val rule = rulesByKey[key] ?: throw IllegalStateException("Unknown validation rule '$key' in tms.validation")
            val default = properties.defaults[key]
            val override = overrides[key]
            val enabled = override?.enabled ?: default?.enabled ?: true
            if (!enabled) return@mapNotNull null
            val params = RuleParams(default?.params.orEmpty() + override?.params.orEmpty())
            rule.checkConfig(params)
            ConfiguredRule(rule, params)
        }.sortedBy { it.rule.order }
    }
}
