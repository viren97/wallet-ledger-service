package com.aspora.tms.common.error

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

data class ApiError(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
    val timestamp: Instant = Instant.now(),
)

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(TmsException::class)
    fun handleDomain(ex: TmsException) = respond(ex.code, ex.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException) = respond(
        ErrorCode.INVALID_REQUEST,
        "Request validation failed",
        ex.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" },
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException) =
        respond(ErrorCode.INVALID_REQUEST, "Malformed request body")

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(ex: MissingRequestHeaderException) =
        respond(ErrorCode.INVALID_REQUEST, "Required header '${ex.headerName}' is missing")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException) =
        respond(ErrorCode.INVALID_REQUEST, "Invalid value for '${ex.name}'")

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: OptimisticLockingFailureException) =
        respond(ErrorCode.CONCURRENCY_CONFLICT, "Resource was modified concurrently, please retry")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException) =
        respond(ErrorCode.DUPLICATE_RESOURCE, "Request conflicts with an existing resource")

    @ExceptionHandler(
        NoResourceFoundException::class,
        HttpRequestMethodNotSupportedException::class,
        HttpMediaTypeNotSupportedException::class,
    )
    fun handleFramework(ex: Exception): ResponseEntity<ApiError> {
        val status = HttpStatus.valueOf((ex as ErrorResponse).statusCode.value())
        return ResponseEntity.status(status).body(ApiError(status.name, ex.message ?: status.reasonPhrase))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception", ex)
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected error, please retry later")
    }

    private fun respond(code: ErrorCode, message: String, details: List<String> = emptyList()) =
        ResponseEntity.status(code.httpStatus).body(ApiError(code.name, message, details))
}
