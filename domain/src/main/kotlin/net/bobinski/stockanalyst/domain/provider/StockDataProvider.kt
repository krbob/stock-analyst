package net.bobinski.stockanalyst.domain.provider

import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.DividendPayment
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.SearchResult

interface StockDataProvider {
    suspend fun getHistory(
        symbol: String,
        period: Period,
        interval: Interval = Interval.DAILY
    ): Collection<HistoricalPrice>

    suspend fun getInfo(symbol: String): BasicInfo?
    suspend fun getDividends(symbol: String): List<DividendPayment>
    suspend fun search(query: String): List<SearchResult>

    fun resolveConversionSymbol(from: String, to: String): String

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

    enum class Interval(val value: String) {
        _1m("1m"),
        _5m("5m"),
        _15m("15m"),
        _30m("30m"),
        _1h("1h"),
        DAILY("1d"),
        WEEKLY("1wk"),
        MONTHLY("1mo");

        val isIntraday: Boolean get() = ordinal <= _1h.ordinal

        val durationMinutes: Int
            get() = when (this) {
                _1m -> 1
                _5m -> 5
                _15m -> 15
                _30m -> 30
                _1h -> 60
                else -> error("Not an intraday interval: $this")
            }
    }
}
