package net.bobinski.stockanalyst.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val symbol: String,
    val name: String,
    val exchange: String,
    val quoteType: String
)
