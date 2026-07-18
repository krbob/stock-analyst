package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class DataProvenance(
    val source: MarketDataSource,
    val retrievedAt: Instant,
    val marketTimestamp: Instant? = null,
    val marketDate: LocalDate? = null,
    val currency: String? = null,
    val unitScale: Double,
    val adjustment: DataAdjustment,
    val coverageFrom: LocalDate? = null,
    val coverageTo: LocalDate? = null,
    val status: DataStatus,
    val priceStatus: DataStatus? = null,
    val analyticsStatus: AnalyticsStatus? = null,
    val analyticsLimitations: List<String>? = null
)

@Serializable
enum class AnalyticsStatus {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE
}

@Serializable
enum class MarketDataSource {
    YAHOO_FINANCE
}

@Serializable
enum class DataAdjustment {
    RAW,
    SPLIT_ADJUSTED,
    TOTAL_RETURN
}

@Serializable
enum class DataStatus {
    FRESH,
    STALE,
    PARTIAL,
    ERROR
}
