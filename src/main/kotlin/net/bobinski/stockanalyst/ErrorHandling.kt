package net.bobinski.stockanalyst

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import kotlinx.coroutines.CancellationException
import net.bobinski.stockanalyst.route.ApiErrorCode
import net.bobinski.stockanalyst.route.respondError

fun Application.configureErrorHandling() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            if (call.request.httpMethod != HttpMethod.Get && isKnownGetRoute(call.request.path())) {
                call.response.header(HttpHeaders.Allow, HttpMethod.Get.value)
                call.respondError(
                    HttpStatusCode.MethodNotAllowed,
                    "Method not allowed.",
                    ApiErrorCode.METHOD_NOT_ALLOWED
                )
            } else {
                call.respondError(status, "Route not found.", ApiErrorCode.ROUTE_NOT_FOUND)
            }
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.response.header(HttpHeaders.Allow, HttpMethod.Get.value)
            call.respondError(status, "Method not allowed.", ApiErrorCode.METHOD_NOT_ALLOWED)
        }
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Unhandled application error", cause)
            call.respondError(
                status = HttpStatusCode.InternalServerError,
                message = "Unexpected error occurred.",
                errorCode = ApiErrorCode.INTERNAL_ERROR
            )
        }
    }
}

private fun isKnownGetRoute(path: String): Boolean =
    path == "/health" ||
        path == "/healthz" ||
        path == "/readyz" ||
        path == "/metrics" ||
        path == "/openapi/v1.json" ||
        path == "/compare" ||
        path == "/v1/compare" ||
        PARAMETERIZED_GET_ROUTE.matches(path)

private val PARAMETERIZED_GET_ROUTE =
    Regex("^/(?:v1/)?(?:quote|history|indicators|search)/[^/]+$")
