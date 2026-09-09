package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.bobinski.stockanalyst.domain.error.BackendDataException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackendProviderRetryTest {

    @Test
    fun `backend transport budget is explicit and bounded below caller deadline`() {
        val timeout = HttpTimeoutConfig()
        val retry = HttpRequestRetryConfig()

        timeout.configureBackendTimeouts()
        retry.configureBackendTransportRetries()

        assertEquals(15_000L, timeout.requestTimeoutMillis)
        assertEquals(2_000L, timeout.connectTimeoutMillis)
        assertEquals(15_000L, timeout.socketTimeoutMillis)
        assertEquals(2, retry.maxRetries)
        assertEquals(18_500L, BackendHttpBudget.MAX_TOTAL_ELAPSED_MILLIS)
    }

    @Test
    fun `slow successful loads survive the former six second cutoff`() = runBlocking {
        val client = retryingClient(MockEngine {
            delay(7_500)
            respond("history", HttpStatusCode.OK)
        })
        try {
            val response = withBackendRequestBudget("EURPLN=X") {
                client.get("http://backend.test/history/EURPLN=X/1y")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        } finally {
            client.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `transport retries cannot extend the shared elapsed deadline`() = runTest {
        var requestCount = 0
        val client = retryingClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                requestCount++
                delay(7_000)
                throw IOException("connection reset")
            }
        }), timeouts = false)
        try {
            assertThrows<BackendDataException> {
                withBackendRequestBudget("EURPLN=X") {
                    client.get("http://backend.test/history/EURPLN=X/1y")
                }
            }
            assertEquals(3, requestCount)
            assertEquals(18_500L, currentTime)
        } finally {
            client.close()
        }
    }

    @Test
    fun `shared deadline preserves missing symbol results`() = runTest {
        assertNull(withBackendRequestBudget("MISSING") { null })
    }

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

    private fun retryingClient(engine: MockEngine, timeouts: Boolean = true): HttpClient = HttpClient(engine) {
        if (timeouts) {
            install(HttpTimeout) {
                configureBackendTimeouts()
            }
        }
        install(HttpRequestRetry) {
            configureBackendTransportRetries()
        }
    }
}
