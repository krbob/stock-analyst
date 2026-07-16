package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.DataProvenance
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.MarketDataSource
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal fun marketDataProvenance(
    currentTimeProvider: CurrentTimeProvider,
    marketDate: LocalDate?,
    marketTimestampEpochSeconds: Long?,
    currency: String?,
    adjustment: DataAdjustment,
    coverageFrom: LocalDate?,
    coverageTo: LocalDate?,
    cadence: MarketDataCadence,
    partial: Boolean = false,
    freshnessReferenceDate: LocalDate? = null
): DataProvenance {
    val retrievedAt = currentTimeProvider.instant()
    val marketTimestamp = marketTimestampEpochSeconds.toInstantOrNull()
    val stale = when {
        freshnessReferenceDate != null -> marketDate == null ||
            marketDate < freshnessReferenceDate.minus(cadence.maxAgeDays, DateTimeUnit.DAY)
        marketTimestamp != null -> retrievedAt - marketTimestamp > cadence.maxAgeDays.days
        else -> marketDate == null ||
            marketDate < currentTimeProvider.localDate().minus(cadence.maxAgeDays, DateTimeUnit.DAY)
    }

    return DataProvenance(
        source = MarketDataSource.YAHOO_FINANCE,
        retrievedAt = retrievedAt,
        marketTimestamp = marketTimestamp,
        marketDate = marketDate,
        currency = currency,
        unitScale = 1.0,
        adjustment = adjustment,
        coverageFrom = coverageFrom,
        coverageTo = coverageTo,
        status = when {
            partial -> DataStatus.PARTIAL
            stale -> DataStatus.STALE
            else -> DataStatus.FRESH
        }
    )
}

internal enum class MarketDataCadence(val maxAgeDays: Int) {
    INTRADAY(4),
    DAILY(4),
    WEEKLY(10),
    MONTHLY(40)
}

internal fun Interval.marketDataCadence(): MarketDataCadence = when {
    isIntraday -> MarketDataCadence.INTRADAY
    this == Interval.WEEKLY -> MarketDataCadence.WEEKLY
    this == Interval.MONTHLY -> MarketDataCadence.MONTHLY
    else -> MarketDataCadence.DAILY
}

private fun Long?.toInstantOrNull(): Instant? = this?.let { epochSeconds ->
    runCatching { Instant.fromEpochSeconds(epochSeconds) }.getOrNull()
}
