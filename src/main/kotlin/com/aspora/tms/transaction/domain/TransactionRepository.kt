package com.aspora.tms.transaction.domain

import org.springframework.data.jpa.repository.JpaRepository

interface TransactionRepository : JpaRepository<Transaction, String> {
    fun findByIdempotencyKey(idempotencyKey: String): Transaction?
}
