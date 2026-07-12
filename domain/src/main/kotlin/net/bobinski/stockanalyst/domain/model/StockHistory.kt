package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StockHistory(
    val symbol: String,
    val name: String,
    val period: String,
    val interval: String,
    val prices: List<HistoricalPrice>,
    val adjustment: PriceAdjustment,
    val indicators: Indicators? = null,
    val currency: String? = null,
    val requestedFrom: LocalDate? = null,
    val requestedTo: LocalDate? = null,
    val provenance: DataProvenance
)

@Serializable
enum class PriceAdjustment {
    @SerialName("split-adjusted")
    SPLIT_ADJUSTED
}
