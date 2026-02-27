package net.bobinski.portfolio.domain.error

class BackendDataException(
    message: String,
    val reason: Reason
) : RuntimeException(message) {

    enum class Reason { NOT_FOUND, INSUFFICIENT_DATA, BACKEND_ERROR }

    companion object {
        fun unknownSymbol(symbol: String) =
            BackendDataException("Unknown symbol: $symbol", Reason.NOT_FOUND)

        fun missingHistory(symbol: String) =
            BackendDataException("Missing history for $symbol", Reason.NOT_FOUND)

        fun insufficientConversion(symbol: String) =
            BackendDataException("Not enough conversion history for $symbol", Reason.INSUFFICIENT_DATA)

        fun backendError(symbol: String) =
            BackendDataException("Backend error for $symbol", Reason.BACKEND_ERROR)
    }
}
