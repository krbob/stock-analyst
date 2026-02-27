package net.bobinski.portfolio.domain.provider

import net.bobinski.portfolio.domain.model.BasicInfo
import net.bobinski.portfolio.domain.model.HistoricalPrice

interface StockDataProvider {
    suspend fun getHistory(symbol: String, period: Period): Collection<HistoricalPrice>
    suspend fun getInfo(symbol: String): BasicInfo?

    enum class Period(val value: String) {
        _1d("1d"),
        _5d("5d"),
        _1mo("1mo"),
        _3mo("3mo"),
        _6mo("6mo"),
        _1y("1y"),
        _2y("2y"),
        _5y("5y"),
        _10y("10y"),
        ytd("ytd"),
        max("max")
    }
}
