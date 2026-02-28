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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.DividendPayment
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
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
        val expected = BasicInfo(
            "Apple Inc.", 195.0, 30.0f, 45.0f, 6.5f, 1.5f, 3e9,
            "buy", 40, 210.0f, 150.0f, 1.2f, "Technology", "Consumer Electronics",
            "2024-07-25", 1.0f, 0.96f
        )
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
    fun `getDividends returns data on success`() = runTest {
        val dividends = listOf(
            DividendPayment(kotlinx.datetime.LocalDate(2024, 2, 9), 0.24),
            DividendPayment(kotlinx.datetime.LocalDate(2024, 5, 10), 0.25)
        )
        val provider = providerWith(json.encodeToString(dividends), HttpStatusCode.OK)

        val result = provider.getDividends("AAPL")

        assertEquals(2, result.size)
        assertEquals(0.24, result[0].amount)
    }

    @Test
    fun `getDividends returns empty on 404`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.NotFound)

        val result = provider.getDividends("INVALID")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDividends throws on 500`() = runTest {
        val provider = providerWith("{}", HttpStatusCode.InternalServerError)

        val exception = assertThrows<BackendDataException> {
            provider.getDividends("AAPL")
        }

        assertEquals(BackendDataException.Reason.BACKEND_ERROR, exception.reason)
    }

    private fun providerWith(responseBody: String, status: HttpStatusCode): BackendProvider {
        val engine = MockEngine { _ ->
            respond(responseBody, status, jsonHeaders)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return BackendProvider(client, "http://localhost:7776")
    }
}
