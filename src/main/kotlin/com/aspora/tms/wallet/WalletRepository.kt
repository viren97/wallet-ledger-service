package com.aspora.tms.wallet

import org.springframework.data.jpa.repository.JpaRepository

interface WalletRepository : JpaRepository<Wallet, String> {
    fun findByUserId(userId: String): List<Wallet>
    fun findByType(type: WalletType): List<Wallet>
    fun findBySystemCodeAndCurrency(code: SystemWalletCode, currency: String): Wallet?
}
