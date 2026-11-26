package dev.viren.tms.wallet

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock

/**
 * Striped in-process locks for wallets. Locks are always acquired in ascending stripe order,
 * so A->B and B->A transfers running together cannot deadlock.
 *
 * This serialises work on the same wallet inside one JVM; @Version optimistic locking on the
 * Wallet entity is still the source of truth across instances.
 */
@Component
class WalletLockManager(@Value("\${tms.locks.stripes:1024}") stripes: Int) {
    private val locks = Array(stripes) { ReentrantLock() }

    fun <T> withLocks(walletIds: Collection<String>, action: () -> T): T {
        val ordered = walletIds
            .map { Math.floorMod(it.hashCode(), locks.size) }
            .distinct()
            .sorted()
            .map { locks[it] }
        ordered.forEach { it.lock() }
        try {
            return action()
        } finally {
            ordered.asReversed().forEach { it.unlock() }
        }
    }
}
