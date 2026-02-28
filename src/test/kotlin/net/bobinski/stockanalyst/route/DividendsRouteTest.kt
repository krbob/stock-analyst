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
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.core.dependency.CoreModule
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.DividendHistory
import net.bobinski.stockanalyst.domain.model.DividendPayment
import net.bobinski.stockanalyst.domain.usecase.GetDividendHistoryUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class DividendsRouteTest {

    @Test
    fun `responds with dividend history when request is valid`() = testApplication {
        val useCase = mockk<GetDividendHistoryUseCase>()
        coEvery { useCase.invoke("AAPL") } returns testDividendHistory()
        configureApp(useCase)

        val response = client.get("/dividends/AAPL")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"payments\""))
    }

    @Test
    fun `responds with 404 when symbol is unknown`() = testApplication {
        val useCase = mockk<GetDividendHistoryUseCase>()
        coEvery { useCase.invoke("INVALID") } throws BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/dividends/INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<GetDividendHistoryUseCase>()
        coEvery { useCase.invoke("AAPL") } throws BackendDataException.backendError("AAPL")
        configureApp(useCase)

        val response = client.get("/dividends/AAPL")

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `responds with 400 for invalid symbol`() = testApplication {
        val useCase = mockk<GetDividendHistoryUseCase>()
        configureApp(useCase)

        val response = client.get("/dividends/DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `responds with 500 when unexpected exception is thrown`() = testApplication {
        val useCase = mockk<GetDividendHistoryUseCase>()
        coEvery { useCase.invoke("FAIL") } throws RuntimeException("Something went wrong")
        configureApp(useCase)

        val response = client.get("/dividends/FAIL")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    private fun ApplicationTestBuilder.configureApp(useCase: GetDividendHistoryUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { dividendsRoute() }
        }
    }

    private fun Application.configureKoin(useCase: GetDividendHistoryUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testDividendHistory() = DividendHistory(
        symbol = "AAPL",
        name = "Apple Inc.",
        payments = listOf(
            DividendPayment(LocalDate(2024, 2, 9), 0.24),
            DividendPayment(LocalDate(2024, 5, 10), 0.25)
        ),
        summary = DividendHistory.Summary(
            currentYield = 0.004,
            growth = 0.042,
            frequency = 4
        )
    )
}
