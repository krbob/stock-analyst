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
import net.bobinski.stockanalyst.domain.model.LatestIndicators
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import net.bobinski.stockanalyst.domain.usecase.GetLatestIndicatorsUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class IndicatorsRouteTest {

    @Test
    fun `responds with indicators when request is valid`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        val expected = testIndicators()
        coEvery { useCase.invoke("AAPL", emptySet(), null, Period._1y, null) } returns expected
        configureApp(useCase)

        val response = client.get("/indicators/AAPL")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"rsi\":45.23"))
    }

    @Test
    fun `responds with 404 when symbol is unknown`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        coEvery { useCase.invoke("INVALID", any(), any(), any(), any()) } throws
            BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/indicators/INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Unknown symbol"))
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        coEvery { useCase.invoke("AAPL", any(), any(), any(), any()) } throws
            BackendDataException.backendError("AAPL")
        configureApp(useCase)

        val response = client.get("/indicators/AAPL")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("Backend error"))
    }

    @Test
    fun `responds with 400 for invalid symbol`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        configureApp(useCase)

        val response = client.get("/indicators/DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid symbol"))
    }

    @Test
    fun `responds with 500 when unexpected exception is thrown`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        coEvery { useCase.invoke("FAIL", any(), any(), any(), any()) } throws RuntimeException("Something went wrong")
        configureApp(useCase)

        val response = client.get("/indicators/FAIL")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("Something went wrong"))
    }

    @Test
    fun `passes indicators parameter to use case`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        coEvery { useCase.invoke("AAPL", setOf("rsi", "sma50"), null, Period._1y, null) } returns testIndicators()
        configureApp(useCase)

        client.get("/indicators/AAPL?indicators=rsi,sma50")

        coVerify { useCase.invoke("AAPL", setOf("rsi", "sma50"), null, Period._1y, null) }
    }

    @Test
    fun `passes currency parameter to use case`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        coEvery { useCase.invoke("AAPL", emptySet(), "EUR", Period._1y, null) } returns testIndicators()
        configureApp(useCase)

        client.get("/indicators/AAPL?currency=EUR")

        coVerify { useCase.invoke("AAPL", emptySet(), "EUR", Period._1y, null) }
    }

    @Test
    fun `passes period and interval parameters to use case`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        coEvery { useCase.invoke("AAPL", setOf("rsi"), null, Period._5y, Interval.WEEKLY) } returns testIndicators()
        configureApp(useCase)

        client.get("/indicators/AAPL?indicators=rsi&period=5y&interval=1wk")

        coVerify { useCase.invoke("AAPL", setOf("rsi"), null, Period._5y, Interval.WEEKLY) }
    }

    @Test
    fun `responds with 400 for invalid period`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        configureApp(useCase)

        val response = client.get("/indicators/AAPL?period=invalid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid period"))
    }

    @Test
    fun `responds with 400 for invalid interval`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        configureApp(useCase)

        val response = client.get("/indicators/AAPL?interval=invalid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid interval"))
    }

    @Test
    fun `responds with 400 for invalid currency code`() = testApplication {
        val useCase = mockk<GetLatestIndicatorsUseCase>()
        configureApp(useCase)

        val response = client.get("/indicators/AAPL?currency=INVALID")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid currency code"))
    }

    private fun ApplicationTestBuilder.configureApp(useCase: GetLatestIndicatorsUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { indicatorsRoute() }
        }
    }

    private fun Application.configureKoin(useCase: GetLatestIndicatorsUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testIndicators() = LatestIndicators(
        symbol = "AAPL",
        date = LocalDate(2024, 6, 15),
        rsi = 45.23,
        macd = LatestIndicators.MacdSnapshot(macd = 1.5, signal = 0.8, histogram = 0.7),
        bb = LatestIndicators.BollingerSnapshot(upper = 270.0, middle = 260.0, lower = 250.0),
        sma50 = 174.2,
        sma200 = 168.9,
        ema50 = 173.8,
        ema200 = 169.1
    )
}
