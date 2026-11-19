package com.aspora.tms.transaction.handler

import com.aspora.tms.common.error.TmsException
import com.aspora.tms.transaction.domain.TransactionType
import org.springframework.stereotype.Component

/** Registry of strategies. Spring injects every TransactionHandler bean, so nothing here changes per new type. */
@Component
class TransactionHandlerRegistry(handlers: List<TransactionHandler>) {
    private val byType: Map<TransactionType, TransactionHandler> = handlers.associateBy { it.type }

    init {
        check(byType.size == handlers.size) { "Duplicate TransactionHandler registered for the same type" }
    }

    fun get(type: TransactionType): TransactionHandler =
        byType[type] ?: throw TmsException.invalid("Transaction type $type is not supported yet")
}
