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
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.usecase.GetQuoteUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class QuoteRouteTest {

    @Test
    fun `responds with quote when request is valid`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        val expected = testQuote()
        coEvery { useCase.invoke("AAPL", null) } returns expected
        configureApp(useCase)

        val response = client.get("/quote/AAPL")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"name\":\"Apple Inc.\""))
    }

    @Test
    fun `responds with 404 when symbol is unknown`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        coEvery { useCase.invoke("INVALID", null) } throws BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/quote/INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Unknown symbol"))
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        coEvery { useCase.invoke("AAPL", null) } throws BackendDataException.backendError("AAPL")
        configureApp(useCase)

        val response = client.get("/quote/AAPL")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("Backend error"))
    }

    @Test
    fun `responds with 422 when conversion data is insufficient`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        coEvery { useCase.invoke("AAPL", "EUR") } throws
            BackendDataException.insufficientConversion("EUR=X")
        configureApp(useCase)

        val response = client.get("/quote/AAPL?currency=EUR")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("Not enough conversion history"))
    }

    @Test
    fun `responds with 422 when currency conversion metadata is unavailable`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        coEvery { useCase.invoke("AAPL", "EUR") } throws
            BackendDataException.currencyUnavailable("AAPL")
        configureApp(useCase)

        val response = client.get("/quote/AAPL?currency=EUR")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("Currency conversion is unavailable"))
    }

    @Test
    fun `responds with 500 when unexpected exception is thrown`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        coEvery { useCase.invoke("FAIL", null) } throws RuntimeException("Something went wrong")
        configureApp(useCase)

        val response = client.get("/quote/FAIL")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("Something went wrong"))
    }

    @Test
    fun `responds with 400 for invalid symbol`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        configureApp(useCase)

        val response = client.get("/quote/DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid symbol"))
    }

    @Test
    fun `passes currency parameter to use case`() = testApplication {
        val useCase = mockk<GetQuoteUseCase>()
        coEvery { useCase.invoke("AAPL", "EUR") } returns testQuote()
        configureApp(useCase)

        client.get("/quote/AAPL?currency=EUR")

        coVerify { useCase.invoke("AAPL", "EUR") }
    }

    private fun ApplicationTestBuilder.configureApp(useCase: GetQuoteUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { quoteRoute() }
        }
    }

    private fun Application.configureKoin(useCase: GetQuoteUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testQuote() = Quote(
        symbol = "AAPL",
        name = "Apple Inc.",
        date = LocalDate(2024, 6, 15),
        lastPrice = 195.0,
        gain = Quote.Gain(
            daily = 0.01, weekly = 0.02, monthly = 0.05,
            quarterly = 0.1, halfYearly = 0.15, ytd = 0.12,
            yearly = 0.25, fiveYear = 0.8
        ),
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
