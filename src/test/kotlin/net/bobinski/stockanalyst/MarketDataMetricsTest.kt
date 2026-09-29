package net.bobinski.stockanalyst

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.DataProvenance
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.HistoryPartialReason
import net.bobinski.stockanalyst.domain.model.MarketDataSource
import net.bobinski.stockanalyst.domain.model.PriceAdjustment
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.model.StockHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.getKoin
import kotlin.time.Instant

class MarketDataMetricsTest {
    @Test
    fun `partial reasons are counted once and do not change the public response schema`() = testApplication {
        lateinit var registry: RequestMetricsRegistry
        application {
            module()
            registry = getKoin().get()
            routing {
                get("/test/partial/{cause}") {
                    val cause = call.parameters["cause"]?.let { name ->
                        HistoryPartialReason.entries.find { it.name.lowercase() == name }
                    }
                    call.respond(history(DataStatus.PARTIAL).copy(partialReason = cause))
                }
            }
        }
        startApplication()

        for (reason in listOf("empty_range", "fx_coverage", "other")) {
            val response = client.get("/test/partial/$reason")
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(response.bodyAsText().contains("partialReason"))
            assertTrue(registry.scrape().contains(
                "stock_analyst_market_data_partial_responses_total{operation=\"history\",scope=\"live\",reason=\"$reason\"} 1"
            ))
        }
        assertTrue(registry.scrape().contains("operation=\"history\",scope=\"live\",status=\"PARTIAL\"} 3"))
        assertFalse(registry.scrape().contains("PRIVATE"))
    }

    @Test
    fun `HTTP success with stale data is counted once without exposing symbols`() = testApplication {
        lateinit var registry: RequestMetricsRegistry
        application {
            module()
            registry = getKoin().get()
            routing {
                get("/test/market-data") { call.respond(history(DataStatus.STALE)) }
            }
        }
        startApplication()
        val baseline = registry.scrape()
        assertTrue(baseline.contains("operation=\"history\",scope=\"live\",status=\"STALE\"} 0"))

        assertEquals(HttpStatusCode.OK, client.get("/test/market-data").status)

        val scrape = registry.scrape()
        assertTrue(scrape.contains("operation=\"history\",scope=\"live\",status=\"STALE\"} 1"), scrape)
        assertTrue(scrape.contains("stock_analyst_market_data_age_seconds_count{operation=\"history\"} 1"))
        assertFalse(scrape.contains("PRIVATE"))
    }

    @Test
    fun `archival history does not pollute live age or stale counters`() {
        val metrics = MarketDataMetrics()
        val archival = history(DataStatus.FRESH).copy(requestedTo = LocalDate(2024, 6, 1))
        metrics.record(MarketDataObservation.from(archival)!!)

        val scrape = metrics.scrape()
        assertTrue(scrape.contains("operation=\"history\",scope=\"historical\",status=\"FRESH\"} 1"))
        assertTrue(scrape.contains("stock_analyst_market_data_age_seconds_count{operation=\"history\"} 0"))
        assertTrue(scrape.contains("operation=\"history\",scope=\"live\",status=\"STALE\"} 0"))
    }

    @Test
    fun `quote price freshness is independent of incomplete long term analytics`() {
        val quote = mockk<Quote>()
        every { quote.provenance } returns provenance(DataStatus.PARTIAL).copy(priceStatus = DataStatus.FRESH)

        val observation = MarketDataObservation.from(quote)!!
        assertEquals(DataStatus.FRESH, observation.status)
        val metrics = MarketDataMetrics()
        metrics.record(observation)
        assertTrue(metrics.scrape().contains("operation=\"quote\",scope=\"live\",reason=\"other\"} 0"))
    }

    @Test
    fun `market timestamp takes precedence over date and histogram buckets are cumulative`() {
        val metrics = MarketDataMetrics()
        val response = history(DataStatus.FRESH).copy(
            provenance = provenance(DataStatus.FRESH).copy(marketTimestamp = Instant.parse("2024-06-17T11:30:00Z"))
        )
        metrics.record(MarketDataObservation.from(response)!!)

        val scrape = metrics.scrape()
        assertTrue(scrape.contains("operation=\"history\",le=\"60\"} 0"))
        assertTrue(scrape.contains("operation=\"history\",le=\"3600\"} 1"))
        assertTrue(scrape.contains("operation=\"history\",le=\"+Inf\"} 1"))
        assertTrue(scrape.contains("stock_analyst_market_data_age_seconds_sum{operation=\"history\"} 1800.000"))
    }

    private fun history(status: DataStatus) = StockHistory(
        symbol = "PRIVATE",
        name = "Private instrument",
        period = "1d",
        interval = "1d",
        prices = emptyList(),
        adjustment = PriceAdjustment.SPLIT_ADJUSTED,
        provenance = provenance(status)
    )

    private fun provenance(status: DataStatus) = DataProvenance(
        source = MarketDataSource.YAHOO_FINANCE,
        retrievedAt = Instant.parse("2024-06-17T12:00:00Z"),
        marketDate = LocalDate(2024, 6, 1),
        unitScale = 1.0,
        adjustment = DataAdjustment.SPLIT_ADJUSTED,
        status = status
    )
}
