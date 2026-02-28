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
import net.bobinski.stockanalyst.domain.model.Analysis
import net.bobinski.stockanalyst.domain.usecase.CompareStocksUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class CompareRouteTest {

    @Test
    fun `responds with analyses for valid symbols`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        coEvery { useCase.invoke(listOf("AAPL", "MSFT"), null) } returns
            listOf(testAnalysis("AAPL"), testAnalysis("MSFT"))
        configureApp(useCase)

        val response = client.get("/compare?symbols=AAPL,MSFT")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("AAPL"))
        assertTrue(body.contains("MSFT"))
    }

    @Test
    fun `responds with 400 when symbols parameter is missing`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        configureApp(useCase)

        val response = client.get("/compare")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Missing symbols"))
    }

    @Test
    fun `responds with 400 when too many symbols`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        configureApp(useCase)

        val symbols = (1..11).joinToString(",") { "SYM$it" }
        val response = client.get("/compare?symbols=$symbols")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Too many symbols"))
    }

    @Test
    fun `responds with 400 for invalid symbol in list`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        configureApp(useCase)

        val response = client.get("/compare?symbols=AAPL,DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid symbol"))
    }

    @Test
    fun `responds with 404 when any symbol is unknown`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        coEvery { useCase.invoke(listOf("AAPL", "INVALID"), null) } throws
            BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/compare?symbols=AAPL,INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `passes conversion parameter to use case`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        coEvery { useCase.invoke(listOf("AAPL"), "eur=x") } returns listOf(testAnalysis("AAPL"))
        configureApp(useCase)

        client.get("/compare?symbols=AAPL&conversion=eur=x")

        coVerify { useCase.invoke(listOf("AAPL"), "eur=x") }
    }

    private fun ApplicationTestBuilder.configureApp(useCase: CompareStocksUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { compareRoute() }
        }
    }

    private fun Application.configureKoin(useCase: CompareStocksUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testAnalysis(symbol: String) = Analysis(
        symbol = symbol,
        name = "Test",
        date = LocalDate(2024, 6, 15),
        lastPrice = 195.0,
        gain = Analysis.Gain(0.01, 0.02, 0.05, 0.1, 0.25),
        rsi = Analysis.Rsi(55.0, 60.0, 65.0),
        macd = Analysis.Macd(1.5, 1.2, 0.3),
        bollingerBands = Analysis.BollingerBands(200.0, 195.0, 190.0),
        movingAverages = Analysis.MovingAverages(193.0, 180.0, 194.0, 182.0),
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
