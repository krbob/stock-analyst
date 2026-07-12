package net.bobinski.stockanalyst

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.route.ApiErrorCode
import net.bobinski.stockanalyst.route.ApiErrorResponse
import net.bobinski.stockanalyst.route.respondError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitoringAndErrorContractTest {

    @Test
    fun `validation error preserves a safe request id in header and body`() = testApplication {
        application { module() }

        val response = client.get("/quote/AAPL?currency=INVALID") {
            header(HttpHeaders.XRequestId, "stock-request-123")
        }
        val error = response.apiError()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("stock-request-123", response.headers[HttpHeaders.XRequestId])
        assertEquals("stock-request-123", error.requestId)
        assertEquals(ApiErrorCode.INVALID_REQUEST, error.errorCode)
        assertFalse(error.retryable)
    }

    @Test
    fun `invalid request id is replaced with a generated safe value`() = testApplication {
        application { module() }

        val response = client.get("/quote/AAPL?currency=INVALID") {
            header(HttpHeaders.XRequestId, "unsafe id with spaces")
        }
        val responseId = response.headers[HttpHeaders.XRequestId]
        val error = response.apiError()

        assertNotEquals("unsafe id with spaces", responseId)
        assertTrue(responseId != null && SAFE_REQUEST_ID_PATTERN.matches(responseId))
        assertEquals(responseId, error.requestId)
    }

    @Test
    fun `framework 404 and 405 use the stable error contract`() = testApplication {
        application { module() }

        val missing = client.get("/v1/not-a-route")
        val unsupported = client.post("/quote/AAPL")
        val unsupportedUnknown = client.post("/not-a-route")

        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(ApiErrorCode.ROUTE_NOT_FOUND, missing.apiError().errorCode)
        assertEquals(HttpStatusCode.MethodNotAllowed, unsupported.status)
        assertEquals(HttpMethod.Get.value, unsupported.headers[HttpHeaders.Allow])
        assertEquals(ApiErrorCode.METHOD_NOT_ALLOWED, unsupported.apiError().errorCode)
        assertEquals(HttpStatusCode.NotFound, unsupportedUnknown.status)
        assertEquals(ApiErrorCode.ROUTE_NOT_FOUND, unsupportedUnknown.apiError().errorCode)
    }

    @Test
    fun `unhandled exception returns internal error without leaking details`() = testApplication {
        application {
            module()
            routing {
                get("/test/unhandled") { error("secret implementation detail") }
            }
        }

        val response = client.get("/test/unhandled")
        val body = response.bodyAsText()
        val error = JSON.decodeFromString<ApiErrorResponse>(body)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(ApiErrorCode.INTERNAL_ERROR, error.errorCode)
        assertFalse(error.retryable)
        assertFalse(body.contains("secret implementation detail"))
    }

    @Test
    fun `retryable backend error keeps code and retry-after`() = testApplication {
        application {
            module()
            routing {
                get("/test/rate-limit") {
                    call.respondError(BackendDataException.rateLimited("AAPL", "60"))
                }
            }
        }

        val response = client.get("/test/rate-limit")
        val error = response.apiError()

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("60", response.headers[HttpHeaders.RetryAfter])
        assertEquals(ApiErrorCode.UPSTREAM_RATE_LIMITED, error.errorCode)
        assertTrue(error.retryable)
    }

    @Test
    fun `production log pattern renders request id from MDC`() {
        val config = requireNotNull(javaClass.getResource("/logback.xml")).readText()

        assertTrue(config.contains("[requestId=%X{requestId}]"))
        assertTrue(config.contains("%d{yyyy-MM-dd"))
    }

    private suspend fun io.ktor.client.statement.HttpResponse.apiError(): ApiErrorResponse =
        JSON.decodeFromString(bodyAsText())

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SAFE_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
