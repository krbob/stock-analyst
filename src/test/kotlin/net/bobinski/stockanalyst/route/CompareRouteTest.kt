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
import net.bobinski.stockanalyst.domain.model.CompareResult
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.usecase.CompareStocksUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class CompareRouteTest {

    @Test
    fun `responds with results for valid symbols`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        coEvery { useCase.invoke(listOf("AAPL", "MSFT"), null) } returns
            listOf(
                CompareResult("AAPL", testQuote("AAPL")),
                CompareResult("MSFT", testQuote("MSFT"))
            )
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
    fun `returns partial results when one symbol is unknown`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        coEvery { useCase.invoke(listOf("AAPL", "INVALID"), null) } returns
            listOf(
                CompareResult("AAPL", testQuote("AAPL")),
                CompareResult("INVALID", error = "Unknown symbol: INVALID")
            )
        configureApp(useCase)

        val response = client.get("/compare?symbols=AAPL,INVALID")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("AAPL"))
        assertTrue(body.contains("Unknown symbol"))
    }

    @Test
    fun `passes currency parameter to use case`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        coEvery { useCase.invoke(listOf("AAPL"), "EUR") } returns
            listOf(CompareResult("AAPL", testQuote("AAPL")))
        configureApp(useCase)

        client.get("/compare?symbols=AAPL&currency=EUR")

        coVerify { useCase.invoke(listOf("AAPL"), "EUR") }
    }

    @Test
    fun `responds with 400 when symbols is only commas`() = testApplication {
        val useCase = mockk<CompareStocksUseCase>()
        configureApp(useCase)

        val response = client.get("/compare?symbols=,,,")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("No symbols"))
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

    private fun testQuote(symbol: String) = Quote(
        symbol = symbol,
        name = "Test",
        date = LocalDate(2024, 6, 15),
        lastPrice = 195.0,
        gain = Quote.Gain(0.01, 0.02, 0.05, 0.1, 0.15, 0.12, 0.25, 0.8),
        peRatio = 30.0,
        pbRatio = 45.0,
        eps = 6.5,
        roe = 1.5,
        marketCap = 3_000_000_000.0,
        beta = 1.2,
        dividendYield = 0.005,
        dividendGrowth = 0.042,
        fiftyTwoWeekHigh = 210.0,
        fiftyTwoWeekLow = 150.0,
        sector = "Technology",
        industry = "Consumer Electronics",
        earningsDate = LocalDate(2024, 7, 25),
        recommendation = "buy",
        analystCount = 40
    )
}
