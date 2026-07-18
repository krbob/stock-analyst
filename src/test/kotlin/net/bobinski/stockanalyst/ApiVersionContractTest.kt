package net.bobinski.stockanalyst

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bobinski.stockanalyst.domain.model.AnalyticsStatus
import net.bobinski.stockanalyst.domain.model.BollingerValue
import net.bobinski.stockanalyst.domain.model.CompareResult
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.DataProvenance
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.Indicators
import net.bobinski.stockanalyst.domain.model.LatestIndicators
import net.bobinski.stockanalyst.domain.model.MacdValue
import net.bobinski.stockanalyst.domain.model.MarketDataSource
import net.bobinski.stockanalyst.domain.model.PriceAdjustment
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.model.SearchResult
import net.bobinski.stockanalyst.domain.model.SingleValue
import net.bobinski.stockanalyst.domain.model.StockHistory
import net.bobinski.stockanalyst.route.ApiErrorCode
import net.bobinski.stockanalyst.route.ApiErrorResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiVersionContractTest {

    @Test
    fun `canonical v1 routes preserve legacy behavior`() = testApplication {
        application { module() }

        val cases = listOf(
            "/quote/AAPL?currency=INVALID" to "/v1/quote/AAPL?currency=INVALID",
            "/compare" to "/v1/compare",
            "/history/AAPL?period=invalid" to "/v1/history/AAPL?period=invalid",
            "/indicators/AAPL?period=invalid" to "/v1/indicators/AAPL?period=invalid",
            "/search/${"a".repeat(51)}" to "/v1/search/${"a".repeat(51)}"
        )

        cases.forEach { (legacyPath, canonicalPath) ->
            val legacy = client.get(legacyPath) {
                header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
            }
            val canonical = client.get(canonicalPath) {
                header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
            }

            assertEquals(HttpStatusCode.BadRequest, canonical.status, canonicalPath)
            assertEquals(legacy.status, canonical.status, canonicalPath)
            assertEquals(legacy.bodyAsText(), canonical.bodyAsText(), canonicalPath)
        }
    }

    @Test
    fun `bundled OpenAPI describes every canonical route and shared error contract`() = testApplication {
        application { module() }

        val response = client.get("/openapi/v1.json") {
            header(HttpHeaders.XRequestId, CONTRACT_REQUEST_ID)
        }
        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val paths = document.getValue("paths").jsonObject
        val schemas = document.getValue("components").jsonObject.getValue("schemas").jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType())
        assertEquals(CONTRACT_REQUEST_ID, response.headers[HttpHeaders.XRequestId])
        assertEquals("3.0.3", document.getValue("openapi").toString().trim('"'))
        assertEquals(
            setOf(
                "/v1/quote/{stock}",
                "/v1/compare",
                "/v1/history/{stock}",
                "/v1/indicators/{stock}",
                "/v1/search/{query}"
            ),
            paths.keys
        )
        assertTrue(paths.values.all { "get" in it.jsonObject })
        assertTrue("ApiError" in schemas)
        assertTrue("ApiErrorCode" in schemas)

        MODEL_SERIALIZERS.forEach { (schemaName, serializer) ->
            val properties = schemas.getValue(schemaName).jsonObject
                .getValue("properties").jsonObject.keys
            assertEquals(serializer.fieldNames(), properties, schemaName)
        }

        val errorCodes = schemas.getValue("ApiErrorCode").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        val adjustments = schemas.getValue("PriceAdjustment").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(ApiErrorCode.entries.map(ApiErrorCode::name), errorCodes)
        assertEquals(PriceAdjustment.serializer().descriptor.fieldNames(), adjustments.toSet())
        assertEquals(
            MarketDataSource.entries.map(MarketDataSource::name),
            schemas.enumValues("MarketDataSource")
        )
        assertEquals(DataAdjustment.entries.map(DataAdjustment::name), schemas.enumValues("DataAdjustment"))
        assertEquals(DataStatus.entries.map(DataStatus::name), schemas.enumValues("DataStatus"))
        assertEquals(AnalyticsStatus.entries.map(AnalyticsStatus::name), schemas.enumValues("AnalyticsStatus"))
    }

    private fun kotlinx.serialization.json.JsonObject.enumValues(schemaName: String): List<String> =
        getValue(schemaName).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    private fun KSerializer<*>.fieldNames(): Set<String> = descriptor.fieldNames()

    private fun kotlinx.serialization.descriptors.SerialDescriptor.fieldNames(): Set<String> =
        (0 until elementsCount).map(::getElementName).toSet()

    private companion object {
        const val CONTRACT_REQUEST_ID = "stock-openapi-contract"

        val MODEL_SERIALIZERS = mapOf(
            "ApiError" to ApiErrorResponse.serializer(),
            "Quote" to Quote.serializer(),
            "Gain" to Quote.Gain.serializer(),
            "CompareResult" to CompareResult.serializer(),
            "StockHistory" to StockHistory.serializer(),
            "HistoricalPrice" to HistoricalPrice.serializer(),
            "Indicators" to Indicators.serializer(),
            "SingleValue" to SingleValue.serializer(),
            "BollingerValue" to BollingerValue.serializer(),
            "MacdValue" to MacdValue.serializer(),
            "LatestIndicators" to LatestIndicators.serializer(),
            "MacdSnapshot" to LatestIndicators.MacdSnapshot.serializer(),
            "BollingerSnapshot" to LatestIndicators.BollingerSnapshot.serializer(),
            "DataProvenance" to DataProvenance.serializer(),
            "SearchResult" to SearchResult.serializer()
        )
    }
}
