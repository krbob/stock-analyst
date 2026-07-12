package net.bobinski.stockanalyst.route

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.error.BackendDataException.Reason

suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, mapOf("error" to message))
}

suspend fun ApplicationCall.respondError(error: BackendDataException) {
    error.retryAfter?.let { response.header(HttpHeaders.RetryAfter, it) }
    respondError(error.toHttpStatusCode(), error.message ?: "Error.")
}

fun BackendDataException.toHttpStatusCode(): HttpStatusCode = when (reason) {
    Reason.NOT_FOUND -> HttpStatusCode.NotFound
    Reason.INSUFFICIENT_DATA -> HttpStatusCode.UnprocessableEntity
    Reason.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    Reason.BACKEND_ERROR -> HttpStatusCode.BadGateway
}
