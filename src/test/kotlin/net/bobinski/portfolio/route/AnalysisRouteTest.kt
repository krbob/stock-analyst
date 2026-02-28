package net.bobinski.portfolio.route

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
import net.bobinski.portfolio.core.dependency.CoreModule
import net.bobinski.portfolio.domain.error.BackendDataException
import net.bobinski.portfolio.domain.model.Analysis
import net.bobinski.portfolio.domain.usecase.AnalyzeStockUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class AnalysisRouteTest {

    @Test
    fun `responds with analysis when request is valid`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        val expected = testAnalysis()
        coEvery { useCase.invoke("AAPL", null) } returns expected
        configureApp(useCase)

        val response = client.get("/analysis/AAPL")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"name\":\"Apple Inc.\""))
    }

    @Test
    fun `responds with 404 when symbol is unknown`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        coEvery { useCase.invoke("INVALID", null) } throws BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/analysis/INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Unknown symbol"))
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        coEvery { useCase.invoke("AAPL", null) } throws BackendDataException.backendError("AAPL")
        configureApp(useCase)

        val response = client.get("/analysis/AAPL")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("Backend error"))
    }

    @Test
    fun `responds with 422 when conversion data is insufficient`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        coEvery { useCase.invoke("AAPL", "eur=x") } throws
            BackendDataException.insufficientConversion("eur=x")
        configureApp(useCase)

        val response = client.get("/analysis/AAPL?conversion=eur=x")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("Not enough conversion history"))
    }

    @Test
    fun `responds with 500 when unexpected exception is thrown`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        coEvery { useCase.invoke("FAIL", null) } throws RuntimeException("Something went wrong")
        configureApp(useCase)

        val response = client.get("/analysis/FAIL")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("Something went wrong"))
    }

    @Test
    fun `responds with 400 for invalid symbol`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        configureApp(useCase)

        val response = client.get("/analysis/DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid symbol"))
    }

    @Test
    fun `passes conversion parameter to use case`() = testApplication {
        val useCase = mockk<AnalyzeStockUseCase>()
        coEvery { useCase.invoke("AAPL", "eur=x") } returns testAnalysis()
        configureApp(useCase)

        client.get("/analysis/AAPL?conversion=eur=x")

        coVerify { useCase.invoke("AAPL", "eur=x") }
    }

    private fun ApplicationTestBuilder.configureApp(useCase: AnalyzeStockUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { analysisRoute() }
        }
    }

    private fun Application.configureKoin(useCase: AnalyzeStockUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testAnalysis() = Analysis(
        symbol = "AAPL",
        name = "Apple Inc.",
        date = LocalDate(2024, 6, 15),
        lastPrice = 195.0,
        gain = Analysis.Gain(
            daily = 0.01, weekly = 0.02, monthly = 0.05,
            quarterly = 0.1, yearly = 0.25
        ),
        rsi = Analysis.Rsi(daily = 55.0, weekly = 60.0, monthly = 65.0),
        macd = Analysis.Macd(macd = 1.5, signal = 1.2, histogram = 0.3),
        bollingerBands = Analysis.BollingerBands(upper = 200.0, middle = 195.0, lower = 190.0),
        movingAverages = Analysis.MovingAverages(sma50 = 193.0, sma200 = 180.0, ema50 = 194.0, ema200 = 182.0),
        atr = 3.5,
        dividendYield = 0.005,
        dividendGrowth = 0.042,
        peRatio = 30.0f,
        pbRatio = 45.0f,
        eps = 6.5f,
        roe = 1.5f,
        marketCap = 3_000_000_000.0,
        recommendation = "buy",
        analystCount = 40,
        fiftyTwoWeekHigh = 210.0f,
        fiftyTwoWeekLow = 150.0f,
        beta = 1.2f,
        sector = "Technology",
        industry = "Consumer Electronics",
        earningsDate = "2024-07-25"
    )
}
