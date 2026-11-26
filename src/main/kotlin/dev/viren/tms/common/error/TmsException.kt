package dev.viren.tms.common.error

/** The only domain exception. The [code] decides the HTTP status and the transaction failure code. */
class TmsException(val code: ErrorCode, override val message: String) : RuntimeException(message) {
    companion object {
        fun notFound(resource: String, id: String) = TmsException(ErrorCode.RESOURCE_NOT_FOUND, "$resource '$id' not found")
        fun invalid(message: String) = TmsException(ErrorCode.INVALID_REQUEST, message)
    }
}
