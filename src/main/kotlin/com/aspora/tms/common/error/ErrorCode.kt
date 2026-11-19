package com.aspora.tms.common.error

import org.springframework.http.HttpStatus

/**
 * Single catalogue of business error codes. A new failure reason is a new enum entry,
 * not a new exception class.
 */
enum class ErrorCode(val httpStatus: HttpStatus) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY),
    LIMIT_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    WALLET_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY),
    SAME_WALLET(HttpStatus.UNPROCESSABLE_ENTITY),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT),
    CONCURRENCY_CONFLICT(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
}
