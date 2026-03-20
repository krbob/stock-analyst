package net.bobinski.stockanalyst.route

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.core.dependency.CoreModule
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.StockHistory
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import net.bobinski.stockanalyst.domain.usecase.GetStockHistoryUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class HistoryRouteTest {

    @Test
    fun `responds with stock history when request is valid`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y) } returns testHistory()
        configureApp(useCase)

        val response = client.get("/history/AAPL")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"prices\""))
        assertTrue(body.contains("\"period\":\"1y\""))
        assertTrue(body.contains("\"interval\":\"1d\""))
    }

    @Test
    fun `passes custom period to use case`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._5d) } returns testHistory(period = "5d")
        configureApp(useCase)

        client.get("/history/AAPL?period=5d")

        coVerify { useCase.invoke("AAPL", Period._5d) }
    }

    @Test
    fun `uses default period 1y when not specified`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y) } returns testHistory()
        configureApp(useCase)

        client.get("/history/AAPL")

        coVerify { useCase.invoke("AAPL", Period._1y) }
    }

    @Test
    fun `responds with 400 for invalid period`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/history/AAPL?period=99z")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `responds with 400 for invalid symbol`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/history/DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `responds with 404 when symbol is unknown`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("INVALID", Period._1y) } throws
            BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/history/INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y) } throws
            BackendDataException.backendError("AAPL")
        configureApp(useCase)

        val response = client.get("/history/AAPL")

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `passes custom interval to use case`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._5y, Interval.DAILY) } returns testHistory(period = "5y", interval = "1d")
        configureApp(useCase)

        client.get("/history/AAPL?period=5y&interval=1d")

        coVerify { useCase.invoke("AAPL", Period._5y, Interval.DAILY) }
    }

    @Test
    fun `passes null interval when not specified`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y, null) } returns testHistory()
        configureApp(useCase)

        client.get("/history/AAPL")

        coVerify { useCase.invoke("AAPL", Period._1y, null) }
    }

    @Test
    fun `responds with 400 for invalid interval`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/history/AAPL?interval=99z")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `passes indicators to use case`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y, null, setOf("sma50", "rsi")) } returns testHistory()
        configureApp(useCase)

        client.get("/history/AAPL?indicators=sma50,rsi")

        coVerify { useCase.invoke("AAPL", Period._1y, null, setOf("sma50", "rsi")) }
    }

    @Test
    fun `responds with 400 for invalid indicators`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/history/AAPL?indicators=sma50,unknown")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid indicators"))
    }

    @Test
    fun `passes empty indicators when not specified`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y, null, emptySet()) } returns testHistory()
        configureApp(useCase)

        client.get("/history/AAPL")

        coVerify { useCase.invoke("AAPL", Period._1y, null, emptySet()) }
    }

    @Test
    fun `passes intraday interval to use case`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1d, Interval._5m) } returns testHistory(period = "1d", interval = "5m")
        configureApp(useCase)

        client.get("/history/AAPL?period=1d&interval=5m")

        coVerify { useCase.invoke("AAPL", Period._1d, Interval._5m) }
    }

    @Test
    fun `responds with 500 when unexpected exception is thrown`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("FAIL", Period._1y) } throws
            RuntimeException("Something went wrong")
        configureApp(useCase)

        val response = client.get("/history/FAIL")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `passes currency to use case`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y, null, emptySet(), "EUR") } returns testHistory()
        configureApp(useCase)

        client.get("/history/AAPL?currency=EUR")

        coVerify { useCase.invoke("AAPL", Period._1y, null, emptySet(), "EUR") }
    }

    @Test
    fun `responds with 400 for invalid currency code`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/history/AAPL?currency=INVALID")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `responds with 400 for invalid dividends flag`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/history/AAPL?dividends=yes")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid dividends flag"))
    }

    @Test
    fun `responds with 422 when currency conversion metadata is unavailable`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("AAPL", Period._1y, null, emptySet(), "EUR", false) } throws
            BackendDataException.currencyUnavailable("AAPL")
        configureApp(useCase)

        val response = client.get("/history/AAPL?currency=EUR")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("Currency conversion is unavailable"))
    }

    @Test
    fun `passes explicit range to use case`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        val from = LocalDate(2024, 1, 2)
        val to = LocalDate(2024, 6, 15)
        coEvery {
            useCase.invoke("AAPL", Period._1y, null, emptySet(), null, false, from, to)
        } returns testHistory()
        configureApp(useCase)

        client.get("/history/AAPL?from=2024-01-02&to=2024-06-15")

        coVerify {
            useCase.invoke("AAPL", Period._1y, null, emptySet(), null, false, from, to)
        }
    }

    @Test
    fun `responds with 400 when only one range endpoint is provided`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>(relaxed = true)
        configureApp(useCase)

        val response = client.get("/history/AAPL?from=2024-01-02")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("'from' and 'to' must be provided together"))
    }

    @Test
    fun `responds with 400 for invalid from date`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>(relaxed = true)
        configureApp(useCase)

        val response = client.get("/history/AAPL?from=2024-99-01&to=2024-06-15")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid from date"))
    }

    @Test
    fun `responds with 400 when range start is after end`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>(relaxed = true)
        configureApp(useCase)

        val response = client.get("/history/AAPL?from=2024-06-15&to=2024-01-02")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("'from' must be earlier"))
    }

    private fun ApplicationTestBuilder.configureApp(useCase: GetStockHistoryUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { historyRoute() }
        }
    }

    private fun Application.configureKoin(useCase: GetStockHistoryUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testHistory(period: String = "1y", interval: String = "1d") = StockHistory(
        symbol = "AAPL",
        name = "Apple Inc.",
        period = period,
        interval = interval,
        prices = listOf(
            HistoricalPrice(
                date = LocalDate(2024, 1, 2), open = 184.0, close = 185.0,
                low = 183.0, high = 186.0, volume = 1_000_000L, dividend = 0.0
            ),
            HistoricalPrice(
                date = LocalDate(2024, 6, 15), open = 209.0, close = 210.0,
                low = 208.0, high = 211.0, volume = 1_500_000L, dividend = 0.0
            )
        )
    )
}
