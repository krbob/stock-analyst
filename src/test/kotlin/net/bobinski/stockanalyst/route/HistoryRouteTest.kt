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
    fun `responds with 500 when unexpected exception is thrown`() = testApplication {
        val useCase = mockk<GetStockHistoryUseCase>()
        coEvery { useCase.invoke("FAIL", Period._1y) } throws
            RuntimeException("Something went wrong")
        configureApp(useCase)

        val response = client.get("/history/FAIL")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
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

    private fun testHistory(period: String = "1y") = StockHistory(
        symbol = "AAPL",
        name = "Apple Inc.",
        period = period,
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
