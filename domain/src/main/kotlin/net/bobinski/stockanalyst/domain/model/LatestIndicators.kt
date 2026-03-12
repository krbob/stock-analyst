package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class LatestIndicators(
    val symbol: String,
    val date: LocalDate,
    val rsi: Double? = null,
    val macd: MacdSnapshot? = null,
    val bb: BollingerSnapshot? = null,
    val sma50: Double? = null,
    val sma200: Double? = null,
    val ema50: Double? = null,
    val ema200: Double? = null
) {

    @Serializable
    data class MacdSnapshot(
        val macd: Double,
        val signal: Double,
        val histogram: Double
    )

    @Serializable
    data class BollingerSnapshot(
        val upper: Double,
        val middle: Double,
        val lower: Double
    )
}
