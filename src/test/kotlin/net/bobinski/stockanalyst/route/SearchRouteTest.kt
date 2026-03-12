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
import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.core.dependency.CoreModule
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.SearchResult
import net.bobinski.stockanalyst.domain.usecase.SearchTickerUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class SearchRouteTest {

    @Test
    fun `responds with search results when request is valid`() = testApplication {
        val useCase = mockk<SearchTickerUseCase>()
        coEvery { useCase.invoke("apple-ok") } returns listOf(
            SearchResult("AAPL", "Apple Inc.", "NMS", "EQUITY")
        )
        configureApp(useCase)

        val response = client.get("/search/apple-ok")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"symbol\":\"AAPL\""))
    }

    @Test
    fun `responds with 400 for overly long query`() = testApplication {
        val useCase = mockk<SearchTickerUseCase>()
        configureApp(useCase)

        val response = client.get("/search/${"a".repeat(51)}")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Query too long"))
    }

    @Test
    fun `responds with 502 when backend fails`() = testApplication {
        val useCase = mockk<SearchTickerUseCase>()
        coEvery { useCase.invoke("apple-fail") } throws BackendDataException.backendError("apple-fail")
        configureApp(useCase)

        val response = client.get("/search/apple-fail")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("Backend error"))
    }

    @Test
    fun `passes query to use case`() = testApplication {
        val useCase = mockk<SearchTickerUseCase>()
        coEvery { useCase.invoke("apple-pass") } returns emptyList()
        configureApp(useCase)

        client.get("/search/apple-pass")

        coVerify { useCase.invoke("apple-pass") }
    }

    private fun ApplicationTestBuilder.configureApp(useCase: SearchTickerUseCase) {
        application {
            configureKoin(useCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { searchRoute() }
        }
    }

    private fun Application.configureKoin(useCase: SearchTickerUseCase) {
        install(Koin) {
            modules(
                CoreModule,
                module { single { useCase } }
            )
        }
    }
}
