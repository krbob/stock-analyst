package net.bobinski.stockanalyst.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CompareResult(
    val symbol: String,
    val data: Quote? = null,
    val error: String? = null
)
