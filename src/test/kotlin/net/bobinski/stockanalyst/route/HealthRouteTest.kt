package net.bobinski.stockanalyst.route

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthRouteTest {
    @Test
    fun `responds with ok status`() = testApplication {
        application {
            routing { healthRoute() }
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }
}
