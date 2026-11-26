package dev.viren.tms.transaction.api

import dev.viren.tms.common.error.TmsException
import dev.viren.tms.transaction.domain.Transaction
import dev.viren.tms.transaction.domain.TransactionStatus
import dev.viren.tms.transaction.domain.TransactionType
import dev.viren.tms.transaction.handler.TransactionRequest
import dev.viren.tms.transaction.service.SubmissionResult
import dev.viren.tms.transaction.service.TransactionService
import dev.viren.tms.transaction.validation.TransactionValidator
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

const val IDEMPOTENCY_HEADER = "Idempotency-Key"

data class CreateTransactionRequest(
    @field:NotNull val type: TransactionType?,
    val sourceWalletId: String?,
    val destinationWalletId: String?,
    @field:NotNull @field:Positive val amount: Long?,
    @field:NotNull @field:Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") val currency: String?,
)

data class TransactionResponse(
    val id: String,
    val type: TransactionType,
    val status: TransactionStatus,
    val sourceWalletId: String,
    val destinationWalletId: String,
    val amount: Long,
    val currency: String,
    val externalReference: String?,
    val failureCode: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun Transaction.toResponse() = TransactionResponse(
    id, type, status, sourceWalletId, destinationWalletId, amount, currency,
    externalReference, failureCode, failureReason, createdAt, updatedAt,
)

/** 202 for a newly accepted transaction, 200 when an idempotent retry replays the original. */
fun SubmissionResult.toHttpResponse(): ResponseEntity<TransactionResponse> =
    ResponseEntity.status(if (replayed) HttpStatus.OK else HttpStatus.ACCEPTED).body(transaction.toResponse())

internal fun validIdempotencyKey(key: String): String =
    key.trim().takeIf { it.isNotEmpty() && it.length <= 100 }
        ?: throw TmsException.invalid("$IDEMPOTENCY_HEADER must be 1-100 characters")

@RestController
class TransactionController(
    private val transactionService: TransactionService,
    private val validator: TransactionValidator,
    private val statusCache: TransactionStatusCache,
) {

    @PostMapping("/api/v1/transactions")
    fun create(
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: CreateTransactionRequest,
    ): ResponseEntity<TransactionResponse> = transactionService.submit(
        TransactionRequest(
            type = request.type!!,
            sourceWalletId = request.sourceWalletId,
            destinationWalletId = request.destinationWalletId,
            amount = request.amount!!,
            currency = request.currency!!,
            idempotencyKey = validIdempotencyKey(idempotencyKey),
        ),
    ).toHttpResponse()

    @GetMapping("/api/v1/transactions/{id}")
    fun get(@PathVariable id: String): TransactionResponse =
        statusCache.get(id) ?: transactionService.get(id).toResponse().also(statusCache::putIfTerminal)

    @GetMapping("/api/v1/admin/validation-rules")
    fun validationRules() = validator.describe()
}
