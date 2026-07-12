package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackendProviderRetryTest {

    @Test
    fun `does not retry classified backend HTTP responses`() = runTest {
        listOf(
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable
        ).forEach { status ->
            var requestCount = 0
            val engine = MockEngine {
                requestCount++
                respond(
                    content = "{}",
                    status = status,
                    headers = headersOf(HttpHeaders.RetryAfter, "30")
                )
            }
            val client = retryingClient(engine)

            try {
                val response = client.get("http://backend.test/info/AAPL")

                assertEquals(status, response.status)
                assertEquals(1, requestCount, "Unexpected retry for $status")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `retries transient transport failures up to the configured budget`() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            if (requestCount < 3) {
                throw IOException("connection reset")
            }
            respond("{}", HttpStatusCode.OK)
        }
        val client = retryingClient(engine)

        try {
            val response = client.get("http://backend.test/info/AAPL")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(3, requestCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `does not retry cancellation`() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            throw CancellationException("cancelled")
        }
        val client = retryingClient(engine)

        try {
            assertThrows<CancellationException> {
                client.get("http://backend.test/info/AAPL")
            }
            assertEquals(1, requestCount)
        } finally {
            client.close()
        }
    }

    private fun retryingClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(HttpRequestRetry) {
            configureBackendTransportRetries()
        }
    }
}
