package dev.viren.tms.wallet

import dev.viren.tms.ledger.EntryDirection
import dev.viren.tms.ledger.LedgerEntry
import dev.viren.tms.ledger.ReconciliationReport
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CreateWalletRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") val currency: String?,
)

data class WalletResponse(
    val id: String,
    val userId: String?,
    val type: WalletType,
    val systemCode: SystemWalletCode?,
    val currency: String,
    val status: WalletStatus,
    val withdrawableBalance: Long,
    val nonWithdrawableBalance: Long,
    val totalBalance: Long,
)

data class LedgerEntryResponse(
    val id: String,
    val transactionId: String,
    val direction: EntryDirection,
    val bucket: BalanceBucket,
    val amount: Long,
    val balanceAfter: Long,
    val createdAt: Instant,
)

fun Wallet.toResponse() = WalletResponse(
    id, userId, type, systemCode, currency, status, withdrawableBalance, nonWithdrawableBalance, totalBalance,
)

fun LedgerEntry.toResponse() = LedgerEntryResponse(id, transactionId, direction, bucket, amount, balanceAfter, createdAt)

@RestController
class WalletController(private val walletService: WalletService) {

    @PostMapping("/api/v1/users/{userId}/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable userId: String, @Valid @RequestBody request: CreateWalletRequest): WalletResponse =
        walletService.createForUser(userId, request.currency!!).toResponse()

    @GetMapping("/api/v1/users/{userId}/wallets")
    fun forUser(@PathVariable userId: String): List<WalletResponse> = walletService.forUser(userId).map { it.toResponse() }

    @GetMapping("/api/v1/wallets/system")
    fun system(): List<WalletResponse> = walletService.systemWallets().map { it.toResponse() }

    @GetMapping("/api/v1/wallets/{id}")
    fun get(@PathVariable id: String): WalletResponse = walletService.get(id).toResponse()

    @GetMapping("/api/v1/wallets/{id}/ledger")
    fun ledger(@PathVariable id: String): List<LedgerEntryResponse> = walletService.ledger(id).map { it.toResponse() }

    @GetMapping("/api/v1/wallets/{id}/reconciliation")
    fun reconcile(@PathVariable id: String): ReconciliationReport = walletService.reconcile(id)
}
