package com.aspora.tms.ledger

import org.springframework.data.jpa.repository.JpaRepository

interface LedgerRepository : JpaRepository<LedgerEntry, String> {
    fun findByWalletIdOrderByCreatedAtAsc(walletId: String): List<LedgerEntry>
    fun findByTransactionId(transactionId: String): List<LedgerEntry>
}
