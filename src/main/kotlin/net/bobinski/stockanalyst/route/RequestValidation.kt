package net.bobinski.stockanalyst.route

import net.bobinski.stockanalyst.domain.model.IndicatorCatalog
import net.bobinski.stockanalyst.domain.provider.StockDataProvider

internal val symbolPattern = Regex("^[a-zA-Z0-9.\\-=^]{1,20}$")
internal val currencyPattern = Regex("^[A-Za-z]{3}$")
internal val validPeriods = StockDataProvider.Period.entries.associateBy { it.value }
internal val validIntervals = StockDataProvider.Interval.entries.associateBy { it.value }
internal val validIndicators = IndicatorCatalog.validKeys
internal val validIndicatorsText = validIndicators.toList().sorted().joinToString()

internal data class ParsedIndicators(
    val values: Set<String>,
    val invalid: Set<String>
)

internal fun parseIndicators(raw: String?): ParsedIndicators {
    if (raw.isNullOrBlank()) return ParsedIndicators(emptySet(), emptySet())

    val normalized = raw.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    val invalid = normalized.filter { it !in validIndicators }.toSet()
    return ParsedIndicators(
        values = normalized.toSet(),
        invalid = invalid
    )
}
