package net.bobinski.stockanalyst

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestMetricsTest {

    @Test
    fun `metrics expose bounded route labels without request data`() = testApplication {
        application { module() }

        client.get("/v1/quote/AAPL?currency=INVALID") {
            header(HttpHeaders.XRequestId, "sensitive-request-id")
        }
        client.get("/v1/not-a-route")
        client.get("/healthz")
        val response = client.get("/metrics")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            body.contains(
                "stock_analyst_http_requests_total" +
                    "{method=\"GET\",route=\"/v1/quote/{stock}\",status=\"400\"} 1"
            ),
            body
        )
        assertTrue(
            body.contains(
                "stock_analyst_http_requests_total" +
                    "{method=\"GET\",route=\"unmatched\",status=\"404\"} 1"
            ),
            body
        )
        assertTrue(body.contains("stock_analyst_http_request_duration_seconds_bucket"))
        assertFalse(body.contains("AAPL"))
        assertFalse(body.contains("sensitive-request-id"))
        assertFalse(body.contains("/healthz"))
    }

    @Test
    fun `histogram buckets and sum are cumulative and deterministic`() {
        val registry = RequestMetricsRegistry()

        registry.record("GET", "/v1/quote/{stock}", 200, 40_000_000)
        val body = registry.scrape()

        assertTrue(body.contains("le=\"0.01\"} 0"))
        assertTrue(body.contains("le=\"0.05\"} 1"))
        assertTrue(body.contains("le=\"+Inf\"} 1"))
        assertTrue(body.contains("_sum{method=\"GET\",route=\"/v1/quote/{stock}\",status=\"200\"} 0.040000000"))
        assertTrue(body.contains("_count{method=\"GET\",route=\"/v1/quote/{stock}\",status=\"200\"} 1"))
    }
}
