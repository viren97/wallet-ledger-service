package dev.viren.tms.transaction

import dev.viren.tms.transaction.handler.Allocation
import dev.viren.tms.transaction.handler.BalanceAllocations
import dev.viren.tms.wallet.BalanceBucket.NON_WITHDRAWABLE
import dev.viren.tms.wallet.BalanceBucket.WITHDRAWABLE
import dev.viren.tms.wallet.Wallet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BalanceAllocationTest {

    private val wallet = Wallet.forUser("u1", "INR").apply {
        credit(WITHDRAWABLE, 1_000)
        credit(NON_WITHDRAWABLE, 300)
    }

    @Test
    fun `promo-first spends non-withdrawable then withdrawable`() {
        assertThat(BalanceAllocations.PROMO_FIRST.allocate(wallet, 500))
            .containsExactly(Allocation(NON_WITHDRAWABLE, 300), Allocation(WITHDRAWABLE, 200))
    }

    @Test
    fun `promo-first uses a single bucket when promo balance covers it`() {
        assertThat(BalanceAllocations.PROMO_FIRST.allocate(wallet, 300))
            .containsExactly(Allocation(NON_WITHDRAWABLE, 300))
    }

    @Test
    fun `available balance depends on the strategy`() {
        assertThat(BalanceAllocations.PROMO_FIRST.available(wallet)).isEqualTo(1_300)
        assertThat(BalanceAllocations.WITHDRAWABLE_ONLY.available(wallet)).isEqualTo(1_000)
    }
}
