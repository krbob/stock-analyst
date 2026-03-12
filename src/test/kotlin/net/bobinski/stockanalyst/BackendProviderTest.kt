package net.bobinski.stockanalyst

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                100.0, 101.0, 99.0, 102.0, 1000L, 0.0
            )
        )
        val provider = providerWith(json.encodeToString(prices), HttpStatusCode.OK)

        val result = provider.getHistory("AAPL", StockDataProvider.Period._1y)

        assertEquals(1, result.size)
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
    fun `getInfo returns null on 400`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.BadRequest)

        val result = provider.getInfo("BAD")

        assertNull(result)
    }

    @Test
    fun `getHistory returns empty on 400`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.BadRequest)

        val result = provider.getHistory("BAD", StockDataProvider.Period._1y)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `search returns results on success`() = runTest {
        val expected = listOf(SearchResult("AAPL", "Apple Inc.", "NMS", "EQUITY"))
        val provider = providerWith(json.encodeToString(expected), HttpStatusCode.OK)

        val result = provider.search("apple")

        assertEquals(expected, result)
    }

    @Test
    fun `search returns empty on 404`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.NotFound)

        val result = provider.search("missing")

        assertTrue(result.isEmpty())
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
        kotlinx.datetime.LocalDate(2024, 7, 25), 1.0, 0.96
    )

    private fun providerWith(responseBody: String, status: HttpStatusCode): BackendProvider {
        val engine = MockEngine { _ ->
            respond(responseBody, status, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return BackendProvider(client, "http://localhost:8081")
    }
}
