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
    fun `legacy health and liveness stay independent from readiness`() = testApplication {
        application {
            routing { healthRoute { false } }
        }

        val legacy = client.get("/health")
        val liveness = client.get("/healthz")
        val readiness = client.get("/readyz")

        assertEquals(HttpStatusCode.OK, legacy.status)
        assertTrue(legacy.bodyAsText().contains("ok"))
        assertEquals(HttpStatusCode.OK, liveness.status)
        assertEquals("{\"status\":\"UP\"}", liveness.bodyAsText())
        assertEquals(HttpStatusCode.ServiceUnavailable, readiness.status)
        assertEquals("{\"status\":\"DOWN\"}", readiness.bodyAsText())
    }

    @Test
    fun `readiness is up when dependency check succeeds`() = testApplication {
        application {
            routing { healthRoute { true } }
        }

        val response = client.get("/readyz")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"UP\"}", response.bodyAsText())
    }
}
