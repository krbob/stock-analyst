package net.bobinski.stockanalyst.route

import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.DataProvenance
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.MarketDataSource
import kotlin.time.Instant

internal fun testProvenance() = DataProvenance(
    source = MarketDataSource.YAHOO_FINANCE,
    retrievedAt = Instant.parse("2024-06-15T12:00:00Z"),
    marketDate = LocalDate(2024, 6, 15),
    currency = "USD",
    unitScale = 1.0,
    adjustment = DataAdjustment.SPLIT_ADJUSTED,
    coverageFrom = LocalDate(2024, 1, 2),
    coverageTo = LocalDate(2024, 6, 15),
    status = DataStatus.FRESH
)
