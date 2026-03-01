package net.bobinski.stockanalyst.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class StockHistory(
    val symbol: String,
    val name: String,
    val period: String,
    val prices: List<HistoricalPrice>
)
