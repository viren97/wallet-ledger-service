package com.aspora.tms.transaction.handler

import com.aspora.tms.wallet.BalanceBucket
import com.aspora.tms.wallet.Wallet

data class Allocation(val bucket: BalanceBucket, val amount: Long)

/** Decides which balance bucket(s) a debit is taken from. */
interface BalanceAllocationStrategy {
    fun available(wallet: Wallet): Long
    fun allocate(wallet: Wallet, amount: Long): List<Allocation>
}

/** Debit from exactly one bucket. */
class SingleBucketAllocation(private val bucket: BalanceBucket) : BalanceAllocationStrategy {
    override fun available(wallet: Wallet) = wallet.balanceOf(bucket)
    override fun allocate(wallet: Wallet, amount: Long) = listOf(Allocation(bucket, amount))
}

/**
 * Drain buckets in the given order. Any remainder is put on the last bucket, where the wallet's
 * own invariant rejects it for user wallets (defence in depth behind the sufficient-balance rule).
 */
class PriorityAllocation(private val order: List<BalanceBucket>) : BalanceAllocationStrategy {
    init { require(order.isNotEmpty()) }

    override fun available(wallet: Wallet) = order.sumOf { wallet.balanceOf(it) }

    override fun allocate(wallet: Wallet, amount: Long): List<Allocation> {
        var remaining = amount
        val allocations = mutableListOf<Allocation>()
        for (bucket in order) {
            if (remaining == 0L) break
            val take = minOf(remaining, wallet.balanceOf(bucket).coerceAtLeast(0))
            if (take > 0) {
                allocations += Allocation(bucket, take)
                remaining -= take
            }
        }
        if (remaining > 0) allocations += Allocation(order.last(), remaining)
        return allocations.groupBy { it.bucket }.map { (bucket, a) -> Allocation(bucket, a.sumOf { it.amount }) }
    }
}

object BalanceAllocations {
    val WITHDRAWABLE_ONLY: BalanceAllocationStrategy = SingleBucketAllocation(BalanceBucket.WITHDRAWABLE)
    val PROMO_FIRST: BalanceAllocationStrategy =
        PriorityAllocation(listOf(BalanceBucket.NON_WITHDRAWABLE, BalanceBucket.WITHDRAWABLE))
}
