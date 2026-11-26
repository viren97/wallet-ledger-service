package dev.viren.tms.wallet

import dev.viren.tms.common.error.TmsException
import dev.viren.tms.ledger.LedgerEntry
import dev.viren.tms.ledger.LedgerService
import dev.viren.tms.ledger.ReconciliationReport
import dev.viren.tms.user.UserService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WalletService(
    private val wallets: WalletRepository,
    private val users: UserService,
    private val ledger: LedgerService,
) {

    @Transactional
    fun createForUser(userId: String, currency: String): Wallet {
        if (!users.exists(userId)) throw TmsException.notFound("User", userId)
        return wallets.save(Wallet.forUser(userId, currency.uppercase()))
    }

    @Transactional(readOnly = true)
    fun get(id: String): Wallet = wallets.findByIdOrNull(id) ?: throw TmsException.notFound("Wallet", id)

    @Transactional(readOnly = true)
    fun forUser(userId: String): List<Wallet> = wallets.findByUserId(userId)

    @Transactional(readOnly = true)
    fun systemWallets(): List<Wallet> = wallets.findByType(WalletType.SYSTEM)

    @Transactional(readOnly = true)
    fun ledger(id: String): List<LedgerEntry> = ledger.entriesFor(get(id).id)

    @Transactional(readOnly = true)
    fun reconcile(id: String): ReconciliationReport = ledger.reconcile(get(id))
}
