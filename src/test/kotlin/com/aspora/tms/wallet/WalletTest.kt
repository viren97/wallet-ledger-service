package com.aspora.tms.wallet

import com.aspora.tms.common.error.ErrorCode
import com.aspora.tms.common.error.TmsException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WalletTest {

    @Test
    fun `user wallet cannot go negative in a bucket even if total balance is enough`() {
        val wallet = Wallet.forUser("u1", "INR").apply { credit(BalanceBucket.NON_WITHDRAWABLE, 1_000) }

        assertThatThrownBy { wallet.debit(BalanceBucket.WITHDRAWABLE, 1) }
            .isInstanceOf(TmsException::class.java)
            .extracting("code").isEqualTo(ErrorCode.INSUFFICIENT_BALANCE)
        assertThat(wallet.totalBalance).isEqualTo(1_000)
    }

    @Test
    fun `system wallet can go negative to mirror money held outside the platform`() {
        val settlement = Wallet.system(SystemWalletCode.BANK_SETTLEMENT, "INR")

        settlement.debit(BalanceBucket.WITHDRAWABLE, 5_000)

        assertThat(settlement.withdrawableBalance).isEqualTo(-5_000)
    }
}
