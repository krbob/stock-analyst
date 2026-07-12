package net.bobinski.stockanalyst.route

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.error.BackendDataException.Reason

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
    errorCode: ApiErrorCode = ApiErrorCode.INVALID_REQUEST,
    retryable: Boolean = false
) {
    respond(
        status,
        ApiErrorResponse(
            error = message,
            errorCode = errorCode,
            retryable = retryable,
            requestId = callId
        )
    )
}

suspend fun ApplicationCall.respondError(error: BackendDataException) {
    error.retryAfter?.let { response.header(HttpHeaders.RetryAfter, it) }
    respondError(
        status = error.toHttpStatusCode(),
        message = error.message ?: "Error.",
        errorCode = error.toApiErrorCode(),
        retryable = error.reason in RETRYABLE_REASONS
    )
}

fun BackendDataException.toHttpStatusCode(): HttpStatusCode = when (reason) {
    Reason.NOT_FOUND -> HttpStatusCode.NotFound
    Reason.INSUFFICIENT_DATA -> HttpStatusCode.UnprocessableEntity
    Reason.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    Reason.SERVICE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    Reason.BACKEND_ERROR -> HttpStatusCode.BadGateway
}

private fun BackendDataException.toApiErrorCode(): ApiErrorCode = when (reason) {
    Reason.NOT_FOUND -> ApiErrorCode.SYMBOL_NOT_FOUND
    Reason.INSUFFICIENT_DATA -> ApiErrorCode.INSUFFICIENT_DATA
    Reason.RATE_LIMITED -> ApiErrorCode.UPSTREAM_RATE_LIMITED
    Reason.SERVICE_UNAVAILABLE -> ApiErrorCode.SERVICE_UNAVAILABLE
    Reason.BACKEND_ERROR -> ApiErrorCode.UPSTREAM_ERROR
}

private val RETRYABLE_REASONS = setOf(
    Reason.RATE_LIMITED,
    Reason.SERVICE_UNAVAILABLE,
    Reason.BACKEND_ERROR
)

@Serializable
data class ApiErrorResponse(
    val error: String,
    val errorCode: ApiErrorCode,
    val retryable: Boolean,
    val requestId: String?
)

@Serializable
enum class ApiErrorCode {
    INVALID_REQUEST,
    ROUTE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    SYMBOL_NOT_FOUND,
    INSUFFICIENT_DATA,
    UPSTREAM_RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    UPSTREAM_ERROR,
    INTERNAL_ERROR
}
