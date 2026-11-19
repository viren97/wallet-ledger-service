package com.aspora.tms.ledger

import com.aspora.tms.wallet.BalanceBucket
import com.aspora.tms.wallet.Wallet
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class BucketReconciliation(val storedBalance: Long, val ledgerBalance: Long) {
    val consistent: Boolean get() = storedBalance == ledgerBalance
}

data class ReconciliationReport(val walletId: String, val buckets: Map<BalanceBucket, BucketReconciliation>) {
    val consistent: Boolean get() = buckets.values.all { it.consistent }
}

@Service
class LedgerService(private val ledger: LedgerRepository) {

    /**
     * MANDATORY: a ledger write is only valid as part of the same DB transaction that changes
     * the wallet balance (the "dual write"). Calling this outside a transaction fails fast.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(transactionId: String, wallet: Wallet, direction: EntryDirection, bucket: BalanceBucket, amount: Long) {
        ledger.save(LedgerEntry(transactionId, wallet.id, direction, bucket, amount, wallet.balanceOf(bucket)))
    }

    @Transactional(readOnly = true)
    fun entriesFor(walletId: String): List<LedgerEntry> = ledger.findByWalletIdOrderByCreatedAtAsc(walletId)

    /** Re-derives the balance from the ledger and compares it with the stored balance. */
    @Transactional(readOnly = true)
    fun reconcile(wallet: Wallet): ReconciliationReport {
        val ledgerNet = entriesFor(wallet.id).groupBy { it.bucket }.mapValues { (_, e) -> e.sumOf { it.signedAmount } }
        val buckets = BalanceBucket.entries.associateWith {
            BucketReconciliation(wallet.balanceOf(it), ledgerNet[it] ?: 0)
        }
        return ReconciliationReport(wallet.id, buckets)
    }
}
