package com.aspora.tms.transaction.api

import com.aspora.tms.transaction.domain.TransactionType
import com.aspora.tms.transaction.handler.TransactionRequest
import com.aspora.tms.transaction.service.TransactionService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class BankDepositNotification(
    @field:NotBlank val bankReference: String?,
    @field:NotBlank val walletId: String?,
    @field:NotNull @field:Positive val amount: Long?,
    @field:NotNull @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String?,
)

/**
 * Inbound push from the bank when a cash/bank deposit lands. Banks retry webhooks, so the bank
 * reference is the idempotency key (plus a unique external_reference column as a DB guarantee).
 * Signature (HMAC) verification is on the roadmap.
 */
@RestController
class BankWebhookController(private val transactionService: TransactionService) {

    @PostMapping("/api/v1/webhooks/bank/deposits")
    fun deposit(@Valid @RequestBody notification: BankDepositNotification) = transactionService.submit(
        TransactionRequest(
            type = TransactionType.DEPOSIT,
            sourceWalletId = null,
            destinationWalletId = notification.walletId,
            amount = notification.amount!!,
            currency = notification.currency!!,
            idempotencyKey = "bank-deposit:${notification.bankReference}",
            externalReference = notification.bankReference,
        ),
    ).toHttpResponse()
}
