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
import net.bobinski.stockanalyst.domain.model.Price
import net.bobinski.stockanalyst.domain.usecase.GetPriceUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class PriceRouteTest {

    @Test
    fun `responds with price when request is valid`() = testApplication {
        val useCase = mockk<GetPriceUseCase>()
        coEvery { useCase.invoke("AAPL", null) } returns testPrice()
        configureApp(useCase)

        val response = client.get("/price/AAPL")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"lastPrice\""))
    }

    @Test
    fun `responds with 404 when symbol is unknown`() = testApplication {
        val useCase = mockk<GetPriceUseCase>()
        coEvery { useCase.invoke("INVALID", null) } throws BackendDataException.unknownSymbol("INVALID")
        configureApp(useCase)

        val response = client.get("/price/INVALID")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<GetPriceUseCase>()
        coEvery { useCase.invoke("AAPL", null) } throws BackendDataException.backendError("AAPL")
        configureApp(useCase)

        val response = client.get("/price/AAPL")

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `responds with 400 for invalid symbol`() = testApplication {
        val useCase = mockk<GetPriceUseCase>()
        configureApp(useCase)

        val response = client.get("/price/DROP%20TABLE")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `passes conversion parameter to use case`() = testApplication {
        val useCase = mockk<GetPriceUseCase>()
        coEvery { useCase.invoke("AAPL", "eur=x") } returns testPrice()
        configureApp(useCase)

        client.get("/price/AAPL?conversion=eur=x")

        coVerify { useCase.invoke("AAPL", "eur=x") }
    }

    private fun ApplicationTestBuilder.configureApp(useCase: GetPriceUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { priceRoute() }
        }
    }

    private fun Application.configureKoin(useCase: GetPriceUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }

    private fun testPrice() = Price(
        symbol = "AAPL",
        name = "Apple Inc.",
        date = LocalDate(2024, 6, 15),
        lastPrice = 195.0,
        gain = Price.Gain(
            daily = 0.01, weekly = 0.02, monthly = 0.05,
            quarterly = 0.1, yearly = 0.25
        )
    )
}
