package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class StockHistory(
    val symbol: String,
    val name: String,
    val period: String,
    val interval: String,
    val prices: List<HistoricalPrice>,
    val indicators: Indicators? = null,
    val currency: String? = null,
    val requestedFrom: LocalDate? = null,
    val requestedTo: LocalDate? = null
)
