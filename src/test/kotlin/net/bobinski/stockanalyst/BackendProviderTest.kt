package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.SearchResult
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackendProviderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `getInfo returns BasicInfo on success`() = runTest {
        val expected = basicInfo()
        val provider = providerWith(json.encodeToString(expected), HttpStatusCode.OK)

        val result = provider.getInfo("AAPL")

        assertEquals(expected, result)
    }

    @Test
    fun `getInfo returns null on 404`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.NotFound)

        val result = provider.getInfo("INVALID")

        assertNull(result)
    }

    @Test
    fun `getInfo throws on 500`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.InternalServerError)

        val exception = assertThrows<BackendDataException> { provider.getInfo("AAPL") }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `getHistory returns data on success`() = runTest {
        val prices = listOf(
            HistoricalPrice(
                kotlinx.datetime.LocalDate(2024, 6, 15),
                100.0, 101.0, 99.0, 102.0, 1000L, 0.0,
                splitRatio = 10.0
            )
        )
        val provider = providerWith(json.encodeToString(prices), HttpStatusCode.OK)

        val result = provider.getHistory("AAPL", StockDataProvider.Period._1y)

        assertEquals(1, result.size)
        assertEquals(10.0, result.single().splitRatio)
    }

    @Test
    fun `getHistory encodes symbol in backend path`() = runTest {
        val prices = emptyList<HistoricalPrice>()
        var requestedPath = ""
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond(json.encodeToString(prices), HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        provider.getHistory("^GSPC", StockDataProvider.Period._1y)

        assertEquals("/history/%5EGSPC/1y", requestedPath)
    }

    @Test
    fun `getHistory encodes slash inside symbol path segment`() = runTest {
        val prices = emptyList<HistoricalPrice>()
        var requestedPath = ""
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond(json.encodeToString(prices), HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        provider.getHistory("BRK/B", StockDataProvider.Period._1y)

        assertEquals("/history/BRK%2FB/1y", requestedPath)
    }

    @Test
    fun `getHistory returns empty on 404`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.NotFound)

        val result = provider.getHistory("INVALID", StockDataProvider.Period._1y)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getHistory throws on 500`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.InternalServerError)

        val exception = assertThrows<BackendDataException> {
            provider.getHistory("AAPL", StockDataProvider.Period._1y)
        }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `getInfo treats 400 as backend failure instead of missing symbol`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.BadRequest)

        val exception = assertThrows<BackendDataException> { provider.getInfo("BAD") }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `getInfo encodes symbol in backend path`() = runTest {
        var requestedPath = ""
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond(json.encodeToString(basicInfo()), HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        provider.getInfo("^GSPC")

        assertEquals("/info/%5EGSPC", requestedPath)
    }

    @Test
    fun `getInfo encodes slash inside symbol path segment`() = runTest {
        var requestedPath = ""
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond(json.encodeToString(basicInfo()), HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        provider.getInfo("BRK/B")

        assertEquals("/info/BRK%2FB", requestedPath)
    }

    @Test
    fun `getHistory treats 400 as backend failure instead of empty history`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.BadRequest)

        val exception = assertThrows<BackendDataException> {
            provider.getHistory("BAD", StockDataProvider.Period._1y)
        }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `search returns results on success`() = runTest {
        val expected = listOf(SearchResult("AAPL", "Apple Inc.", "NMS", "EQUITY"))
        val provider = providerWith(json.encodeToString(expected), HttpStatusCode.OK)

        val result = provider.search("apple")

        assertEquals(expected, result)
    }

    @Test
    fun `search treats 404 as backend failure instead of legal empty results`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.NotFound)

        val exception = assertThrows<BackendDataException> { provider.search("missing") }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `getHistory preserves rate limit and Retry-After`() = runTest {
        val provider = providerWith(
            responseBody = "{}",
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "120")
        )

        val exception = assertThrows<BackendDataException> {
            provider.getHistory("AAPL", StockDataProvider.Period._1y)
        }

        assertEquals(BackendDataException.Reason.RATE_LIMITED, exception.reason)
        assertEquals("120", exception.retryAfter)
    }

    @Test
    fun `getInfo preserves rate limit without invented Retry-After`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.TooManyRequests)

        val exception = assertThrows<BackendDataException> { provider.getInfo("AAPL") }

        assertEquals(BackendDataException.Reason.RATE_LIMITED, exception.reason)
        assertNull(exception.retryAfter)
    }

    @Test
    fun `search preserves rate limit`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.TooManyRequests)

        val exception = assertThrows<BackendDataException> { provider.search("apple") }

        assertEquals(BackendDataException.Reason.RATE_LIMITED, exception.reason)
    }

    @Test
    fun `getHistory preserves backend saturation and Retry-After`() = runTest {
        val provider = providerWith(
            responseBody = "{}",
            status = HttpStatusCode.ServiceUnavailable,
            headers = headersOf(HttpHeaders.RetryAfter, "1")
        )

        val exception = assertThrows<BackendDataException> {
            provider.getHistory("AAPL", StockDataProvider.Period._1y)
        }

        assertEquals(BackendDataException.Reason.SERVICE_UNAVAILABLE, exception.reason)
        assertEquals("1", exception.retryAfter)
    }

    @Test
    fun `getInfo preserves backend saturation`() = runTest {
        val provider = providerWith(
            responseBody = "{}",
            status = HttpStatusCode.ServiceUnavailable,
            headers = headersOf(HttpHeaders.RetryAfter, "1")
        )

        val exception = assertThrows<BackendDataException> { provider.getInfo("AAPL") }

        assertEquals(BackendDataException.Reason.SERVICE_UNAVAILABLE, exception.reason)
        assertEquals("1", exception.retryAfter)
    }

    @Test
    fun `search preserves backend saturation`() = runTest {
        val provider = providerWith(
            responseBody = "{}",
            status = HttpStatusCode.ServiceUnavailable,
            headers = headersOf(HttpHeaders.RetryAfter, "1")
        )

        val exception = assertThrows<BackendDataException> { provider.search("apple") }

        assertEquals(BackendDataException.Reason.SERVICE_UNAVAILABLE, exception.reason)
        assertEquals("1", exception.retryAfter)
    }

    @Test
    fun `getHistory treats upstream 403 as backend failure`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.Forbidden)

        val exception = assertThrows<BackendDataException> {
            provider.getHistory("AAPL", StockDataProvider.Period._1y)
        }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `transport cancellation is rethrown`() = runTest {
        val engine = MockEngine { throw CancellationException("cancelled") }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        assertThrows<CancellationException> {
            provider.getHistory("AAPL", StockDataProvider.Period._1y)
        }
    }

    @Test
    fun `search throws on 500`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.InternalServerError)

        val exception = assertThrows<BackendDataException> { provider.search("apple") }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `getInfo throws on transport exception`() = runTest {
        val engine = MockEngine { throw IOException("timeout") }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        val exception = assertThrows<BackendDataException> { provider.getInfo("AAPL") }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    @Test
    fun `concurrent getInfo calls for same symbol coalesce into single request`() = runTest {
        var requestCount = 0
        val expected = basicInfo()
        val engine = MockEngine { _ ->
            requestCount++
            delay(100)
            respond(json.encodeToString(expected), HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        val results = (1..3).map {
            async { provider.getInfo("AAPL") }
        }.awaitAll()

        assertEquals(1, requestCount)
        results.forEach { assertEquals(expected, it) }
    }

    @Test
    fun `cancelled first waiter does not cancel coalesced backend request`() = runTest {
        var requestCount = 0
        val started = CompletableDeferred<Unit>()
        val expected = basicInfo()
        val engine = MockEngine { _ ->
            requestCount++
            started.complete(Unit)
            delay(100)
            respond(json.encodeToString(expected), HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val provider = BackendProvider(client, "http://localhost:8081")

        val first = async { provider.getInfo("AAPL") }
        started.await()
        first.cancelAndJoin()

        val result = provider.getInfo("AAPL")

        assertEquals(expected, result)
        assertEquals(1, requestCount)
    }

    @Test
    fun `resolveConversionSymbol uses shorthand for USD base`() {
        val provider = providerWith("{}", HttpStatusCode.OK)

        assertEquals("PLN=X", provider.resolveConversionSymbol("USD", "PLN"))
        assertEquals("EUR=X", provider.resolveConversionSymbol("USD", "EUR"))
        assertEquals("GBP=X", provider.resolveConversionSymbol("usd", "gbp"))
    }

    @Test
    fun `resolveConversionSymbol uses full pair for non-USD base`() {
        val provider = providerWith("{}", HttpStatusCode.OK)

        assertEquals("EURPLN=X", provider.resolveConversionSymbol("EUR", "PLN"))
        assertEquals("GBPUSD=X", provider.resolveConversionSymbol("GBP", "USD"))
        assertEquals("EURUSD=X", provider.resolveConversionSymbol("eur", "usd"))
    }

    private fun basicInfo() = BasicInfo(
        "Apple Inc.", 195.0, 30.0, 45.0, 6.5, 1.5, 3e9,
        "buy", 40, 210.0, 150.0, 1.2, "Technology", "Consumer Electronics",
        kotlinx.datetime.LocalDate(2024, 7, 25), 1.0, 0.96,
        currency = "USD",
        previousClose = 193.5,
        marketDate = kotlinx.datetime.LocalDate(2024, 6, 15)
    )

    private fun providerWith(
        responseBody: String,
        status: HttpStatusCode,
        headers: Headers = jsonHeaders
    ): BackendProvider {
        val engine = MockEngine { _ ->
            respond(responseBody, status, headers)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return BackendProvider(client, "http://localhost:8081")
    }
}
