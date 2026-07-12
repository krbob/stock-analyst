package net.bobinski.stockanalyst.domain.error

class BackendDataException(
    message: String,
    val reason: Reason,
    val retryAfter: String? = null
) : RuntimeException(message) {

    enum class Reason { NOT_FOUND, INSUFFICIENT_DATA, RATE_LIMITED, BACKEND_ERROR }

    companion object {
        fun unknownSymbol(symbol: String) =
            BackendDataException("Unknown symbol: $symbol", Reason.NOT_FOUND)

        fun missingHistory(symbol: String) =
            BackendDataException("Missing history for $symbol", Reason.NOT_FOUND)

        fun insufficientConversion(symbol: String) =
            BackendDataException("Not enough conversion history for $symbol", Reason.INSUFFICIENT_DATA)

        fun currencyUnavailable(symbol: String) =
            BackendDataException("Currency conversion is unavailable for $symbol", Reason.INSUFFICIENT_DATA)

        fun rateLimited(resource: String, retryAfter: String?) =
            BackendDataException(
                message = "Upstream rate limit for $resource",
                reason = Reason.RATE_LIMITED,
                retryAfter = retryAfter
            )

        fun backendError(symbol: String) =
            BackendDataException("Backend error for $symbol", Reason.BACKEND_ERROR)
    }
}
